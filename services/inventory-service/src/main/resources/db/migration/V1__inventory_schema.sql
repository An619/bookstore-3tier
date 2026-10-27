-- Inventory schema: stock per book plus the idempotency ledger the orders
-- reconciler reads to decide whether a reservation actually landed.
CREATE TABLE IF NOT EXISTS inventory_items (
    id BIGSERIAL PRIMARY KEY,
    book_id BIGINT NOT NULL UNIQUE,
    available_quantity INTEGER NOT NULL CHECK (available_quantity >= 0)
);

CREATE TABLE IF NOT EXISTS inventory_operations (
    idempotency_key VARCHAR(64) PRIMARY KEY,
    operation VARCHAR(16) NOT NULL,
    book_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL,
    resulting_quantity INTEGER NOT NULL
);
