import hashlib
import hmac
import os
import secrets
import sqlite3
import time
from base64 import b64decode, b64encode
from contextlib import closing, contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DB_PATH = Path(os.environ.get("AIRPORT_WEB_DB", ROOT / "data" / "airport.sqlite3"))
ASTANA = timezone(timedelta(hours=5))
DEMO_PASSWORD = "Airport123"
DEMOS = (
    ("Қарлығаш", "user@airport.test", "passenger"),
    ("Бейбарс", "operator@airport.test", "operator"),
    ("Ерасыл", "admin@airport.test", "admin"),
)
CITIES = {
    "Астана": ("NQZ", "Қазақстан", 51.0222, 71.4669),
    "Алматы": ("ALA", "Қазақстан", 43.2389, 76.8897),
    "Шымкент": ("CIT", "Қазақстан", 42.3417, 69.5901),
    "Ақтау": ("SCO", "Қазақстан", 43.6532, 51.1975),
    "Атырау": ("GUW", "Қазақстан", 47.1068, 51.916),
    "Ыстанбұл": ("IST", "Түркия", 41.0082, 28.9784),
    "Дубай": ("DXB", "БАӘ", 25.2048, 55.2708),
    "Ташкент": ("TAS", "Өзбекстан", 41.2995, 69.2401),
}
FLIGHT_STATES = ("Уақтылы", "Кешігуде", "Отырғызу", "Тоқтатылды")
TICKET_STATES = ("Жаңа", "Қаралып жатыр", "Шешілді")
SEATS = tuple(f"{row}{letter}" for row in range(1, 6) for letter in "ABCDEF")


def now_ms():
    return int(time.time() * 1000)


def local_date(ms):
    return datetime.fromtimestamp(ms / 1000, ASTANA).date().isoformat()


def when(ms):
    return datetime.fromtimestamp(ms / 1000, ASTANA).strftime("%d.%m %H:%M")


def connect():
    db = sqlite3.connect(DB_PATH, timeout=8, isolation_level=None, check_same_thread=False)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys=ON")
    return db


def database():
    db = connect()
    try:
        yield db
    finally:
        db.close()


@contextmanager
def transaction(db):
    db.execute("BEGIN IMMEDIATE")
    try:
        yield
        db.commit()
    except BaseException:
        db.rollback()
        raise


def password_hash(password, salt):
    return b64encode(hashlib.pbkdf2_hmac("sha256", password.encode(), b64decode(salt), 120_000)).decode()


def new_password(password):
    salt = b64encode(secrets.token_bytes(16)).decode()
    return salt, password_hash(password, salt)


def password_ok(password, user):
    return hmac.compare_digest(password_hash(password, user["salt"]), user["password"])


def create_account(db, name, email, password, role):
    salt, digest = new_password(password)
    return db.execute(
        "INSERT INTO users(name,email,role,salt,password) VALUES (?,?,?,?,?)",
        (name, email, role, salt, digest),
    ).lastrowid


def init_db():
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    with closing(connect()) as db:
        db.execute("PRAGMA journal_mode=WAL")
        db.executescript((ROOT / "schema.sql").read_text(encoding="utf-8"))
        with transaction(db):
            for name, email, role in DEMOS:
                account = db.execute("SELECT * FROM users WHERE email=?", (email,)).fetchone()
                if role == "passenger":
                    if account is None:
                        create_account(db, name, email, DEMO_PASSWORD, role)
                elif account and account["role"] == role and password_ok(DEMO_PASSWORD, account):
                    raise RuntimeError("Set a private password for shared staff before starting Airport.")
        ensure_schedule(db)


