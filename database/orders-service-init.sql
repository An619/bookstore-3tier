CREATE TABLE IF NOT EXISTS customer_orders (id BIGSERIAL PRIMARY KEY, customer_name VARCHAR(160) NOT NULL, book_id BIGINT NOT NULL, quantity INTEGER NOT NULL, status VARCHAR(32) NOT NULL);
INSERT INTO customer_orders (customer_name, book_id, quantity, status) SELECT 'Sample Customer',1,1,'CREATED' WHERE NOT EXISTS (SELECT 1 FROM customer_orders);
CREATE TABLE IF NOT EXISTS checkout_attempts (idempotency_key VARCHAR(64) PRIMARY KEY, order_id BIGINT, status VARCHAR(32) NOT NULL, response_status INTEGER NOT NULL, message VARCHAR(200));
