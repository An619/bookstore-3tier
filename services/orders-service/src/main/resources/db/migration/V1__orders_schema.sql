-- Orders schema: order records, durable idempotency outcomes and the checkout
-- outbox that makes stock reservation survive a crash.
CREATE TABLE IF NOT EXISTS customer_orders (
    id BIGSERIAL PRIMARY KEY,
    customer_name VARCHAR(160) NOT NULL,
    book_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS checkout_attempts (
    idempotency_key VARCHAR(64) PRIMARY KEY,
    order_id BIGINT,
    status VARCHAR(32) NOT NULL,
    response_status INTEGER NOT NULL,
    message VARCHAR(200)
);

CREATE TABLE IF NOT EXISTS checkout_outbox (
    id BIGSERIAL PRIMARY KEY,
    aggregate_key VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    book_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL,
    reconcile_cycles INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(200)
);

CREATE INDEX IF NOT EXISTS checkout_outbox_status_idx ON checkout_outbox (status, id);
CREATE INDEX IF NOT EXISTS checkout_attempts_status_idx ON checkout_attempts (status);
