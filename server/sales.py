"""Route comparison, demo wallet settlement and whole-itinerary refunds."""
import json
import secrets
from collections import defaultdict
from datetime import date, datetime, time
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


def loyalty(db, uid):
    count = db.execute("SELECT COUNT(*) FROM travel_stamps WHERE user=?", (uid,)).fetchone()[0]
    percent = 10 if count >= 10 else 5 if count >= 3 else 0
    return {"completed": count, "percent": percent, "next_at": 3 if count < 3 else 10 if count < 10 else None}


def balance(db, uid):
    spent = db.execute("SELECT COALESCE(SUM(total-refunded),0) FROM orders WHERE user=?", (uid,)).fetchone()[0]
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
    fare = db.execute("SELECT * FROM fares ORDER BY markup_pct,id LIMIT 1").fetchone()
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
            "fares": [prices(legs, fare, tier["percent"]) for fare in db.execute("SELECT * FROM fares ORDER BY markup_pct,id")]}


def checkout(db, uid, entry):
    request = str(entry.request_id)
    body = json.dumps([[(leg.flight, leg.seat) for leg in entry.legs], entry.fare_id, entry.expected_total])
    with data.transaction(db):
        prior = db.execute("SELECT id,request_body FROM orders WHERE user=? AND request_id=?", (uid, request)).fetchone()
        if prior:
            if prior["request_body"] != body:
                raise HTTPException(409, "Бұл төлем коды басқа тапсырысқа тиесілі.")
            code = db.execute("SELECT code FROM bookings WHERE order_id=? AND leg_index=0", (prior["id"],)).fetchone()[0]
            return {"code": code}
        legs = load_legs(db, [leg.flight for leg in entry.legs])
        fare = db.execute("SELECT * FROM fares WHERE id=?", (entry.fare_id,)).fetchone()
        if not fare:
            raise HTTPException(404, "Тариф табылмады.")
        discount = loyalty(db, uid)["percent"]
        price = prices(legs, fare, discount)
        if price["total"] != entry.expected_total:
            raise HTTPException(409, "Баға өзгерді. Төлем сомасын қайта тексеріңіз.")
        if balance(db, uid) < price["total"]:
            raise HTTPException(409, "Демо әмиянда қаражат жеткіліксіз.")
        order = db.execute("""INSERT INTO orders(user,request_id,request_body,created,fare_name,baggage,
            refund_pct,subtotal,discount_pct,total) VALUES (?,?,?,?,?,?,?,?,?,?)""",
            (uid, request, body, data.now_ms(), fare["name"], fare["baggage"], fare["refund_pct"],
             price["subtotal"], discount, price["total"])).lastrowid
        codes = []
        for i, leg in enumerate(entry.legs):
            if leg.seat not in data.SEATS:
                raise HTTPException(400, "Әр рейске орын таңдаңыз.")
            if db.execute("""SELECT 1 FROM bookings WHERE flight=? AND cancelled=0 AND (seat=? OR user=?)""",
                          (leg.flight, leg.seat, uid)).fetchone():
                raise HTTPException(409, "Орын бос емес немесе бұл рейске билетіңіз бар. Таңдауды жаңартыңыз.")
            code = "NQZ-" + secrets.token_hex(4).upper()
            while db.execute("SELECT 1 FROM bookings WHERE code=?", (code,)).fetchone():
                code = "NQZ-" + secrets.token_hex(4).upper()
            db.execute("""INSERT INTO bookings(user,flight,code,seat,order_id,leg_index,paid_amount)
                          VALUES (?,?,?,?,?,?,?)""", (uid, leg.flight, code, leg.seat, order, i, price["amounts"][i]))
            codes.append(code)
    return {"code": codes[0]}


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
