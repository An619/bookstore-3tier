# Runbook: PostgreSQL backup and restore (local lab)

This procedure is for a disposable local Compose environment. Adapt retention, encryption, storage, access control and recovery objectives before using any shared environment.

## Create a logical backup

```bash
mkdir -p backups
docker compose exec -T database pg_dump -U bookuser -d bookstore --format=custom > backups/bookstore-$(date +%Y%m%d-%H%M%S).dump
```

Verify the file is non-empty and store it somewhere separate from the container/volume. Do not commit database dumps if they contain real or sensitive data.

## Restore into a separate test database

Create a fresh test database in the running database container:
```bash
docker compose exec -T database createdb -U bookuser bookstore_restore_test
cat backups/<backup-file>.dump | docker compose exec -T database pg_restore -U bookuser -d bookstore_restore_test --clean --if-exists
docker compose exec -T database psql -U bookuser -d bookstore_restore_test -c 'SELECT id, title, author, price FROM books ORDER BY id;'
``

A restore may require ownership/privilege options depending on the backup source. Review errors; do not blindly ignore them.

## Recovery validation
- Confirm expected tables and row counts.
- Exercise the API against the restored database in an isolated test environment.
- Record restore duration and any data loss against your chosen RTO/RPO.
- Keep the original database untouched until the restore is verified.
