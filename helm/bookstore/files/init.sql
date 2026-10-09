CREATE TABLE IF NOT EXISTS books (
    id SERIAL PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    author VARCHAR(100) NOT NULL,
    price NUMERIC(10,2) NOT NULL
);

INSERT INTO books (title, author, price)
VALUES
    ('DevOps for Beginners', 'John Doe', 499.00),
    ('Kubernetes Basics', 'Jane Doe', 599.00),
    ('Mastering Jenkins', 'Alex Smith', 699.00)
ON CONFLICT DO NOTHING;
