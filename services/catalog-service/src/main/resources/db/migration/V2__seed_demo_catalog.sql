-- Demo catalogue. Idempotent, so the lab can be reset without duplicates.
INSERT INTO catalog_books (title, author, category, price)
SELECT 'DevOps for Beginners', 'John Doe', 'DevOps', 499.00
WHERE NOT EXISTS (SELECT 1 FROM catalog_books WHERE title = 'DevOps for Beginners');
INSERT INTO catalog_books (title, author, category, price)
SELECT 'Kubernetes Basics', 'Jane Doe', 'Cloud Native', 599.00
WHERE NOT EXISTS (SELECT 1 FROM catalog_books WHERE title = 'Kubernetes Basics');
INSERT INTO catalog_books (title, author, category, price)
SELECT 'Mastering Jenkins', 'Alex Smith', 'CI/CD', 699.00
WHERE NOT EXISTS (SELECT 1 FROM catalog_books WHERE title = 'Mastering Jenkins');
