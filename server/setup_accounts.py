"""Provision private staff passwords on the server host."""
import secrets
from contextlib import closing

import airport_data as data
from demo_passport import seed_demo_passport


def main():
    data.init_db()
    created = []
    with closing(data.connect()) as db, data.transaction(db):
        # Starting the web service may already have created the demo passenger.
        # Only a fresh course database receives sample trips.
        fresh_course = db.execute(
            "SELECT (SELECT COUNT(*) FROM users) = 1 AND NOT EXISTS (SELECT 1 FROM bookings) "
            "AND NOT EXISTS (SELECT 1 FROM travel_stamps)"
        ).fetchone()[0]
        ids = {}
        for name, email, role in data.DEMOS:
            existing = db.execute("SELECT id,role FROM users WHERE email=?", (email,)).fetchone()
            if existing:
                if existing["role"] != role:
                    raise RuntimeError(f"{email} already belongs to a different role.")
                ids[role] = existing["id"]
                continue
            password = secrets.token_urlsafe(20)
            ids[role] = data.create_account(db, name, email, password, role)
            created.append((email, password))
        if fresh_course:
            seed_demo_passport(db, ids["passenger"], ids["operator"])
    if not created:
        print("The staff accounts are already configured.")
        return
    print("Save these initial staff passwords privately. You can change them in Profile.")
    for email, password in created:
        print(f"{email}  {password}")


if __name__ == "__main__":
    main()
