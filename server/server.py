import hashlib
import io
import ipaddress
import re
import secrets
import sqlite3
import time
from contextlib import asynccontextmanager
from datetime import date, datetime
from urllib.parse import urlsplit

import qrcode
import qrcode.image.svg
from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

import airport_data as data
from weather import get_forecast

SESSION = "airport_web_session"
SESSION_TTL = 12 * 3600
ACCOUNT_COLUMNS = "id,name,email,role,active,flight_alerts"
TUNNEL_HOST_FILE = data.ROOT / "runtime" / "tunnel-host.txt"


def configured_tunnel_host():
    """Accept only the one temporary Quick Tunnel hostname configured locally."""
    try:
        hostname = TUNNEL_HOST_FILE.read_text(encoding="ascii").strip().lower()
    except (OSError, UnicodeError):
        return None
    if re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.trycloudflare\.com", hostname):
        return hostname
    return None


@asynccontextmanager
async def lifespan(app):
    data.init_db()
    yield


app = FastAPI(title="Airport", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
app.mount("/static", StaticFiles(directory=data.ROOT / "static"), name="static")


def fail(detail, status=400):
    raise HTTPException(status, detail)


@app.middleware("http")
async def protect(request, call_next):
    hostname = request.url.hostname
    public_tunnel = False
    try:
        address = ipaddress.ip_address(hostname or "")
        allowed = address.is_loopback or (address.is_private and not address.is_unspecified)
    except ValueError:
        tunnel_host = configured_tunnel_host()
        public_tunnel = bool(tunnel_host and hostname == tunnel_host)
        allowed = hostname == "localhost" or public_tunnel
    if not allowed:
        return JSONResponse({"detail": "Бұл жергілікті сервер."}, status_code=400)
    request.state.public_tunnel = public_tunnel
    if request.method in {"POST", "PATCH", "PUT", "DELETE"}:
        origin = request.headers.get("origin")
        if origin and urlsplit(origin).netloc.lower() != request.url.netloc.lower():
            return JSONResponse({"detail": "Бұл сұрауға рұқсат жоқ."}, status_code=403)
        if request.headers.get("content-type", "").split(";")[0].lower() != "application/json":
            return JSONResponse({"detail": "JSON сұрауын жіберіңіз."}, status_code=415)
    result = await call_next(request)
    result.headers["X-Content-Type-Options"] = "nosniff"
    result.headers["Referrer-Policy"] = "no-referrer"
    result.headers["X-Frame-Options"] = "DENY"
    result.headers["Content-Security-Policy"] = "default-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
    if request.url.path.startswith("/api"):
        result.headers["Cache-Control"] = "no-store"
    return result


@app.get("/")
def index():
    return FileResponse(data.ROOT / "static" / "index.html", headers={"Cache-Control": "no-cache"})


@app.get("/health")
def health():
    return {"app": "Airport", "status": "ready"}


def token_hash(token):
    return hashlib.sha256(token.encode()).hexdigest()


def signed_in(request: Request, db=Depends(data.database)):
    token = request.cookies.get(SESSION, "")
    row = db.execute("""SELECT u.* FROM sessions s JOIN users u ON u.id=s.user
                        WHERE s.token_hash=? AND s.expires>? AND u.active=1""",
                     (token_hash(token), int(time.time()))).fetchone()
    if not row:
        fail("Аккаунтқа кіріңіз.", 401)
    return row


def allow(who, *roles):
    if who["role"] not in roles:
        fail("Бұл әрекетке рұқсат жоқ.", 403)


def public(who):
    result = {key: who[key] for key in ACCOUNT_COLUMNS.split(",")}
    result["demo"] = any(who["email"] == email and role == "passenger" for _, email, role in data.DEMOS)
    return result


class Credentials(BaseModel):
    email: str = Field(max_length=90)
    password: str = Field(max_length=80)


class NewAccount(Credentials):
    name: str = Field(max_length=60)


class ProfileEdit(BaseModel):
    name: str | None = Field(default=None, max_length=60)
    flight_alerts: bool | None = None


class PasswordEdit(BaseModel):
    current: str = Field(max_length=80)
    password: str = Field(min_length=8, max_length=80)


def make_account(db, entry, role):
    email = entry.email.strip().lower()
    if not entry.name.strip() or not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", email) or len(entry.password) < 8:
        fail("Аты, email және кемінде 8 таңбалы құпиясөз қажет.")
    if any(email == reserved for _, reserved, _ in data.DEMOS):
        fail("Бұл email жүйелік аккаунтқа тиесілі.", 409)
    if role != "passenger" and entry.password == data.DEMO_PASSWORD:
        fail("Қызметкер үшін жеке құпиясөз таңдаңыз.")
    try:
        return data.create_account(db, entry.name.strip(), email, entry.password, role)
    except sqlite3.IntegrityError:
        fail("Бұл email тіркелген.", 409)


def start_session(db, response, user_id, old_token=None, secure=False):
    token = secrets.token_urlsafe(32)
    now = int(time.time())
    db.execute("DELETE FROM sessions WHERE expires<=?", (now,))
    if old_token:
        db.execute("DELETE FROM sessions WHERE token_hash=?", (token_hash(old_token),))
    db.execute("INSERT INTO sessions(token_hash,user,expires) VALUES (?,?,?)",
               (token_hash(token), user_id, now+SESSION_TTL))
    response.set_cookie(SESSION, token, max_age=SESSION_TTL, httponly=True, samesite="strict", secure=secure)
    return public(db.execute("SELECT * FROM users WHERE id=?", (user_id,)).fetchone())


@app.post("/api/login")
def login(entry: Credentials, request: Request, response: Response, db=Depends(data.database)):
    with data.transaction(db):
        user = db.execute("SELECT * FROM users WHERE email=? AND active=1", (entry.email.strip().lower(),)).fetchone()
        if not user or not data.password_ok(entry.password, user):
            fail("Email, құпиясөз немесе аккаунт күйін тексеріңіз.", 401)
        return start_session(db, response, user["id"], request.cookies.get(SESSION), secure=request.state.public_tunnel)


@app.post("/api/register")
def register(entry: NewAccount, request: Request, response: Response, db=Depends(data.database)):
    with data.transaction(db):
        uid = make_account(db, entry, "passenger")
        return start_session(db, response, uid, request.cookies.get(SESSION), secure=request.state.public_tunnel)


@app.post("/api/logout")
def logout(request: Request, response: Response, db=Depends(data.database)):
    db.execute("DELETE FROM sessions WHERE token_hash=?", (token_hash(request.cookies.get(SESSION, "")),))
    response.delete_cookie(SESSION)
    return {"ok": True}


@app.get("/api/me")
def me(who=Depends(signed_in)):
    return public(who)


@app.patch("/api/me")
def edit_profile(entry: ProfileEdit, who=Depends(signed_in), db=Depends(data.database)):
    name = entry.name.strip() if entry.name is not None else None
    if "name" in entry.model_fields_set and not name:
        fail("Атыңызды енгізіңіз.")
    alerts = entry.flight_alerts if who["role"] == "passenger" else None
    if name is None and alerts is None:
        fail("Өзгертілетін профиль баптауын көрсетіңіз.")
    with data.transaction(db):
        db.execute("UPDATE users SET name=COALESCE(?,name),flight_alerts=COALESCE(?,flight_alerts) WHERE id=?",
                   (name, alerts, who["id"]))
        return public(db.execute("SELECT * FROM users WHERE id=?", (who["id"],)).fetchone())


@app.put("/api/me/password")
def password(entry: PasswordEdit, request: Request, who=Depends(signed_in), db=Depends(data.database)):
    if public(who)["demo"]:
        fail("Құпиясөз жеке аккаунтта ауыстырылады.")
    if who["role"] != "passenger" and entry.password == data.DEMO_PASSWORD:
        fail("Қызметкер үшін жеке құпиясөз таңдаңыз.")
    with data.transaction(db):
        current = db.execute("SELECT * FROM users WHERE id=?", (who["id"],)).fetchone()
        if not data.password_ok(entry.current, current):
            fail("Қазіргі құпиясөз қате.")
        salt, digest = data.new_password(entry.password)
        db.execute("UPDATE users SET salt=?,password=? WHERE id=?", (salt, digest, who["id"]))
        db.execute("DELETE FROM sessions WHERE user=? AND token_hash!=?",
                   (who["id"], token_hash(request.cookies.get(SESSION, ""))))
    return {"ok": True}


@app.get("/api/home")
def home(who=Depends(signed_in), db=Depends(data.database)):
    data.ensure_schedule(db)
    uid, role = who["id"], who["role"]
    tickets = data.ticket_rows(db, who)
    result = {"today": data.local_date(data.now_ms()), "unread": sum(t["unread"] for t in tickets),
              "notices": db.execute("SELECT COUNT(*) FROM notices WHERE user=? AND seen=0", (uid,)).fetchone()[0]}
    if role == "passenger":
        result["trips"] = [data.flight_json(r) for r in data.trip_rows(db, uid)]
        result["saved"] = db.execute("SELECT COUNT(*) FROM saved_flights WHERE user=?", (uid,)).fetchone()[0]
        result["passport"] = data.travel_passport(db, uid)["summary"]
    elif role == "operator":
        todays = [r for r in db.execute("SELECT time,status FROM flights WHERE direction=0") if data.local_date(r["time"]) == result["today"]]
        result.update(flights=len(todays), disrupted=sum(r["status"] in (1, 3) for r in todays), queue=len(data.checkin_queue(db)), tickets=len(tickets))
    else:
        result.update(active=db.execute("SELECT COUNT(*) FROM users WHERE active=1").fetchone()[0],
                      open_tickets=sum(t["state"] != 2 for t in tickets),
                      accounts=db.execute("SELECT COUNT(*) FROM users").fetchone()[0])
    return result


def get_flight(db, key):
    flight = db.execute("SELECT * FROM flights WHERE id=?", (key,)).fetchone()
    if not flight:
        fail("Рейс табылмады.", 404)
    return flight


@app.get("/api/flights")
def flights(direction: int = Query(0, ge=0, le=1), q: str = Query("", max_length=80), day: date | None = None,
            saved: bool = False, disrupted: bool = False, who=Depends(signed_in), db=Depends(data.database)):
    data.ensure_schedule(db)
    uid = who["id"]
    choices = {r[0] for r in db.execute("SELECT flight FROM saved_flights WHERE user=?", (uid,))}
    needle = q.replace(" ", "").casefold()
    now = data.now_ms()
    results = []
    for row in db.execute("SELECT * FROM flights WHERE direction=? ORDER BY time", (direction,)):
        flight = data.flight_json(row)
        if needle and not any(needle in flight[key].replace(" ", "").casefold() for key in ("number", "city", "code_city")):
            continue
        if day and flight["date"] != day.isoformat():
            continue
        if saved and flight["id"] not in choices:
            continue
        if disrupted and flight["status"] not in (1, 3):
            continue
        if not day and not saved and who["role"] == "passenger" and flight["time"] < now:
            continue
        flight["saved"] = flight["id"] in choices
        results.append(flight)
    if not day and who["role"] != "passenger":
        results.sort(key=lambda f: (f["time"] < now, -f["time"] if f["time"] < now else f["time"]))
    return results


@app.get("/api/flights/{key}")
def flight_detail(key: int, who=Depends(signed_in), db=Depends(data.database)):
    flight = data.flight_json(get_flight(db, key))
    if who["role"] == "passenger":
        flight["saved"] = bool(db.execute("SELECT 1 FROM saved_flights WHERE user=? AND flight=?", (who["id"], key)).fetchone())
        own = db.execute("SELECT code FROM bookings WHERE user=? AND flight=?", (who["id"], key)).fetchone()
        flight["own_booking"] = own["code"] if own else None
    return flight


class Flag(BaseModel):
    enabled: bool


@app.put("/api/flights/{key}/saved")
def save_flight(key: int, entry: Flag, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger")
    get_flight(db, key)
    if entry.enabled:
        db.execute("INSERT OR IGNORE INTO saved_flights(user,flight) VALUES (?,?)", (who["id"], key))
    else:
        db.execute("DELETE FROM saved_flights WHERE user=? AND flight=?", (who["id"], key))
    return {"ok": True}


class FlightEdit(BaseModel):
    number: str = Field(min_length=1, max_length=24)
    city: str = Field(min_length=1, max_length=70)
    gate: str = Field(min_length=1, max_length=12)
    time: int = Field(ge=1_000_000_000_000, le=4_102_444_800_000)
    direction: int = Field(ge=0, le=1)
    status: int = Field(ge=0, le=3)


@app.post("/api/flights")
def create_flight(entry: FlightEdit, who=Depends(signed_in), db=Depends(data.database)):
    return write_flight(entry, None, who, db)


@app.put("/api/flights/{key}")
def edit_flight(entry: FlightEdit, key: int, who=Depends(signed_in), db=Depends(data.database)):
    return write_flight(entry, key, who, db)


def write_flight(entry, key, who, db):
    allow(who, "operator")
    number, city, gate = entry.number.strip().upper(), entry.city.strip(), entry.gate.strip().upper()
    if not number or not city or not gate:
        fail("Рейс нөмірін, қаланы және қақпаны енгізіңіз.")
    args = (number, city, gate, entry.time, entry.direction, entry.status)
    try:
        with data.transaction(db):
            if key is None:
                key = db.execute("INSERT INTO flights(number,city,gate,time,direction,status) VALUES (?,?,?,?,?,?)", args).lastrowid
                data.audit(db, who["id"], "Рейс қосылды", f"{number} · {city}")
            else:
                old = get_flight(db, key)
                db.execute("UPDATE flights SET number=?,city=?,gate=?,time=?,direction=?,status=? WHERE id=?", (*args, key))
                updates = []
                if old["gate"] != gate:
                    updates.append(f"Қақпа {old['gate']} → {gate}")
                if old["time"] != entry.time:
                    updates.append(f"Уақыт {data.when(old['time'])} → {data.when(entry.time)}")
                if old["status"] != entry.status:
                    updates.append("Мәртебе · " + data.FLIGHT_STATES[entry.status])
                if old["city"] != city or old["direction"] != entry.direction:
                    updates.append("Бағыт · " + (f"Астана → {city}" if entry.direction == 0 else f"{city} → Астана"))
                if old["number"] != number:
                    updates.append(f"Рейс {old['number']} → {number}")
                if updates:
                    title, body = f"{number} · Рейс жаңарды", "\n".join(updates)
                    data.audit(db, who["id"], title, body)
                    db.execute("""INSERT INTO notices(user,flight,title,body,created)
                        SELECT b.user,b.flight,?,?,? FROM bookings b JOIN users u ON u.id=b.user
                        WHERE b.flight=? AND u.active=1 AND u.flight_alerts=1""",
                        (title, body, data.now_ms(), key))
    except sqlite3.IntegrityError:
        fail("Бұл рейс нөмірі қолданылып тұр.", 409)
    return data.flight_json(get_flight(db, key))


def trip(db, who, code):
    allow(who, "passenger")
    row = db.execute("""SELECT f.*,b.code,b.seat,b.checked,b.trip_tasks FROM bookings b
                       JOIN flights f ON f.id=b.flight WHERE b.user=? AND b.code=?""", (who["id"], code)).fetchone()
    if not row:
        fail("Бронь табылмады.", 404)
    return row


@app.get("/api/trips")
def trips(who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger")
    return [data.flight_json(row) for row in data.trip_rows(db, who["id"])]


@app.get("/api/passport")
def passport(who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger")
    return data.travel_passport(db, who["id"])


@app.get("/api/trips/{code}")
def get_trip(code: str, who=Depends(signed_in), db=Depends(data.database)):
    return data.flight_json(trip(db, who, code))


@app.get("/api/flights/{key}/seats")
def seats(key: int, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger")
    get_flight(db, key)
    return {"seats": data.SEATS, "occupied": [r[0] for r in db.execute("SELECT seat FROM bookings WHERE flight=? AND seat IS NOT NULL", (key,))]}


class Reservation(BaseModel):
    seat: str = Field(max_length=4)


@app.post("/api/flights/{key}/book")
def book(key: int, entry: Reservation, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger")
    if entry.seat not in data.SEATS:
        fail("Бос орынды таңдаңыз.")
    with data.transaction(db):
        f = get_flight(db, key)
        uid = who["id"]
        if f["direction"] != 0 or f["status"] == 3 or f["time"] <= data.now_ms():
            fail("Бұл рейске бронь жасау қолжетімсіз.", 409)
        old = db.execute("SELECT code FROM bookings WHERE user=? AND flight=?", (uid, key)).fetchone()
        if old:
            code = old["code"]
        else:
            if db.execute("SELECT 1 FROM bookings WHERE flight=? AND seat=?", (key, entry.seat)).fetchone():
                fail("Бұл орын бос емес. Басқа орынды таңдаңыз.", 409)
            code = "NQZ-" + secrets.token_hex(4).upper()
            while db.execute("SELECT 1 FROM bookings WHERE code=?", (code,)).fetchone():
                code = "NQZ-" + secrets.token_hex(4).upper()
            db.execute("INSERT INTO bookings(user,flight,code,seat) VALUES (?,?,?,?)", (uid, key, code, entry.seat))
        return data.flight_json(trip(db, who, code))


@app.delete("/api/trips/{code}")
def cancel(code: str, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger")
    if not db.execute("DELETE FROM bookings WHERE user=? AND code=? AND checked=0", (who["id"], code)).rowcount:
        fail("Бұл броньды қазір жою мүмкін емес.", 409)
    return {"ok": True}


class Checklist(BaseModel):
    mask: int = Field(ge=0, le=15)


@app.put("/api/trips/{code}/checklist")
def checklist(code: str, entry: Checklist, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger")
    if not db.execute("UPDATE bookings SET trip_tasks=? WHERE user=? AND code=?", (entry.mask, who["id"], code)).rowcount:
        fail("Бронь табылмады.", 404)
    return {"ok": True}


@app.get("/api/trips/{code}/qr.svg")
def boarding_qr(code: str, who=Depends(signed_in), db=Depends(data.database)):
    row = trip(db, who, code)
    image = qrcode.make(row["code"], image_factory=qrcode.image.svg.SvgPathFillImage)
    output = io.BytesIO()
    image.save(output)
    return Response(output.getvalue(), media_type="image/svg+xml")


@app.get("/api/notices")
def notices(who=Depends(signed_in), db=Depends(data.database)):
    return [dict(row) for row in db.execute("""SELECT n.*,b.code FROM notices n
        LEFT JOIN bookings b ON b.flight=n.flight AND b.user=n.user
        WHERE n.user=? ORDER BY n.id DESC""", (who["id"],))]


@app.post("/api/notices/read")
def read_all(who=Depends(signed_in), db=Depends(data.database)):
    db.execute("UPDATE notices SET seen=1 WHERE user=?", (who["id"],))
    return {"ok": True}


@app.post("/api/notices/{key}/read")
def read_notice(key: int, who=Depends(signed_in), db=Depends(data.database)):
    db.execute("UPDATE notices SET seen=1 WHERE user=? AND id=?", (who["id"], key))
    return {"ok": True}


@app.delete("/api/notices/{key}")
def delete_notice(key: int, who=Depends(signed_in), db=Depends(data.database)):
    db.execute("DELETE FROM notices WHERE user=? AND id=?", (who["id"], key))
    return {"ok": True}


def get_ticket(db, who, key):
    row = db.execute("SELECT * FROM tickets WHERE id=?", (key,)).fetchone()
    if not row or (who["role"] == "passenger" and row["owner"] != who["id"]):
        fail("Өтінім табылмады.", 404)
    return row


@app.get("/api/tickets")
def tickets(who=Depends(signed_in), db=Depends(data.database)):
    return [dict(r) for r in data.ticket_rows(db, who)]


class Message(BaseModel):
    body: str = Field(max_length=2000)


class TicketCreate(Message):
    subject: str = Field(max_length=80)


def message_body(entry):
    if not entry.body.strip():
        fail("Хабар мәтінін енгізіңіз.")
    return entry.body.strip()


@app.post("/api/tickets")
def create_ticket(entry: TicketCreate, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger")
    if not entry.subject.strip():
        fail("Өтінім тақырыбын таңдаңыз.")
    body = message_body(entry)
    with data.transaction(db):
        now, uid = data.now_ms(), who["id"]
        key = db.execute("INSERT INTO tickets(owner,subject,updated) VALUES (?,?,?)", (uid, entry.subject.strip(), now)).lastrowid
        db.execute("INSERT INTO messages(ticket,sender,body,sent) VALUES (?,?,?,?)", (key, uid, body, now))
    return {"id": key}


@app.get("/api/tickets/{key}")
def get_messages(key: int, who=Depends(signed_in), db=Depends(data.database)):
    ticket = dict(get_ticket(db, who, key))
    ticket["messages"] = [dict(r) for r in db.execute("""SELECT m.id,m.sender,m.body,m.sent,u.name,u.role FROM messages m
         JOIN users u ON u.id=m.sender WHERE m.ticket=? ORDER BY m.id""", (key,))]
    return ticket


class TicketRead(BaseModel):
    last: int = Field(ge=0)


@app.post("/api/tickets/{key}/read")
def mark_ticket(key: int, entry: TicketRead, who=Depends(signed_in), db=Depends(data.database)):
    get_ticket(db, who, key)
    latest = db.execute("SELECT COALESCE(MAX(id),0) FROM messages WHERE ticket=? AND id<=?", (key, entry.last)).fetchone()[0]
    db.execute("""INSERT INTO reads(user,ticket,last_message) VALUES (?,?,?)
        ON CONFLICT(user,ticket) DO UPDATE SET last_message=MAX(reads.last_message,excluded.last_message)""",
        (who["id"], key, latest))
    return {"ok": True}


@app.post("/api/tickets/{key}/messages")
def post_message(key: int, entry: Message, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger", "operator")
    body = message_body(entry)
    with data.transaction(db):
        ticket = get_ticket(db, who, key)
        if ticket["state"] == 2:
            fail("Өтінім шешілді. Жаңа өтінім ашыңыз.", 409)
        now = data.now_ms()
        db.execute("INSERT INTO messages(ticket,sender,body,sent) VALUES (?,?,?,?)", (key, who["id"], body, now))
        next_state = 1 if ticket["state"] == 0 and who["role"] == "operator" else ticket["state"]
        db.execute("UPDATE tickets SET updated=?,state=? WHERE id=?", (now, next_state, key))
    return {"ok": True}


class TicketState(BaseModel):
    state: int = Field(ge=0, le=2)


@app.put("/api/tickets/{key}/state")
def update_ticket(key: int, entry: TicketState, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "operator")
    get_ticket(db, who, key)
    db.execute("UPDATE tickets SET state=?,updated=? WHERE id=?", (entry.state, data.now_ms(), key))
    return {"ok": True}


@app.get("/api/staff/queue")
def queue(who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "operator")
    return [dict(r) for r in data.checkin_queue(db)]


@app.get("/api/flights/{key}/manifest")
def manifest(key: int, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "operator")
    flight = get_flight(db, key)
    rows = [dict(r) for r in db.execute("""SELECT b.code,b.seat,b.checked,u.name,
           EXISTS(SELECT 1 FROM travel_stamps s WHERE s.code=b.code) completed FROM bookings b
           JOIN users u ON u.id=b.user WHERE b.flight=? ORDER BY b.seat,b.id""", (key,))]
    eligible = flight["direction"] == 0 and flight["status"] != 3 and flight["time"] <= data.now_ms()
    for row in rows:
        row["can_complete"] = bool(eligible and row["checked"] and not row["completed"])
    return rows


class CheckIn(BaseModel):
    code: str = Field(max_length=32)


@app.post("/api/staff/complete")
def complete_trip(entry: CheckIn, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "operator")
    code = entry.code.strip().upper()
    with data.transaction(db):
        if db.execute("SELECT 1 FROM travel_stamps WHERE code=?", (code,)).fetchone():
            return {"ok": True}
        row = db.execute("""SELECT b.user,b.code,b.seat,f.number,f.city,f.time FROM bookings b
                  JOIN flights f ON f.id=b.flight WHERE b.code=? AND b.checked=1
                  AND f.direction=0 AND f.status!=3 AND f.time<=?""", (code, data.now_ms())).fetchone()
        if not row:
            fail("Тіркелген жолаушының аяқталған ұшуын ғана растауға болады.", 409)
        db.execute("""INSERT OR IGNORE INTO travel_stamps(user,code,flight_number,city,departure,seat,completed)
                      VALUES (?,?,?,?,?,?,?)""",
                   (row["user"], code, row["number"], row["city"], row["time"], row["seat"], data.now_ms()))
        data.audit(db, who["id"], "Сапар аяқталды", f"{row['number']} · {row['city']} · {code}")
    return {"ok": True}


@app.post("/api/staff/checkin")
def check_in(entry: CheckIn, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "operator")
    key = entry.code.strip().upper()
    with data.transaction(db):
        row = db.execute("""SELECT f.number FROM flights f JOIN bookings b ON b.flight=f.id
                  WHERE b.code=? AND b.checked=0 AND f.direction=0 AND f.status!=3 AND f.time>?""",
                  (key, data.now_ms())).fetchone()
        if not row:
            fail("Бронь коды жарамсыз немесе жолаушы тіркелген.", 409)
        db.execute("UPDATE bookings SET checked=1 WHERE code=?", (key,))
        data.audit(db, who["id"], "Жолаушы тіркелді", row["number"])
    return {"ok": True}


@app.get("/api/staff/log")
def log(who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "admin", "operator")
    where = "" if who["role"] == "admin" else " WHERE l.actor=?"
    params = () if who["role"] == "admin" else (who["id"],)
    return [dict(r) for r in db.execute("SELECT u.name,l.* FROM ops_log l JOIN users u ON u.id=l.actor" +
            where + " ORDER BY l.id DESC LIMIT 60", params)]


@app.get("/api/admin/accounts")
def accounts(who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "admin")
    return [public(r) for r in db.execute("SELECT " + ACCOUNT_COLUMNS + " FROM users ORDER BY id")]


@app.post("/api/admin/operators")
def create_operator(entry: NewAccount, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "admin")
    with data.transaction(db):
        uid = make_account(db, entry, "operator")
        data.audit(db, who["id"], "Оператор қосылды", f"{entry.name.strip()} · #{uid}")
    return {"id": uid}


@app.put("/api/admin/accounts/{key}/active")
def activate(key: int, entry: Flag, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "admin")
    with data.transaction(db):
        target = db.execute("SELECT id,role,active FROM users WHERE id=?", (key,)).fetchone()
        if not target or target["role"] == "admin":
            fail("Бұл аккаунтты өзгертуге рұқсат жоқ.", 403)
        if bool(target["active"]) != entry.enabled:
            db.execute("UPDATE users SET active=? WHERE id=?", (int(entry.enabled), key))
            if not entry.enabled:
                db.execute("DELETE FROM sessions WHERE user=?", (key,))
            data.audit(db, who["id"], "Аккаунт қайта ашылды" if entry.enabled else "Аккаунт бұғатталды", f"Аккаунт #{key}")
    return {"ok": True}


class ReviewCreate(BaseModel):
    rating: int = Field(strict=True, ge=1, le=5)
    body: str = Field(min_length=1, max_length=2000)


@app.post("/api/reviews")
def create_review(entry: ReviewCreate, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger")
    body = entry.body.strip()
    if not body:
        fail("Пікіріңізді жазыңыз.")
    key = db.execute("INSERT INTO reviews(user,rating,body,created) VALUES (?,?,?,?)",
                     (who["id"], entry.rating, body, data.now_ms())).lastrowid
    return {"id": key}


@app.get("/api/reviews")
def reviews(who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger", "admin")
    where = " WHERE r.user=?" if who["role"] == "passenger" else ""
    params = (who["id"],) if who["role"] == "passenger" else ()
    return [dict(row) for row in db.execute(
        "SELECT r.*,u.name,u.email FROM reviews r JOIN users u ON u.id=r.user" + where + " ORDER BY r.id DESC",
        params)]


@app.get("/api/weather/cities")
def cities(who=Depends(signed_in), db=Depends(data.database)):
    return list(dict.fromkeys([*data.CITIES, *(r[0] for r in db.execute("SELECT DISTINCT city FROM flights ORDER BY city"))]))


@app.get("/api/weather")
def weather(city: str = Query(min_length=1, max_length=70), who=Depends(signed_in), db=Depends(data.database)):
    label = city.strip()
    if label not in data.CITIES and not db.execute("SELECT 1 FROM flights WHERE city=?", (label,)).fetchone():
        fail("Бұл қала рейстер тізімінде жоқ.", 404)
    return get_forecast(label)
