-- Demo stock for the three catalogue books. Idempotent.
INSERT INTO inventory_items (book_id, available_quantity)
SELECT 1, 25 WHERE NOT EXISTS (SELECT 1 FROM inventory_items WHERE book_id = 1);
INSERT INTO inventory_items (book_id, available_quantity)
SELECT 2, 18 WHERE NOT EXISTS (SELECT 1 FROM inventory_items WHERE book_id = 2);
INSERT INTO inventory_items (book_id, available_quantity)
SELECT 3, 12 WHERE NOT EXISTS (SELECT 1 FROM inventory_items WHERE book_id = 3);
