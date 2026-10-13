CREATE TABLE IF NOT EXISTS inventory_items (id BIGSERIAL PRIMARY KEY, book_id BIGINT NOT NULL UNIQUE, available_quantity INTEGER NOT NULL CHECK (available_quantity >= 0));
INSERT INTO inventory_items (book_id, available_quantity) VALUES (1,25),(2,18),(3,12) ON CONFLICT (book_id) DO NOTHING;
