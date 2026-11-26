import hashlib
import io
import ipaddress
import re
import secrets
import sqlite3
import time
from contextlib import asynccontextmanager
from datetime import date
from typing import Literal
from urllib.parse import urlsplit

import qrcode
import qrcode.image.svg
from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

import airport_data as data
import sales
from live_flights import live_board
from weather import get_forecast

SESSION = "airport_web_session"
SESSION_TTL = 12 * 3600
ACCOUNT_COLUMNS = "id,name,email,role,active,flight_alerts,airline_id"
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


def make_account(db, entry, role, airline_id=None):
    email = entry.email.strip().lower()
    if not entry.name.strip() or not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", email) or len(entry.password) < 8:
        fail("Аты, email және кемінде 8 таңбалы құпиясөз қажет.")
    if any(email == reserved for _, reserved, _ in data.DEMOS):
        fail("Бұл email жүйелік аккаунтқа тиесілі.", 409)
    if role != "passenger" and entry.password == data.DEMO_PASSWORD:
        fail("Қызметкер үшін жеке құпиясөз таңдаңыз.")
    try:
        return data.create_account(db, entry.name.strip(), email, entry.password, role, airline_id)
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
        result["loyalty"] = sales.loyalty(db, uid)
        result["balance"] = sales.balance(db, uid)
        result["reviewable"] = db.execute("""SELECT COUNT(*) FROM travel_stamps t WHERE user=? AND NOT EXISTS
            (SELECT 1 FROM reviews r WHERE r.trip_code=t.code)""", (uid,)).fetchone()[0]
    elif role == "operator":
        airline = who["airline_id"]
        todays = [r for r in db.execute("SELECT departure,status FROM schedule WHERE airline_id=?", (airline,))
                  if data.local_date(r["departure"]) == result["today"]]
        result.update(flights=len(todays), disrupted=sum(r["status"] in (1, 3) for r in todays),
                      airline=db.execute("SELECT name FROM airlines WHERE id=?", (airline,)).fetchone()[0],
                      queue=len(data.checkin_queue(db, airline)), tickets=len(tickets))
    else:
        result.update(active=db.execute("SELECT COUNT(*) FROM users WHERE active=1").fetchone()[0],
                      open_tickets=sum(t["state"] != 2 for t in tickets),
                      accounts=db.execute("SELECT COUNT(*) FROM users").fetchone()[0])
    return result


def get_flight(db, key):
    flight = db.execute("SELECT * FROM schedule WHERE id=?", (key,)).fetchone()
    if not flight:
        fail("Рейс табылмады.", 404)
    return flight


def airline_access(who, airline_id):
    if who["role"] == "operator" and airline_id != who["airline_id"]:
        fail("Бұл рейс басқа әуе компаниясына тиесілі.", 403)


def require_airline(db, key):
    if not db.execute("SELECT 1 FROM airlines WHERE id=?", (key,)).fetchone():
        fail("Әуе компаниясын таңдаңыз.")


@app.get("/api/catalog")
def catalog(who=Depends(signed_in), db=Depends(data.database)):
    data.ensure_schedule(db)
    airlines = [dict(r) for r in db.execute("SELECT * FROM airlines ORDER BY name")
                if who["role"] != "operator" or r["id"] == who["airline_id"]]
    cities = set(data.CITIES)
    cities.update(r[0] for r in db.execute("SELECT origin FROM schedule UNION SELECT destination FROM schedule"))
    return {"airlines": airlines, "cities": [{"name": c, "code": data.CITIES.get(c, ("",))[0]} for c in sorted(cities)],
            "fares": [dict(r) for r in db.execute("SELECT * FROM fares ORDER BY markup_pct,id")]}


