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
    ("FlyArystan операторы", "flyarystan@airport.test", "operator"),
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


def create_account(db, name, email, password, role, airline_id=None):
    salt, digest = new_password(password)
    return db.execute(
        "INSERT INTO users(name,email,role,salt,password,airline_id) VALUES (?,?,?,?,?,?)",
        (name, email, role, salt, digest, airline_id),
    ).lastrowid


def init_db():
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    with closing(connect()) as db:
        db.execute("PRAGMA journal_mode=WAL")
        db.executescript((ROOT / "schema.sql").read_text(encoding="utf-8"))
        with transaction(db):
            if db.execute("PRAGMA user_version").fetchone()[0] < 1:
                sql = ""
                for line in (ROOT / "migrations/001_ticket_sales.sql").read_text(encoding="utf-8").splitlines(True):
                    sql += line
                    if sqlite3.complete_statement(sql):
                        db.execute(sql)
                        sql = ""
                db.execute("PRAGMA user_version=1")
            for name, email, role in DEMOS:
                account = db.execute("SELECT * FROM users WHERE email=?", (email,)).fetchone()
                if role == "passenger":
                    if account is None:
                        create_account(db, name, email, DEMO_PASSWORD, role)
                elif account and account["role"] == role and password_ok(DEMO_PASSWORD, account):
                    raise RuntimeError("Set a private password for shared staff before starting Airport.")
        ensure_schedule(db)


# A predictable teaching timetable gives each day a direct flight and a cheaper
# connection. Board times remain Astana local time, including for other cities.
DEMO_ROUTES = (
    ("KC", "Астана", "Алматы", 8, 20, 100, 22500),
    ("FS", "Астана", "Алматы", 9, 0, 100, 17200),
    ("KC", "Алматы", "Ыстанбұл", 12, 25, 340, 61500),
    ("KC", "Астана", "Ыстанбұл", 14, 15, 320, 98000),
    ("FS", "Алматы", "Астана", 7, 20, 105, 17800),
    ("KC", "Астана", "Дубай", 13, 15, 290, 82000),
    ("FS", "Астана", "Шымкент", 16, 10, 115, 19500),
    ("KC", "Ақтау", "Астана", 7, 0, 145, 28800),
    ("KC", "Астана", "Ақтау", 18, 30, 150, 29000),
    ("FS", "Астана", "Ташкент", 20, 0, 125, 38500),
)


def ensure_schedule(db):
    today = datetime.now(ASTANA).replace(hour=0, minute=0, second=0, microsecond=0)
    days = [today + timedelta(days=offset) for offset in range(6)]
    # Dashboard refreshes from multiple devices usually only need to read.
    covered = db.execute("SELECT COUNT(*) FROM market_days WHERE day BETWEEN ? AND ?",
                         (days[0].date().isoformat(), days[-1].date().isoformat())).fetchone()[0]
    if covered == len(days):
        return
    with transaction(db):
        carriers = dict(db.execute("SELECT code,id FROM airlines").fetchall())
        number = db.execute("SELECT COALESCE(MAX(id),0)+201 FROM flights").fetchone()[0]
        for day in days:
            inserted = db.execute("INSERT OR IGNORE INTO market_days(day) VALUES (?)", (day.date().isoformat(),))
            if not inserted.rowcount:
                continue
            for slot, (carrier, origin, destination, hour, minute, duration, price) in enumerate(DEMO_ROUTES):
                if carrier not in carriers:
                    carrier = "KC"
                inbound = destination == "Астана"
                departure = int(day.replace(hour=hour, minute=minute).timestamp()*1000)
                scheduled = departure + (duration*60000 if inbound else 0)
                city, base = (origin, destination) if inbound else (destination, origin)
                gate = f"{'A' if inbound else 'B'}{slot+2}"
                while True:
                    result = db.execute("""INSERT OR IGNORE INTO flights(number,city,base_city,gate,time,direction,
                                          status,airline_id,duration_min,base_price) VALUES (?,?,?,?,?,?,0,?,?,?)""",
                                        (f"{carrier} {number}", city, base, gate, scheduled, int(inbound),
                                         carriers[carrier], duration, price))
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
    result["origin_code"] = CITIES.get(row["origin"], ("—",))[0]
    result["destination_code"] = CITIES.get(row["destination"], ("—",))[0]
    result["checkin_opens"] = row["departure"] - row["checkin_open_min"]*60000
    result["checkin_closes"] = row["departure"] - row["checkin_close_min"]*60000
    other = row["destination"] if row["origin"] == "Астана" else row["origin"]
    country = CITIES.get(other)
    result["terminal"] = None
    if "Астана" in (row["origin"], row["destination"]) and country:
        result["terminal"] = "T2" if country[1] == "Қазақстан" else "T1"
    return result


def trip_rows(db, user):
    return db.execute(
        "SELECT * FROM passenger_trips WHERE passenger_id=? ORDER BY departure",
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
        trips = [dict(row) for row in db.execute("""SELECT code,flight_number,origin,city,departure,seat,completed
                     FROM travel_stamps WHERE user=? AND city=? ORDER BY departure DESC,id DESC""", (user, city))]
        for trip in trips:
            trip["local_time"] = datetime.fromtimestamp(trip["departure"] / 1000, ASTANA).strftime("%d.%m.%Y %H:%M")
        stamps.append({**dict(group), "code_city": info[0] if info else "—", "country": country, "trips": trips})
    return {"summary": {"trips": sum(item["count"] for item in stamps), "cities": len(stamps), "countries": len(countries)},
            "stamps": stamps}


def ticket_rows(db, user):
    who = user["id"]
    role = user["role"]
    where, params = "", [who, who]
    if role == "passenger":
        where = " WHERE t.owner=?"
        params.append(who)
    elif role == "operator":
        where = " WHERE t.airline_id=?"
        params.append(user["airline_id"])
    return db.execute(
        """SELECT t.*,u.name,a.name AS airline,
           (SELECT COUNT(*) FROM messages m WHERE m.ticket=t.id AND m.sender!=?
            AND m.id>COALESCE((SELECT last_message FROM reads r WHERE r.ticket=t.id AND r.user=?),0)) unread
           FROM tickets t JOIN users u ON u.id=t.owner JOIN airlines a ON a.id=t.airline_id"""
        + where + " ORDER BY t.updated DESC,t.id DESC", params,
    ).fetchall()


def checkin_queue(db, airline_id=None):
    now = now_ms()
    return db.execute(
        """SELECT b.code,b.seat,f.number,f.destination AS city,f.departure AS time,u.name FROM bookings b
           JOIN schedule f ON f.id=b.flight JOIN users u ON u.id=b.user
           WHERE b.cancelled=0 AND b.checked=0 AND f.status!=3
           AND f.departure-f.checkin_open_min*60000<=? AND f.departure-f.checkin_close_min*60000>?
           AND (? IS NULL OR f.airline_id=?) ORDER BY f.departure,u.name""", (now, now, airline_id, airline_id),
    ).fetchall()
