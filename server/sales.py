"""Route comparison, simulated payments and itinerary refunds."""
import json
import secrets
from collections import defaultdict
from datetime import date, datetime, time
from typing import Literal
from uuid import UUID

from fastapi import HTTPException
from pydantic import BaseModel, Field

import airport_data as data

MINUTE = 60_000
MIN_CONNECTION = 60
MAX_CONNECTION = 360
OPENING_CREDIT = 1_000_000


class LegSelection(BaseModel):
    flight: int = Field(gt=0)
    seat: str = Field(max_length=4)


class Purchase(BaseModel):
    legs: list[LegSelection] = Field(min_length=1, max_length=2)
    fare_id: int = Field(gt=0)
    expected_total: int = Field(ge=0)
    request_id: UUID


class PaymentStart(Purchase):
    method: Literal["wallet", "card", "qr"]


class PaymentApproval(BaseModel):
    code: str = Field(default="", max_length=4)


def loyalty(db, uid):
    count = db.execute("SELECT COUNT(*) FROM travel_stamps WHERE user=?", (uid,)).fetchone()[0]
    percent = 10 if count >= 10 else 5 if count >= 3 else 0
    return {"completed": count, "percent": percent, "next_at": 3 if count < 3 else 10 if count < 10 else None}


def balance(db, uid):
    spent = db.execute("SELECT COALESCE(SUM(total-refunded),0) FROM orders WHERE user=? AND payment_method='wallet'", (uid,)).fetchone()[0]
    return OPENING_CREDIT - spent


