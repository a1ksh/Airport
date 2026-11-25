"""Two example trips for the newly provisioned demonstration passenger."""
from datetime import datetime, timedelta
import secrets

import airport_data as data


def seed_demo_passport(db, passenger_id, operator_id):
    """Insert the initial travel history in the account setup transaction."""
    today = datetime.now(data.ASTANA).replace(hour=10, minute=0, second=0, microsecond=0)
    for city, days_ago, seat, gate in (
        ("Ыстанбұл", 14, "2A", "B3"),
        ("Дубай", 7, "3F", "B4"),
    ):
        departure = today - timedelta(days=days_ago)
        departed = int(departure.timestamp() * 1000)
        completed = int((departure + timedelta(hours=5)).timestamp() * 1000)
        while True:
            suffix = secrets.token_hex(4).upper()
            code = "NQZ-" + suffix
            number = f"DEMO {data.CITIES[city][0]}-{suffix}"
            if not db.execute(
                "SELECT 1 FROM bookings WHERE code=? UNION ALL SELECT 1 FROM flights WHERE number=?",
                (code, number),
            ).fetchone():
                break
        flight_id = db.execute(
            "INSERT INTO flights(number,city,gate,time,direction,status) VALUES (?,?,?,?,0,0)",
            (number, city, gate, departed),
        ).lastrowid
        db.execute(
            "INSERT INTO bookings(user,flight,code,checked,seat,trip_tasks) VALUES (?,?,?,1,?,15)",
            (passenger_id, flight_id, code, seat),
        )
        db.execute(
            "INSERT INTO travel_stamps(user,code,flight_number,city,departure,seat,completed) "
            "VALUES (?,?,?,?,?,?,?)",
            (passenger_id, code, number, city, departed, seat, completed),
        )
        data.audit(db, operator_id, "Сапар аяқталды", f"{number} · {city} · {code}")