def ensure_schedule(db):
    today = datetime.now(ASTANA).replace(hour=0, minute=0, second=0, microsecond=0)
    days = [today + timedelta(days=offset) for offset in range(6)]
    # Dashboard refreshes from multiple devices usually only need to read.
    covered = db.execute("SELECT COUNT(*) FROM demo_days WHERE day BETWEEN ? AND ?",
                         (days[0].date().isoformat(), days[-1].date().isoformat())).fetchone()[0]
    if covered == len(days):
        return
    routes = ("Алматы", "Шымкент", "Ақтау", "Ыстанбұл", "Дубай", "Ташкент")
    with transaction(db):
        number = db.execute("SELECT COALESCE(MAX(id),0)+201 FROM flights").fetchone()[0]
        for day in days:
            inserted = db.execute("INSERT OR IGNORE INTO demo_days(day) VALUES (?)", (day.date().isoformat(),))
            if not inserted.rowcount:
                continue
            for slot in range(5):
                arrival = slot == 4
                hour = 15 if arrival else 9 + slot * 3
                scheduled = int(day.replace(hour=hour, minute=40-slot*5).timestamp()*1000)
                city = routes[(day.timetuple().tm_yday + slot) % len(routes)]
                gate = f"{'A' if arrival else 'B'}{slot+2}"
                while True:
                    result = db.execute("INSERT OR IGNORE INTO flights(number,city,gate,time,direction,status) VALUES (?,?,?,?,?,0)",
                                        (f"AP {number}", city, gate, scheduled, int(arrival)))
                    number += 1
                    if result.rowcount:
                        break


def audit(db, actor, action, detail):
    db.execute("INSERT INTO ops_log(actor,action,detail,created) VALUES (?,?,?,?)",
               (actor, action, detail, now_ms()))


def flight_json(row):
    result = dict(row)
    result["code_city"] = CITIES.get(row["city"], ("—",))[0]
    result["local_time"] = when(row["time"])
    result["date"] = local_date(row["time"])
    return result


def trip_rows(db, user):
    return db.execute(
        "SELECT f.*,b.code,b.seat,b.checked,b.trip_tasks FROM bookings b JOIN flights f ON f.id=b.flight WHERE b.user=? ORDER BY f.time",
        (user,),
    ).fetchall()


def travel_passport(db, user):
    stamps = []
    countries = set()
    for group in db.execute("""SELECT city,COUNT(*) count,MAX(departure) last_departure
                               FROM travel_stamps WHERE user=? GROUP BY city
                               ORDER BY last_departure DESC,city""", (user,)):
        city = group["city"]
        info = CITIES.get(city)
        country = info[1] if info else None
        if country:
            countries.add(country)
        trips = [dict(row) for row in db.execute("""SELECT code,flight_number,city,departure,seat,completed
                     FROM travel_stamps WHERE user=? AND city=? ORDER BY departure DESC,id DESC""", (user, city))]
        for trip in trips:
            trip["local_time"] = datetime.fromtimestamp(trip["departure"] / 1000, ASTANA).strftime("%d.%m.%Y %H:%M")
        stamps.append({**dict(group), "code_city": info[0] if info else "—", "country": country, "trips": trips})
    return {"summary": {"trips": sum(item["count"] for item in stamps), "cities": len(stamps), "countries": len(countries)},
            "stamps": stamps}


def ticket_rows(db, user):
    who = user["id"]
    role = user["role"]
    where = " WHERE t.owner=?" if role == "passenger" else ""
    params = (who, who, who) if where else (who, who)
    return db.execute(
        """SELECT t.*,u.name,
           (SELECT COUNT(*) FROM messages m WHERE m.ticket=t.id AND m.sender!=?
            AND m.id>COALESCE((SELECT last_message FROM reads r WHERE r.ticket=t.id AND r.user=?),0)) unread
           FROM tickets t JOIN users u ON u.id=t.owner""" + where + " ORDER BY t.updated DESC,t.id DESC", params,
    ).fetchall()


def checkin_queue(db):
    now = datetime.now(ASTANA)
    end = (now + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
    return db.execute(
        """SELECT b.code,b.seat,f.number,f.city,f.time,u.name FROM bookings b
           JOIN flights f ON f.id=b.flight JOIN users u ON u.id=b.user
           WHERE b.checked=0 AND f.direction=0 AND f.status!=3 AND f.time>? AND f.time<?
           ORDER BY f.time,u.name""", (now_ms(), int(end.timestamp()*1000)),
    ).fetchall()
