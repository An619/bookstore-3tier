-- One demo order from the direct API path: CREATED, no stock reservation.
INSERT INTO customer_orders (customer_name, book_id, quantity, status)
SELECT 'Sample Customer', 1, 1, 'CREATED'
WHERE NOT EXISTS (SELECT 1 FROM customer_orders);
