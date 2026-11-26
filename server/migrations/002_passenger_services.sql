ALTER TABLE fares ADD COLUMN available INTEGER NOT NULL DEFAULT 1;
UPDATE OR IGNORE fares SET name='Эконом' WHERE id=1 AND name='Лайт';
UPDATE OR IGNORE fares SET name='Эконом Плюс' WHERE id=2 AND name='Стандарт';
UPDATE OR IGNORE fares SET name='Бизнес-класс' WHERE id=3 AND name='Икемді';
-- A pre-existing custom fare with the new name takes precedence.
UPDATE fares SET available=0 WHERE (id,name) IN ((1,'Лайт'),(2,'Стандарт'),(3,'Икемді'));
UPDATE orders SET fare_name=CASE fare_name WHEN 'Лайт' THEN 'Эконом' WHEN 'Стандарт' THEN 'Эконом Плюс'
    WHEN 'Икемді' THEN 'Бизнес-класс' END WHERE fare_name IN ('Лайт','Стандарт','Икемді');

ALTER TABLE orders ADD COLUMN payment_method TEXT NOT NULL DEFAULT 'wallet' CHECK(payment_method IN ('wallet','card','qr'));
ALTER TABLE orders ADD COLUMN payment_ref TEXT;
UPDATE orders SET payment_ref=printf('AP-%08X',id);
CREATE UNIQUE INDEX payment_receipt ON orders(payment_ref);
CREATE TABLE payment_requests (
    ref TEXT PRIMARY KEY, user INTEGER NOT NULL REFERENCES users(id), request_id TEXT NOT NULL,
    selection TEXT NOT NULL, method TEXT NOT NULL CHECK(method IN ('wallet','card','qr')),
    created INTEGER NOT NULL, expires INTEGER NOT NULL, challenge TEXT, cancelled INTEGER NOT NULL DEFAULT 0,
    UNIQUE(user,request_id)
);
ALTER TABLE reviews ADD COLUMN purchase_order INTEGER REFERENCES orders(id);
CREATE UNIQUE INDEX first_purchase_review ON reviews(user) WHERE purchase_order IS NOT NULL;
CREATE INDEX passenger_orders ON orders(user,id);