def prices(legs, fare, discount):
    subtotals = [(leg["base_price"] * (100 + fare["markup_pct"]) + 50) // 100 for leg in legs]
    amounts = [(item * (100 - discount) + 50) // 100 for item in subtotals]
    return {**dict(fare), "subtotal": sum(subtotals), "total": sum(amounts), "amounts": amounts}


def connected(first, second):
    wait = second["departure"] - first["arrival"]
    return (first["destination"] == second["origin"] and first["origin"] != second["destination"]
            and MIN_CONNECTION*MINUTE <= wait <= MAX_CONNECTION*MINUTE)


def offers(db, uid, origin, destination, day, direct, sort):
    if day < datetime.now(data.ASTANA).date() or day > date(2100, 1, 1):
        return []
    start = int(datetime.combine(day, time.min, data.ASTANA).timestamp()*1000)
    end = start + 24*60*MINUTE
    # The second departure may be the next day, even if the first was before midnight.
    legs = db.execute("""SELECT s.* FROM schedule s WHERE s.status!=3 AND s.departure>=?
        AND s.departure<? AND s.departure-s.checkin_close_min*60000>? AND (s.origin=? OR s.destination=?)
        AND (SELECT COUNT(*) FROM bookings b WHERE b.flight=s.id AND b.cancelled=0) < ?
        AND NOT EXISTS (SELECT 1 FROM bookings b WHERE b.flight=s.id AND b.user=? AND b.cancelled=0)
        ORDER BY s.departure,s.id""", (start, end+30*3600_000, data.now_ms(), origin, destination, len(data.SEATS), uid)).fetchall()
    onward = defaultdict(list)
    for leg in legs:
        if leg["destination"] == destination:
            onward[leg["origin"]].append(leg)
    fare = db.execute("SELECT * FROM fares WHERE available=1 ORDER BY markup_pct,id LIMIT 1").fetchone()
    discount = loyalty(db, uid)["percent"]
    results = []
    for first in legs:
        if first["origin"] != origin or first["departure"] >= end:
            continue
        routes = []
        if first["destination"] == destination:
            routes.append([first])
        elif not direct:
            routes.extend([first, second] for second in onward[first["destination"]] if connected(first, second))
        for route in routes:
            results.append({"legs": [data.flight_json(r) for r in route],
                            "price": prices(route, fare, discount)["total"],
                            "minutes": (route[-1]["arrival"]-first["departure"])//MINUTE})
    key = {"price": lambda r: (r["price"], r["minutes"]), "duration": lambda r: (r["minutes"], r["price"]),
           "departure": lambda r: (r["legs"][0]["departure"], r["price"])}[sort]
    return sorted(results, key=key)


def load_legs(db, ids):
    if len(ids) not in (1, 2) or len(set(ids)) != len(ids):
        raise HTTPException(400, "Бір немесе екі түрлі рейс таңдаңыз.")
    legs = [db.execute("SELECT * FROM schedule WHERE id=?", (key,)).fetchone() for key in ids]
    if any(leg is None for leg in legs):
        raise HTTPException(404, "Рейс табылмады.")
    if any(leg["status"] == 3 or leg["departure"]-leg["checkin_close_min"]*MINUTE <= data.now_ms() for leg in legs):
        raise HTTPException(409, "Бұл рейске билет сату жабылды.")
    if len(legs) == 2 and not connected(*legs):
        raise HTTPException(409, "Рейстер арасында 1–6 сағат және ортақ ауысып міну әуежайы болуы керек.")
    return legs


def occupied(db, key):
    return [r[0] for r in db.execute("SELECT seat FROM bookings WHERE flight=? AND cancelled=0 AND seat IS NOT NULL", (key,))]


def quote(db, uid, ids):
    legs = load_legs(db, ids)
    for key in ids:
        if db.execute("SELECT 1 FROM bookings WHERE flight=? AND user=? AND cancelled=0", (key, uid)).fetchone():
            raise HTTPException(409, "Бұл рейске билетіңіз бар. «Сапарлар» бөлімін ашыңыз.")
    tier = loyalty(db, uid)
    return {"legs": [{**data.flight_json(leg), "occupied": occupied(db, leg["id"])} for leg in legs],
            "seats": data.SEATS, "loyalty": tier, "balance": balance(db, uid),
            "fares": [prices(legs, fare, tier["percent"]) for fare in db.execute("SELECT * FROM fares WHERE available=1 ORDER BY markup_pct,id")]}


def selection_key(entry):
    # This is also the idempotency payload of wallet orders from the first version.
    return json.dumps([[(leg.flight, leg.seat) for leg in entry.legs], entry.fare_id, entry.expected_total])


def check_purchase(db, uid, entry, method):
    legs = load_legs(db, [leg.flight for leg in entry.legs])
    fare = db.execute("SELECT * FROM fares WHERE id=? AND available=1", (entry.fare_id,)).fetchone()
    if not fare:
        raise HTTPException(409, "Тариф өзгерді. Билетті қайта таңдаңыз.")
    discount = loyalty(db, uid)["percent"]
    price = prices(legs, fare, discount)
    if price["total"] != entry.expected_total:
        raise HTTPException(409, "Баға өзгерді. Төлем сомасын қайта тексеріңіз.")
    if method == "wallet" and balance(db, uid) < price["total"]:
        raise HTTPException(409, "Демо әмиянда қаражат жеткіліксіз.")
    for leg in entry.legs:
        if leg.seat not in data.SEATS:
            raise HTTPException(400, "Әр рейске орын таңдаңыз.")
        if db.execute("SELECT 1 FROM bookings WHERE flight=? AND cancelled=0 AND (seat=? OR user=?)",
                      (leg.flight, leg.seat, uid)).fetchone():
            raise HTTPException(409, "Орын бос емес немесе бұл рейске билетіңіз бар. Таңдауды жаңартыңыз.")
    return fare, discount, price


def existing_order(db, uid, request_id):
    return db.execute("""SELECT o.*,b.code FROM orders o JOIN bookings b ON b.order_id=o.id AND b.leg_index=0
                         WHERE o.user=? AND o.request_id=?""", (uid, str(request_id))).fetchone()


def issue_tickets(db, uid, entry, method, ref):
    # The caller owns one immediate transaction for payment and every seat.
    prior = existing_order(db, uid, entry.request_id)
    if prior:
        if prior["request_body"] != selection_key(entry) or prior["payment_method"] != method:
            raise HTTPException(409, "Бұл төлем коды басқа тапсырысқа тиесілі.")
        return {"code": prior["code"]}
    fare, discount, price = check_purchase(db, uid, entry, method)
    order = db.execute("""INSERT INTO orders(user,request_id,request_body,created,fare_name,baggage,
        refund_pct,subtotal,discount_pct,total,payment_method,payment_ref) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
        (uid, str(entry.request_id), selection_key(entry), data.now_ms(), fare["name"], fare["baggage"],
         fare["refund_pct"], price["subtotal"], discount, price["total"], method, ref)).lastrowid
    codes = []
    for i, leg in enumerate(entry.legs):
        code = "NQZ-" + secrets.token_hex(4).upper()
        while db.execute("SELECT 1 FROM bookings WHERE code=?", (code,)).fetchone():
            code = "NQZ-" + secrets.token_hex(4).upper()
        db.execute("""INSERT INTO bookings(user,flight,code,seat,order_id,leg_index,paid_amount)
                      VALUES (?,?,?,?,?,?,?)""", (uid, leg.flight, code, leg.seat, order, i, price["amounts"][i]))
        codes.append(code)
    return {"code": codes[0]}


def checkout(db, uid, entry):
    """Older clients explicitly confirmed their wallet payment on the seat screen."""
    with data.transaction(db):
        if db.execute("SELECT 1 FROM payment_requests WHERE user=? AND request_id=?", (uid, str(entry.request_id))).fetchone():
            raise HTTPException(409, "Төлемді растау бетін ашыңыз.")
        return issue_tickets(db, uid, entry, "wallet", "AP-" + secrets.token_hex(8).upper())


def start_payment(db, uid, entry):
    purchase = Purchase(**entry.model_dump())
    selection = purchase.model_dump_json()
    with data.transaction(db):
        old = db.execute("SELECT * FROM payment_requests WHERE user=? AND request_id=?", (uid, str(entry.request_id))).fetchone()
        if old:
            if old["selection"] != selection or old["method"] != entry.method:
                raise HTTPException(409, "Бұл төлем коды басқа тапсырысқа тиесілі.")
            return {"ref": old["ref"]}
        if existing_order(db, uid, entry.request_id):
            raise HTTPException(409, "Билет рәсімделген. «Сапарлар» бөлімін ашыңыз.")
        check_purchase(db, uid, purchase, entry.method)
        now = data.now_ms()
        ref = "AP-" + secrets.token_hex(8).upper()
        challenge = f"{secrets.randbelow(10000):04}" if entry.method == "card" else None
        db.execute("""INSERT INTO payment_requests(ref,user,request_id,selection,method,created,expires,challenge)
                      VALUES (?,?,?,?,?,?,?,?)""",
                   (ref, uid, str(entry.request_id), selection, entry.method, now, now + 5*MINUTE, challenge))
    return {"ref": ref}


def payment_request(db, uid, ref):
    row = db.execute("""SELECT p.*,b.code FROM payment_requests p LEFT JOIN orders o ON o.payment_ref=p.ref
                        LEFT JOIN bookings b ON b.order_id=o.id AND b.leg_index=0 WHERE p.user=? AND p.ref=?""",
                     (uid, ref)).fetchone()
    if not row:
        raise HTTPException(404, "Төлем табылмады.")
    return row


def payment_state(row):
    if row["code"]:
        return "paid"
    if row["cancelled"]:
        return "cancelled"
    return "expired" if data.now_ms() >= row["expires"] else "pending"


def payment_details(db, uid, ref):
    row = payment_request(db, uid, ref)
    entry = Purchase.model_validate_json(row["selection"])
    state = payment_state(row)
    legs = []
    for leg in entry.legs:
        flight = db.execute("SELECT number,origin,destination FROM schedule WHERE id=?", (leg.flight,)).fetchone()
        legs.append({**dict(flight), "id": leg.flight, "seat": leg.seat})
    return {"ref": ref, "state": state, "method": row["method"], "amount": entry.expected_total, "legs": legs,
            "expires": row["expires"], "created": row["created"], "code": row["code"],
            "balance": balance(db, uid) if row["method"] == "wallet" else None,
            "demo_code": row["challenge"] if state == "pending" else None}


def confirm_payment(db, uid, ref, approval):
    with data.transaction(db):
        row = payment_request(db, uid, ref)
        state = payment_state(row)
        if state == "paid":
            return {"code": row["code"]}
        if state != "pending":
            raise HTTPException(409, "Бұл төлем жабылды. Билетті қайта рәсімдеңіз.")
        if row["method"] == "card" and approval.code != row["challenge"]:
            raise HTTPException(400, "Оқу хабарламасындағы төрт таңбалы кодты енгізіңіз.")
        entry = Purchase.model_validate_json(row["selection"])
        return issue_tickets(db, uid, entry, row["method"], ref)


def cancel_payment(db, uid, ref):
    with data.transaction(db):
        row = payment_request(db, uid, ref)
        if row["code"]:
            raise HTTPException(409, "Билет төленген. Қайтару үшін билетті ашыңыз.")
        db.execute("UPDATE payment_requests SET cancelled=1 WHERE ref=?", (ref,))
    return {"ok": True}


def first_purchase_to_review(db, uid):
    row = db.execute("""SELECT o.id,b.code FROM orders o JOIN bookings b ON b.order_id=o.id AND b.leg_index=0
        WHERE o.user=? AND NOT EXISTS (SELECT 1 FROM reviews r WHERE r.user=o.user AND r.purchase_order IS NOT NULL)
        ORDER BY o.id LIMIT 1""", (uid,)).fetchone()
    return dict(row) if row else None


def order_legs(db, uid, code):
    leg = db.execute("SELECT * FROM passenger_trips WHERE passenger_id=? AND code=?", (uid, code)).fetchone()
    if leg is None:
        raise HTTPException(404, "Бронь табылмады.")
    if leg["order_id"] is None:
        return [leg]
    return db.execute("SELECT * FROM passenger_trips WHERE order_id=? ORDER BY leg_index", (leg["order_id"],)).fetchall()


def refund_terms(legs):
    first = legs[0]
    if first["cancelled_at"] or first["cancelled"]:
        return {"allowed": False, "amount": first["refunded"] or 0, "reason": "Билет қайтарылды."}
    disrupted = any(leg["status"] == 3 for leg in legs)
    missed_connection = len(legs) == 2 and not connected(*legs)
    remaining = [leg for leg in legs if not leg["completed"] and (leg["status"] == 3 or leg["departure"] > data.now_ms())]
    if (disrupted or missed_connection) and remaining:
        return {"allowed": True, "amount": sum(leg["paid_amount"] for leg in remaining),
                "codes": [leg["code"] for leg in remaining], "percent": 100,
                "reason": "Рейс тоқтатылды." if disrupted else "Ауысып міну кестесі өзгерді."}
    if len(remaining) != len(legs) or any(leg["checked"] for leg in legs):
        return {"allowed": False, "amount": 0, "reason": "Тіркелген немесе пайдаланылған билетті қолданбада қайтару жабық."}
    rate = first["refund_pct"] or 0
    return {"allowed": True, "amount": (first["total"] or 0)*rate//100, "percent": rate,
            "codes": [leg["code"] for leg in legs], "reason": "Тариф шарты бойынша."}


def refund(db, uid, code, expected):
    with data.transaction(db):
        legs = order_legs(db, uid, code)
        first = legs[0]
        terms = refund_terms(legs)
        if first["cancelled_at"] or first["cancelled"]:
            return {"refunded": terms["amount"]}
        if not terms["allowed"] or (expected is not None and terms["amount"] != expected):
            raise HTTPException(409, terms["reason"] if not terms["allowed"] else "Қайтару сомасы өзгерді. Қайта тексеріңіз.")
        if first["order_id"] is not None:
            db.execute("UPDATE orders SET refunded=?,cancelled_at=? WHERE id=?", (terms["amount"], data.now_ms(), first["order_id"]))
        db.executemany("UPDATE bookings SET cancelled=1 WHERE code=?", [(item,) for item in terms["codes"]])
    return {"refunded": terms["amount"]}
