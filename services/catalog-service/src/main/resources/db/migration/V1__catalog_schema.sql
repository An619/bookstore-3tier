-- Catalog schema. Owned by this service; no other service reads these tables.
-- IF NOT EXISTS keeps the migration safe on a database created from the old
-- database/*-init.sql scripts before Flyway was introduced.
CREATE TABLE IF NOT EXISTS catalog_books (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(160) NOT NULL,
    author VARCHAR(120) NOT NULL,
    category VARCHAR(80) NOT NULL,
    price NUMERIC(10, 2) NOT NULL
);
