import io
import json
import tempfile
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from fastapi.testclient import TestClient

import airport_data as data
import live_flights
import sales
from server import app


class TicketSalesTests(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        db_path = patch.object(data, "DB_PATH", Path(folder.name) / "airport.sqlite3")
        db_path.start()
        self.addCleanup(db_path.stop)
        data.init_db()
        with closing(data.connect()) as db:
            self.uid = db.execute("SELECT id FROM users WHERE role='passenger'").fetchone()[0]
            for name, role, airline in (("staff1", "operator", 1), ("staff2", "operator", 2), ("admin", "admin", None),
                                       ("other", "passenger", None)):
                data.create_account(db, name, name + "@test.local", "internal-test", role, airline)
        self.p = self.client("user@airport.test", data.DEMO_PASSWORD)
        self.op = self.client("staff1@test.local")
        self.op2 = self.client("staff2@test.local")
        self.admin = self.client("admin@test.local")
        self.other = self.client("other@test.local")
        tomorrow = (datetime.now(data.ASTANA) + timedelta(days=1)).date()
        self.query = {"origin": "Астана", "destination": "Ыстанбұл", "day": tomorrow.isoformat()}
        self.offers = self.p.get("/api/offers", params=self.query).json()
        self.transfer = next(o for o in self.offers if len({f["airline_id"] for f in o["legs"]}) == 2)
        self.direct = next(o for o in self.offers if len(o["legs"]) == 1)

    def client(self, email, password="internal-test"):
        client = TestClient(app, base_url="http://localhost")
        self.addCleanup(client.close)
        r = client.post("/api/login", json={"email": email, "password": password})
        self.assertEqual(r.status_code, 200, r.text)
        return client

    def purchase_body(self, offer, fare_index=1):
        ids = [leg["id"] for leg in offer["legs"]]
        quote = self.p.get("/api/quote", params={"flights": ",".join(map(str, ids))}).json()
        fare = quote["fares"][fare_index]
        return {"fare_id": fare["id"], "legs": [{"flight": key, "seat": "1A"} for key in ids],
                "request_id": str(uuid4()), "expected_total": fare["total"]}

    def purchase(self, body, client=None):
        r = (client or self.p).post("/api/checkout", json=body)
        self.assertEqual(r.status_code, 200, r.text)
        return r.json()["code"]

    def refund(self, client, code):
        return client.request("DELETE", "/api/trips/" + code, json={})

    def test_search_and_connection_validation(self):
        self.assertEqual([o["price"] for o in self.offers], sorted(o["price"] for o in self.offers))
        only_direct = self.p.get("/api/offers", params={**self.query, "direct": "true"}).json()
        self.assertEqual(only_direct, [self.direct])
        for offer in self.offers:
            self.assertEqual(offer["minutes"], (offer["legs"][-1]["arrival"]-offer["legs"][0]["departure"])//60000)
        first, second = self.transfer["legs"]
        self.assertEqual(first["destination"], second["origin"])
        self.assertEqual(self.p.get("/api/quote", params={"flights": f"{second['id']},{first['id']}"}).status_code, 409)
        self.assertEqual(self.p.get("/api/quote", params={"flights": f"{first['id']},{first['id']}"}).status_code, 400)

    def test_one_payment_two_seats_refund_rebook_and_restart(self):
        body = self.purchase_body(self.transfer)
        with ThreadPoolExecutor(max_workers=2) as pool:
            codes = list(pool.map(lambda _: self.purchase(body), range(2)))
        self.assertEqual(codes[0], codes[1])
        code = codes[0]
        trip = self.p.get("/api/trips/" + code).json()
        self.assertEqual(len(trip["legs"]), 2)
        self.assertEqual(trip["total"], body["expected_total"])
        self.assertEqual(self.p.get("/api/home").json()["balance"], sales.OPENING_CREDIT-trip["total"])
        self.assertEqual(self.other.get("/api/trips/" + code).status_code, 404)
        self.assertEqual(self.refund(self.other, code).status_code, 404)
        second_code = trip["legs"][1]["code"]
        for leg_code in [second_code, code]:
            response = self.refund(self.p, leg_code)
            self.assertEqual(response.json()["refunded"], trip["total"]*80//100)
        self.assertEqual(self.p.get("/api/home").json()["balance"], sales.OPENING_CREDIT-trip["total"]+trip["total"]*80//100)
        for leg in trip["legs"]:
            self.assertTrue(self.p.get("/api/trips/" + leg["code"]).json()["cancelled"])
            self.assertEqual(self.p.get("/api/trips/" + leg["code"] + "/qr.svg").status_code, 409)
            self.assertNotIn("1A", self.p.get(f"/api/flights/{leg['id']}/seats").json()["occupied"])
        self.assertNotEqual(code, self.purchase({**body, "request_id": str(uuid4())}))
        data.init_db()
        with closing(data.connect()) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM orders").fetchone()[0], 2)
            self.assertEqual(db.execute("PRAGMA foreign_key_check").fetchall(), [])

    def test_conflict_on_last_leg_rolls_back_earlier_seat_and_debit(self):
        first, second = self.transfer["legs"]
        self.purchase(self.purchase_body({"legs": [second]}), self.other)
        body = self.purchase_body(self.transfer)
        self.assertEqual(self.p.post("/api/checkout", json=body).status_code, 409)
        self.assertEqual(self.p.get("/api/home").json()["balance"], sales.OPENING_CREDIT)
        self.assertEqual(self.p.get(f"/api/flights/{first['id']}/seats").json()["occupied"], [])
        self.assertEqual(self.p.get("/api/trips").json(), [])

    def test_stale_price_and_duplicate_request_with_other_contents(self):
        body = self.purchase_body(self.direct)
        self.assertEqual(self.p.post("/api/checkout", json={**body, "expected_total": 1}).status_code, 409)
        self.purchase(body)
        mismatch = {**body, "legs": [{**body["legs"][0], "seat": "1B"}]}
        self.assertEqual(self.p.post("/api/checkout", json=mismatch).status_code, 409)
        self.assertEqual(self.op.post("/api/checkout", json=body).status_code, 403)

    def test_racing_passengers_cannot_buy_the_same_seat(self):
        body = self.purchase_body(self.direct)
        with ThreadPoolExecutor(max_workers=2) as pool:
            replies = list(pool.map(lambda client: client.post("/api/checkout", json=body), [self.p, self.other]))
        self.assertEqual(sorted(r.status_code for r in replies), [200,409])
        with closing(data.connect()) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM bookings").fetchone()[0], 1)
            self.assertEqual(db.execute("SELECT SUM(total) FROM orders").fetchone()[0], body["expected_total"])

    def test_expensive_fare_and_closed_checkin_do_not_charge(self):
        f = self.direct["legs"][0]
        edit = {**f, "base_price": sales.OPENING_CREDIT+1}
        self.assertEqual(self.op.put(f"/api/flights/{f['id']}", json=edit).status_code, 200)
        body = self.purchase_body(self.direct, 0)
        self.assertEqual(self.p.post("/api/checkout", json=body).status_code, 409)
        with patch.object(data, "now_ms", return_value=f["checkin_closes"]):
            self.assertEqual(self.p.get("/api/quote", params={"flights":f["id"]}).status_code, 409)
        self.assertEqual(self.p.get("/api/home").json()["balance"], sales.OPENING_CREDIT)

    def test_carrier_cancellation_after_connection_started_refunds_only_unused_leg(self):
        body = self.purchase_body(self.transfer, 0)
        code = self.purchase(body)
        first, second = self.p.get("/api/trips/"+code).json()["legs"]
        with patch.object(data, "now_ms", return_value=first["arrival"]+1):
            self.assertEqual(self.admin.put(f"/api/flights/{second['id']}",json={**second,"status":3}).status_code, 200)
            self.assertEqual(self.refund(self.p, code).json()["refunded"], second["paid_amount"])
        receipt = self.p.get("/api/trips/"+code).json()
        self.assertEqual([r["cancelled"] for r in receipt["legs"]], [0,1])
        self.assertEqual(self.refund(self.p, second["code"]).json()["refunded"], second["paid_amount"])
        ledger = self.admin.get("/api/admin/sales").json()["payments"][0]
        self.assertEqual(ledger["refunded"], second["paid_amount"])
        self.assertEqual([r["number"] for r in ledger["legs"]],[first["number"],second["number"]])

    def test_checkin_arrival_review_and_loyalty_boundaries(self):
        flight = self.direct["legs"][0]
        code = self.purchase(self.purchase_body(self.direct))
        review = {"trip_code": code, "rating": 5, "body": "Сапар ұнады."}
        self.assertEqual(self.p.post("/api/reviews", json=review).status_code, 409)
        with patch.object(data, "now_ms", return_value=flight["checkin_opens"]-1):
            self.assertEqual(self.op.post("/api/staff/checkin", json={"code": code}).status_code, 409)
        with patch.object(data, "now_ms", return_value=flight["checkin_closes"]):
            self.assertEqual(self.op.post("/api/staff/checkin", json={"code": code}).status_code, 409)
        with patch.object(data, "now_ms", return_value=flight["checkin_opens"]):
            self.assertEqual(self.op2.post("/api/staff/checkin", json={"code": code}).status_code, 403)
            self.assertEqual(self.op.post("/api/staff/checkin", json={"code": code}).status_code, 200)
            self.assertEqual(self.refund(self.p, code).status_code, 409)
        with patch.object(data, "now_ms", return_value=flight["arrival"]-1):
            self.assertEqual(self.op.post("/api/staff/complete", json={"code": code}).status_code, 409)
        with patch.object(data, "now_ms", return_value=flight["arrival"]):
            for _ in range(2):
                self.assertEqual(self.op.post("/api/staff/complete", json={"code": code}).status_code, 200)
        self.assertEqual(len(self.p.get("/api/reviewable").json()), 1)
        self.assertEqual(self.other.post("/api/reviews", json=review).status_code, 409)
        self.assertEqual(self.p.post("/api/reviews", json=review).status_code, 200)
        self.assertEqual(self.p.post("/api/reviews", json=review).status_code, 409)
        self.assertEqual(self.p.get("/api/reviewable").json(), [])
        with closing(data.connect()) as db:
            for i in range(2, 11):
                db.execute("""INSERT INTO travel_stamps(user,code,flight_number,city,departure,completed)
                              VALUES (?,?,?,?,?,?)""", (self.uid, f"old-{i}", "AP", "Алматы", 1, 2))
                self.assertEqual(sales.loyalty(db,self.uid)["percent"], 10 if i == 10 else 5 if i >= 3 else 0)
        quote = self.p.get("/api/quote", params={"flights": self.transfer["legs"][0]["id"]}).json()
        self.assertEqual(quote["fares"][0]["total"], quote["fares"][0]["subtotal"]*90//100)

    def test_changed_checkin_window_notifies_passenger_and_cancelled_flight_refunds_light_fare(self):
        f = self.direct["legs"][0]
        code = self.purchase(self.purchase_body(self.direct, 0))
        self.assertEqual(self.p.get("/api/trips/" + code).json()["refund"]["amount"], 0)
        edit = {**f, "checkin_open_min": 240, "checkin_close_min": 30, "duration_min": 360}
        self.assertEqual(self.op.put(f"/api/flights/{f['id']}", json=edit).status_code, 200)
        self.assertIn("Тіркеу", self.p.get("/api/notices").json()[0]["body"])
        self.assertEqual(self.op.put(f"/api/flights/{f['id']}", json={**edit, "status": 3}).status_code, 200)
        self.assertEqual(self.refund(self.p, code).json()["refunded"], f["base_price"])

    def test_airline_scopes_catalog_accounts_and_support(self):
        flight = self.direct["legs"][0]
        path = f"/api/flights/{flight['id']}"
        self.assertEqual(self.op2.get(path).status_code, 403)
        self.assertEqual(self.op2.get(path+"/manifest").status_code, 403)
        self.assertEqual(self.op2.put(path,json=flight).status_code, 403)
        self.assertEqual(self.admin.get(path+"/manifest").status_code, 200)
        self.assertTrue(all(f["airline_id"] == 2 for f in self.op2.get("/api/flights").json()))
        ticket = self.p.post("/api/tickets", json={"airline_id": 2, "subject": "Багаж", "body": "Сұрақ"}).json()["id"]
        self.assertEqual(self.op.get(f"/api/tickets/{ticket}").status_code, 404)
        self.assertEqual(self.op.get("/api/tickets").json(), [])
        self.assertEqual(self.op2.get(f"/api/tickets/{ticket}").status_code, 200)
        self.assertEqual(self.admin.get(f"/api/tickets/{ticket}").status_code, 200)
        company = {"name": "Example Air", "code": "EX", "operator": {"name": "Desk", "email": "desk@test.local", "password": "test-account"}}
        invalid = {**company, "operator": {**company["operator"], "email": "staff1@test.local"}}
        self.assertEqual(self.admin.post("/api/admin/airlines", json=invalid).status_code, 409)
        self.assertEqual(self.op.post("/api/admin/airlines", json=company).status_code, 403)
        key = self.admin.post("/api/admin/airlines", json=company).json()["id"]
        self.assertEqual(self.client("desk@test.local","test-account").get("/api/me").json()["airline_id"], key)
        f = {"name": "Comfort", "baggage": "25 kg", "markup_pct": 30, "refund_pct": 90}
        self.assertEqual(self.admin.post("/api/admin/fares", json=f).status_code, 200)
        self.assertIn("Comfort", [r["name"] for r in self.p.get("/api/catalog").json()["fares"]])
        self.assertEqual(len(self.admin.get("/api/admin/sales").json()["companies"]), 3)

    def test_migration_preserves_legacy_data(self):
        with patch.object(data, "DB_PATH", data.DB_PATH.with_name("legacy.sqlite3")), closing(data.connect()) as db:
            db.executescript((data.ROOT / "schema.sql").read_text(encoding="utf-8"))
            db.execute("INSERT INTO users(id,name,email,role,salt,password) VALUES (1,'Old','old@test.local','operator','','')")
            db.execute("INSERT INTO flights VALUES (1,'AP9','Алматы','B1',2000000000000,0,0)")
            db.execute("INSERT INTO bookings VALUES (1,1,1,'NQZ-AAAAAAAA',0,'1A',3)")
            db.execute("INSERT INTO reviews VALUES (1,1,5,'Legacy',12345)")
            for _ in range(2):
                data.init_db()
            row = db.execute("SELECT * FROM passenger_trips WHERE code='NQZ-AAAAAAAA'").fetchone()
            self.assertEqual((row["seat"],row["trip_tasks"],row["order_id"]), ("1A",3,None))
            self.assertEqual(db.execute("SELECT airline_id FROM users WHERE id=1").fetchone()[0], 1)
            self.assertIsNone(db.execute("SELECT trip_code FROM reviews").fetchone()[0])

    def test_live_observations_use_registration_and_ignore_ground(self):
        feed = {"now": int(time.time()*1000), "ac": [
            {"flight": "KZR123 ", "alt_baro": 22000, "r": "P4-XYZ", "t": "A320", "seen": 1},
            {"flight": "KZR124", "alt_baro": "ground", "seen": 0}]}
        with patch.dict(live_flights._cache, {}, clear=True), patch.object(live_flights, "urlopen",
                  return_value=io.BytesIO(json.dumps(feed).encode())) as fetch:
            result = self.p.get("/api/live", params={"mode": "registration", "q": "p4-xyz"})
            self.assertEqual(result.json()["aircraft"], [{"callsign": "KZR123", "registration": "P4-XYZ", "type": "A320"}])
            self.assertTrue(fetch.call_args.args[0].full_url.endswith("/v2/reg/P4-XYZ"))
            self.assertEqual(self.p.get("/api/live", params={"mode": "registration", "q": "P4-XYZ"}).status_code, 200)
            self.assertEqual(fetch.call_count, 1)

    def test_live_empty_result_and_outage_are_distinct(self):
        with patch.dict(live_flights._cache, {}, clear=True):
            with patch.object(live_flights, "urlopen", return_value=io.BytesIO(
                      json.dumps({"now": int(time.time()*1000), "ac": []}).encode())) as fetch:
                self.assertEqual(self.p.get("/api/live", params={"mode":"flight","q":"KC 123"}).json()["aircraft"], [])
                self.assertTrue(fetch.call_args.args[0].full_url.endswith("/callsign/KZR123"))
            with patch.object(live_flights, "urlopen", side_effect=OSError):
                self.assertEqual(self.p.get("/api/live").status_code, 503)


if __name__ == "__main__":
    unittest.main()
