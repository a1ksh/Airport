CREATE TABLE airlines (
    id INTEGER PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE UNIQUE,
    code TEXT NOT NULL COLLATE NOCASE UNIQUE
);
INSERT INTO airlines(id,name,code) VALUES (1,'Air Astana','KC');
INSERT INTO airlines(id,name,code) VALUES (2,'FlyArystan','FS');
ALTER TABLE users ADD COLUMN airline_id INTEGER REFERENCES airlines(id);
UPDATE users SET airline_id=1 WHERE role='operator';
ALTER TABLE tickets ADD COLUMN airline_id INTEGER REFERENCES airlines(id);
UPDATE tickets SET airline_id=1;
ALTER TABLE flights ADD COLUMN airline_id INTEGER REFERENCES airlines(id);
UPDATE flights SET airline_id=1;
ALTER TABLE flights ADD COLUMN base_city TEXT NOT NULL DEFAULT 'Астана';
ALTER TABLE flights ADD COLUMN duration_min INTEGER NOT NULL DEFAULT 120;
ALTER TABLE flights ADD COLUMN checkin_open_min INTEGER NOT NULL DEFAULT 180;
ALTER TABLE flights ADD COLUMN checkin_close_min INTEGER NOT NULL DEFAULT 40;
ALTER TABLE flights ADD COLUMN base_price INTEGER NOT NULL DEFAULT 25000;
ALTER TABLE travel_stamps ADD COLUMN origin TEXT NOT NULL DEFAULT 'Астана';

CREATE TABLE fares (
    id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE COLLATE NOCASE,
    baggage TEXT NOT NULL, markup_pct INTEGER NOT NULL CHECK(markup_pct BETWEEN 0 AND 500),
    refund_pct INTEGER NOT NULL CHECK(refund_pct BETWEEN 0 AND 100)
);
INSERT INTO fares(name,baggage,markup_pct,refund_pct) VALUES
    ('Лайт','Қол жүгі · 5 кг',0,0),
    ('Стандарт','Қол жүгі · 8 кг және багаж · 20 кг',20,80),
    ('Икемді','Қол жүгі · 10 кг және багаж · 32 кг',45,100);
CREATE TABLE orders (
    id INTEGER PRIMARY KEY, user INTEGER NOT NULL REFERENCES users(id),
    request_id TEXT NOT NULL, request_body TEXT NOT NULL, created INTEGER NOT NULL,
    fare_name TEXT NOT NULL, baggage TEXT NOT NULL, refund_pct INTEGER NOT NULL,
    subtotal INTEGER NOT NULL, discount_pct INTEGER NOT NULL, total INTEGER NOT NULL,
    refunded INTEGER NOT NULL DEFAULT 0, cancelled_at INTEGER, UNIQUE(user, request_id)
);

ALTER TABLE bookings RENAME TO old_bookings;
CREATE TABLE bookings (
    id INTEGER PRIMARY KEY, user INTEGER NOT NULL REFERENCES users(id),
    flight INTEGER NOT NULL REFERENCES flights(id), code TEXT NOT NULL UNIQUE,
    checked INTEGER NOT NULL DEFAULT 0, seat TEXT, trip_tasks INTEGER NOT NULL DEFAULT 0,
    order_id INTEGER REFERENCES orders(id), leg_index INTEGER NOT NULL DEFAULT 0,
    cancelled INTEGER NOT NULL DEFAULT 0, paid_amount INTEGER NOT NULL DEFAULT 0
);
INSERT INTO bookings(id,user,flight,code,checked,seat,trip_tasks)
    SELECT id,user,flight,code,checked,seat,trip_tasks FROM old_bookings;
DROP TABLE old_bookings;
CREATE UNIQUE INDEX booking_seat ON bookings(flight,seat) WHERE cancelled=0;
CREATE UNIQUE INDEX active_passenger ON bookings(user,flight) WHERE cancelled=0;
CREATE UNIQUE INDEX order_sequence ON bookings(order_id,leg_index);
ALTER TABLE reviews ADD COLUMN trip_code TEXT REFERENCES travel_stamps(code);
CREATE UNIQUE INDEX review_per_trip ON reviews(trip_code) WHERE trip_code IS NOT NULL;
CREATE TABLE market_days(day TEXT PRIMARY KEY);

CREATE VIEW schedule AS
    SELECT f.*,a.name AS airline,a.code AS airline_code,
        CASE f.direction WHEN 0 THEN f.base_city ELSE f.city END AS origin,
        CASE f.direction WHEN 0 THEN f.city ELSE f.base_city END AS destination,
        CASE f.direction WHEN 0 THEN f.time ELSE f.time-f.duration_min*60000 END AS departure,
        CASE f.direction WHEN 0 THEN f.time+f.duration_min*60000 ELSE f.time END AS arrival
    FROM flights f JOIN airlines a ON a.id=f.airline_id;
CREATE VIEW passenger_trips AS
    SELECT s.*,b.user AS passenger_id,b.code,b.seat,b.checked,b.trip_tasks,b.order_id,
        b.leg_index,b.cancelled,b.paid_amount,o.fare_name,o.baggage,o.discount_pct,o.total,
        o.refund_pct,o.refunded,o.created AS purchased_at,o.cancelled_at,
        EXISTS(SELECT 1 FROM travel_stamps t WHERE t.code=b.code) AS completed,
        EXISTS(SELECT 1 FROM reviews r WHERE r.trip_code=b.code) AS reviewed
    FROM bookings b JOIN schedule s ON s.id=b.flight LEFT JOIN orders o ON o.id=b.order_id;