@app.get("/api/offers")
def search_offers(origin: str = Query(min_length=1, max_length=70), destination: str = Query(min_length=1, max_length=70),
                  day: date = Query(), direct: bool = False, sort: Literal["price", "duration", "departure"] = "price",
                  who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger")
    if origin == destination:
        fail("Екі түрлі қаланы таңдаңыз.")
    data.ensure_schedule(db)
    return sales.offers(db, who["id"], origin, destination, day, direct, sort)


@app.get("/api/quote")
def quote(flights: str = Query(pattern=r"^[1-9][0-9]{0,9}(,[1-9][0-9]{0,9})?$"), who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger")
    return sales.quote(db, who["id"], [int(key) for key in flights.split(",")])


@app.post("/api/checkout")
def checkout(entry: sales.Purchase, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger")
    return sales.checkout(db, who["id"], entry)


@app.get("/api/live")
def live(mode: Literal["registration", "flight"] = "registration", q: str = Query("", max_length=16),
         airport: Literal["NQZ", "ALA", "IST", "LHR"] = "NQZ", who=Depends(signed_in)):
    return live_board(mode, q, airport)


@app.get("/api/flights")
def flights(direction: int = Query(0, ge=0, le=1), q: str = Query("", max_length=80), day: date | None = None,
            saved: bool = False, disrupted: bool = False, who=Depends(signed_in), db=Depends(data.database)):
    data.ensure_schedule(db)
    uid = who["id"]
    choices = {r[0] for r in db.execute("SELECT flight FROM saved_flights WHERE user=?", (uid,))}
    needle = q.replace(" ", "").casefold()
    now = data.now_ms()
    results = []
    for row in db.execute("SELECT * FROM schedule WHERE direction=? ORDER BY time", (direction,)):
        if who["role"] == "operator" and row["airline_id"] != who["airline_id"]:
            continue
        flight = data.flight_json(row)
        if needle and not any(needle in flight[key].replace(" ", "").casefold() for key in
                              ("number", "origin", "destination", "origin_code", "destination_code", "airline")):
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
    airline_access(who, flight["airline_id"])
    if who["role"] == "passenger":
        flight["saved"] = bool(db.execute("SELECT 1 FROM saved_flights WHERE user=? AND flight=?", (who["id"], key)).fetchone())
        own = db.execute("SELECT code FROM bookings WHERE user=? AND flight=? AND cancelled=0", (who["id"], key)).fetchone()
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
    base_city: str = Field(default="Астана", min_length=1, max_length=70)
    airline_id: int | None = Field(default=None, gt=0)
    duration_min: int = Field(default=120, ge=10, le=1200)
    checkin_open_min: int = Field(default=180, ge=1, le=2880)
    checkin_close_min: int = Field(default=40, ge=0, le=1440)
    base_price: int = Field(default=25000, ge=1, le=5_000_000)


@app.post("/api/flights")
def create_flight(entry: FlightEdit, who=Depends(signed_in), db=Depends(data.database)):
    return write_flight(entry, None, who, db)


@app.put("/api/flights/{key}")
def edit_flight(entry: FlightEdit, key: int, who=Depends(signed_in), db=Depends(data.database)):
    return write_flight(entry, key, who, db)


def write_flight(entry, key, who, db):
    allow(who, "operator", "admin")
    number, city, gate = entry.number.strip().upper(), entry.city.strip(), entry.gate.strip().upper()
    try:
        with data.transaction(db):
            old = get_flight(db, key) if key is not None else None
            if old:
                airline_access(who, old["airline_id"])
                extended = ("base_city", "base_price", "duration_min", "checkin_open_min", "checkin_close_min")
                entry = entry.model_copy(update={k: old[k] for k in extended if k not in entry.model_fields_set})
            base_city = entry.base_city.strip()
            if not number or not city or not gate or not base_city or base_city.casefold() == city.casefold():
                fail("Рейс нөмірін, қақпаны және екі түрлі қаланы енгізіңіз.")
            if entry.checkin_open_min <= entry.checkin_close_min:
                fail("Тіркеу ашылғаннан кейін жабылуы керек.")
            airline = entry.airline_id or (old["airline_id"] if old else who["airline_id"])
            require_airline(db, airline)
            airline_access(who, airline)
            args = (number, city, gate, entry.time, entry.direction, entry.status, base_city, airline,
                    entry.duration_min, entry.checkin_open_min, entry.checkin_close_min, entry.base_price)
            if key is None:
                key = db.execute("""INSERT INTO flights(number,city,gate,time,direction,status,base_city,airline_id,
                    duration_min,checkin_open_min,checkin_close_min,base_price) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""", args).lastrowid
                data.audit(db, who["id"], "Рейс қосылды", f"{number} · {city}")
            else:
                rerouted = (old["city"], old["base_city"], old["direction"], old["airline_id"]) != (city, base_city, entry.direction, airline)
                if rerouted and db.execute("SELECT 1 FROM bookings WHERE flight=?", (key,)).fetchone():
                    fail("Билеті бар рейстің бағыты мен әуе компаниясын өзгертуге болмайды.", 409)
                db.execute("""UPDATE flights SET number=?,city=?,gate=?,time=?,direction=?,status=?,base_city=?,airline_id=?,
                           duration_min=?,checkin_open_min=?,checkin_close_min=?,base_price=? WHERE id=?""", (*args, key))
                updates = []
                if old["gate"] != gate:
                    updates.append(f"Қақпа {old['gate']} → {gate}")
                if old["time"] != entry.time:
                    updates.append(f"Уақыт {data.when(old['time'])} → {data.when(entry.time)}")
                if old["status"] != entry.status:
                    updates.append("Мәртебе · " + data.FLIGHT_STATES[entry.status])
                if (old["city"], old["base_city"], old["direction"]) != (city, base_city, entry.direction):
                    updates.append("Бағыт · " + (f"{base_city} → {city}" if entry.direction == 0 else f"{city} → {base_city}"))
                if (old["duration_min"], old["checkin_open_min"], old["checkin_close_min"], old["time"]) != (
                        entry.duration_min, entry.checkin_open_min, entry.checkin_close_min, entry.time):
                    revised = data.flight_json(get_flight(db, key))
                    updates.append(f"Ұшу {data.when(revised['departure'])} · Қону {data.when(revised['arrival'])}")
                    updates.append(f"Тіркеу {data.when(revised['checkin_opens'])} – {data.when(revised['checkin_closes'])}")
                if old["number"] != number:
                    updates.append(f"Рейс {old['number']} → {number}")
                if updates:
                    title, body = f"{number} · Рейс жаңарды", "\n".join(updates)
                    data.audit(db, who["id"], title, body)
                    db.execute("""INSERT INTO notices(user,flight,title,body,created)
                        SELECT b.user,b.flight,?,?,? FROM bookings b JOIN users u ON u.id=b.user
                        WHERE b.flight=? AND b.cancelled=0 AND u.active=1 AND u.flight_alerts=1""",
                        (title, body, data.now_ms(), key))
    except sqlite3.IntegrityError:
        fail("Бұл рейс нөмірі қолданылып тұр.", 409)
    return data.flight_json(get_flight(db, key))


def trip(db, who, code):
    allow(who, "passenger")
    row = db.execute("SELECT * FROM passenger_trips WHERE passenger_id=? AND code=?", (who["id"], code)).fetchone()
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
    selected = trip(db, who, code)
    legs = sales.order_legs(db, who["id"], code)
    return {**data.flight_json(selected), "legs": [data.flight_json(leg) for leg in legs],
            "refund": sales.refund_terms(legs)}


@app.get("/api/flights/{key}/seats")
def seats(key: int, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger")
    get_flight(db, key)
    return {"seats": data.SEATS, "occupied": sales.occupied(db, key)}


@app.post("/api/flights/{key}/book")
def book(key: int, who=Depends(signed_in)):
    allow(who, "passenger")
    fail("Бетті жаңартып, тарифті таңдаңыз және төлемді растаңыз.", 409)


@app.delete("/api/trips/{code}")
def cancel(code: str, expected: int | None = Query(None, ge=0), who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger")
    return sales.refund(db, who["id"], code, expected)


class Checklist(BaseModel):
    mask: int = Field(ge=0, le=15)


@app.put("/api/trips/{code}/checklist")
def checklist(code: str, entry: Checklist, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger")
    if not db.execute("UPDATE bookings SET trip_tasks=? WHERE user=? AND code=? AND cancelled=0", (entry.mask, who["id"], code)).rowcount:
        fail("Бронь табылмады.", 404)
    return {"ok": True}


@app.get("/api/trips/{code}/qr.svg")
def boarding_qr(code: str, who=Depends(signed_in), db=Depends(data.database)):
    row = trip(db, who, code)
    if row["cancelled"] or row["status"] == 3:
        fail("Қайтарылған немесе тоқтатылған билетке талон берілмейді.", 409)
    image = qrcode.make(row["code"], image_factory=qrcode.image.svg.SvgPathFillImage)
    output = io.BytesIO()
    image.save(output)
    return Response(output.getvalue(), media_type="image/svg+xml")


@app.get("/api/notices")
def notices(who=Depends(signed_in), db=Depends(data.database)):
    return [dict(row) for row in db.execute("""SELECT n.*,b.code FROM notices n
        LEFT JOIN bookings b ON b.flight=n.flight AND b.user=n.user AND b.cancelled=0
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
    if (not row or (who["role"] == "passenger" and row["owner"] != who["id"])
            or (who["role"] == "operator" and row["airline_id"] != who["airline_id"])):
        fail("Өтінім табылмады.", 404)
    return row


@app.get("/api/tickets")
def tickets(who=Depends(signed_in), db=Depends(data.database)):
    return [dict(r) for r in data.ticket_rows(db, who)]


class Message(BaseModel):
    body: str = Field(max_length=2000)


class TicketCreate(Message):
    subject: str = Field(max_length=80)
    airline_id: int = Field(default=1, gt=0)


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
        require_airline(db, entry.airline_id)
        key = db.execute("INSERT INTO tickets(owner,subject,updated,airline_id) VALUES (?,?,?,?)",
                         (uid, entry.subject.strip(), now, entry.airline_id)).lastrowid
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
    allow(who, "operator", "admin")
    return [dict(r) for r in data.checkin_queue(db, who["airline_id"])]


@app.get("/api/flights/{key}/manifest")
def manifest(key: int, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "operator", "admin")
    flight = get_flight(db, key)
    airline_access(who, flight["airline_id"])
    rows = [dict(r) for r in db.execute("""SELECT b.code,b.seat,b.checked,u.name,
           EXISTS(SELECT 1 FROM travel_stamps s WHERE s.code=b.code) completed FROM bookings b
           JOIN users u ON u.id=b.user WHERE b.flight=? AND b.cancelled=0 ORDER BY b.seat,b.id""", (key,))]
    eligible = flight["status"] != 3 and flight["arrival"] <= data.now_ms()
    schedule = data.flight_json(flight)
    checkin_open = flight["status"] != 3 and schedule["checkin_opens"] <= data.now_ms() < schedule["checkin_closes"]
    for row in rows:
        row["can_complete"] = bool(eligible and row["checked"] and not row["completed"])
        row["can_checkin"] = bool(checkin_open and not row["checked"])
    return rows


class CheckIn(BaseModel):
    code: str = Field(max_length=32)


def staff_booking(db, who, code):
    row = db.execute("SELECT * FROM passenger_trips WHERE code=? AND cancelled=0", (code,)).fetchone()
    if not row:
        fail("Белсенді бронь табылмады.", 404)
    airline_access(who, row["airline_id"])
    return row


@app.post("/api/staff/complete")
def complete_trip(entry: CheckIn, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "operator", "admin")
    code = entry.code.strip().upper()
    with data.transaction(db):
        row = staff_booking(db, who, code)
        if row["completed"]:
            return {"ok": True}
        if not row["checked"] or row["status"] == 3 or row["arrival"] > data.now_ms():
            fail("Тіркелген жолаушының аяқталған ұшуын ғана растауға болады.", 409)
        db.execute("""INSERT INTO travel_stamps(user,code,flight_number,origin,city,departure,seat,completed)
                      VALUES (?,?,?,?,?,?,?,?)""",
                   (row["passenger_id"], code, row["number"], row["origin"], row["destination"],
                    row["departure"], row["seat"], data.now_ms()))
        data.audit(db, who["id"], "Сапар аяқталды", f"{row['number']} · {row['city']} · {code}")
    return {"ok": True}


@app.post("/api/staff/checkin")
def check_in(entry: CheckIn, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "operator", "admin")
    key = entry.code.strip().upper()
    with data.transaction(db):
        row = data.flight_json(staff_booking(db, who, key))
        if row["checked"]:
            return {"ok": True}
        if row["status"] == 3 or not row["checkin_opens"] <= data.now_ms() < row["checkin_closes"]:
            fail("Осы рейске тіркеу қазір жабық.", 409)
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
    return [{**public(r), "airline": r["airline"]} for r in db.execute("""
        SELECT u.*, a.name AS airline FROM users u LEFT JOIN airlines a ON a.id=u.airline_id ORDER BY u.id""")]


class OperatorCreate(NewAccount):
    airline_id: int = Field(gt=0)


class AirlineCreate(BaseModel):
    name: str = Field(min_length=1, max_length=60)
    code: str = Field(pattern=r"^[A-Za-z0-9]{2,3}$")
    operator: NewAccount


class FareCreate(BaseModel):
    name: str = Field(min_length=1, max_length=50)
    baggage: str = Field(min_length=1, max_length=100)
    markup_pct: int = Field(ge=0, le=500)
    refund_pct: int = Field(ge=0, le=100)


@app.get("/api/admin/sales")
def admin_sales(before: int | None = Query(None, gt=0), who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "admin")
    companies = [dict(r) for r in db.execute("""SELECT a.*,
        (SELECT COUNT(*) FROM users u WHERE u.airline_id=a.id AND u.active=1 AND u.role='operator') AS operators,
        (SELECT COUNT(*) FROM flights f WHERE f.airline_id=a.id) AS flights,
        (SELECT COUNT(*) FROM bookings b JOIN flights f ON f.id=b.flight
         WHERE f.airline_id=a.id AND b.cancelled=0) AS tickets FROM airlines a ORDER BY a.name""")]
    rows = db.execute("""SELECT o.id,o.total,o.refunded,o.cancelled_at,o.created,o.fare_name,u.name,b.code
        FROM orders o JOIN users u ON u.id=o.user JOIN bookings b ON b.order_id=o.id AND b.leg_index=0
        WHERE (? IS NULL OR o.id<?) ORDER BY o.id DESC LIMIT 26""", (before, before)).fetchall()
    payments = {r["id"]: {**dict(r), "legs": []} for r in rows[:25]}
    if payments:
        for leg in db.execute("""SELECT b.order_id,s.number,s.airline,s.origin,s.destination,b.seat,b.cancelled
            FROM bookings b JOIN schedule s ON s.id=b.flight WHERE b.order_id BETWEEN ? AND ?
            ORDER BY b.order_id,b.leg_index""", (min(payments), max(payments))):
            payments[leg["order_id"]]["legs"].append(dict(leg))
    return {"companies": companies, "payments": list(payments.values()),
            "next_before": rows[24]["id"] if len(rows) > 25 else None}


@app.post("/api/admin/airlines")
def add_airline(entry: AirlineCreate, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "admin")
    name = entry.name.strip()
    if not name:
        fail("Әуе компаниясының атауын енгізіңіз.")
    with data.transaction(db):
        try:
            airline = db.execute("INSERT INTO airlines(name,code) VALUES (?,?)", (name, entry.code.upper())).lastrowid
        except sqlite3.IntegrityError:
            fail("Бұл компания немесе код тіркелген.", 409)
        make_account(db, entry.operator, "operator", airline)
        data.audit(db, who["id"], "Әуе компаниясы қосылды", name)
    return {"id": airline}


@app.post("/api/admin/fares")
def add_fare(entry: FareCreate, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "admin")
    if not entry.name.strip() or not entry.baggage.strip():
        fail("Тариф атауы мен багаж шартын енгізіңіз.")
    with data.transaction(db):
        try:
            key = db.execute("INSERT INTO fares(name,baggage,markup_pct,refund_pct) VALUES (?,?,?,?)",
                (entry.name.strip(), entry.baggage.strip(), entry.markup_pct, entry.refund_pct)).lastrowid
        except sqlite3.IntegrityError:
            fail("Бұл тариф бұрын қосылған.", 409)
        data.audit(db, who["id"], "Тариф қосылды", entry.name.strip())
    return {"id": key}


@app.post("/api/admin/operators")
def create_operator(entry: OperatorCreate, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "admin")
    with data.transaction(db):
        require_airline(db, entry.airline_id)
        uid = make_account(db, entry, "operator", entry.airline_id)
        data.audit(db, who["id"], "Оператор қосылды", f"{entry.name.strip()} · #{uid}")
    return {"id": uid}


@app.put("/api/admin/accounts/{key}/active")
def activate(key: int, entry: Flag, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "admin")
    with data.transaction(db):
        target = db.execute("SELECT id,role,active,airline_id FROM users WHERE id=?", (key,)).fetchone()
        if not target or target["role"] == "admin":
            fail("Бұл аккаунтты өзгертуге рұқсат жоқ.", 403)
        if bool(target["active"]) != entry.enabled:
            if target["role"] == "operator" and not entry.enabled and not db.execute(
                "SELECT 1 FROM users WHERE role='operator' AND airline_id=? AND active=1 AND id!=?",
                (target["airline_id"], key)).fetchone():
                fail("Алдымен осы компанияға басқа белсенді оператор қосыңыз.", 409)
            db.execute("UPDATE users SET active=? WHERE id=?", (int(entry.enabled), key))
            if not entry.enabled:
                db.execute("DELETE FROM sessions WHERE user=?", (key,))
            data.audit(db, who["id"], "Аккаунт қайта ашылды" if entry.enabled else "Аккаунт бұғатталды", f"Аккаунт #{key}")
    return {"ok": True}


class ReviewCreate(BaseModel):
    trip_code: str = Field(min_length=1, max_length=32)
    rating: int = Field(strict=True, ge=1, le=5)
    body: str = Field(min_length=1, max_length=2000)


@app.post("/api/reviews")
def create_review(entry: ReviewCreate, who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger")
    body = entry.body.strip()
    if not body:
        fail("Пікіріңізді жазыңыз.")
    with data.transaction(db):
        if not db.execute("SELECT 1 FROM travel_stamps WHERE user=? AND code=?", (who["id"], entry.trip_code)).fetchone():
            fail("Пікір аяқталған өз рейсіңізге ғана жазылады.", 409)
        try:
            key = db.execute("INSERT INTO reviews(user,rating,body,created,trip_code) VALUES (?,?,?,?,?)",
                             (who["id"], entry.rating, body, data.now_ms(), entry.trip_code)).lastrowid
        except sqlite3.IntegrityError:
            fail("Бұл рейске пікір қалдырдыңыз.", 409)
    return {"id": key}


@app.get("/api/reviewable")
def reviewable(who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger")
    return [dict(r) for r in db.execute("""SELECT t.code,t.origin,t.city,t.flight_number,t.departure FROM travel_stamps t
        WHERE t.user=? AND NOT EXISTS (SELECT 1 FROM reviews r WHERE r.trip_code=t.code)
        ORDER BY t.departure DESC""", (who["id"],))]


@app.get("/api/reviews")
def reviews(who=Depends(signed_in), db=Depends(data.database)):
    allow(who, "passenger", "admin")
    where = " WHERE r.user=?" if who["role"] == "passenger" else ""
    params = (who["id"],) if who["role"] == "passenger" else ()
    return [dict(row) for row in db.execute(
        """SELECT r.*,u.name,u.email,t.flight_number,t.city FROM reviews r JOIN users u ON u.id=r.user
           LEFT JOIN travel_stamps t ON t.code=r.trip_code""" + where + " ORDER BY r.id DESC",
        params)]


@app.get("/api/weather/cities")
def cities(who=Depends(signed_in), db=Depends(data.database)):
    return list(dict.fromkeys([*data.CITIES, *(r[0] for r in db.execute("SELECT origin FROM schedule UNION SELECT destination FROM schedule"))]))


@app.get("/api/weather")
def weather(city: str = Query(min_length=1, max_length=70), who=Depends(signed_in), db=Depends(data.database)):
    label = city.strip()
    if label not in data.CITIES and not db.execute("SELECT 1 FROM flights WHERE city=? OR base_city=?", (label, label)).fetchone():
        fail("Бұл қала рейстер тізімінде жоқ.", 404)
    return get_forecast(label)
