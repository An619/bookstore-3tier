# Runbook: service-owned PostgreSQL backups and restore verification

For a running **disposable Docker Compose stack**, from the repository root:

```sh
sh scripts/database-backup.sh backups/test-run
sh scripts/database-restore-test.sh backups/test-run
```

The backup command creates custom-format dumps of Catalog, Orders and Inventory
using the database containers' configured users. Files use restrictive local
permissions; backup directories are excluded from Git. It refuses to overwrite
an existing dump. An interrupted run may leave a partial backup set: review it
and choose a fresh directory rather than assuming all three dumps are present.

The restore test requires all three dumps, then creates an isolated temporary
database in each database container, restores with `--exit-on-error`, queries the
service table and drops only the database that this script successfully created.
It never targets the application database. This checks dump readability/schema
and basic queryability, not full API semantics or exact data equality. A failed
cleanup may require an operator to remove the named `restore_verify_*` database.

CI runs these commands after the disposable-stack integration tests. Do not run
restore tests on a busy production database host: they consume storage and CPU.
Do not run backups/tests concurrently or share the same output directory.

## Production requirements (not provisioned by these scripts)

- Use your managed PostgreSQL provider's automated snapshots and point-in-time
  recovery, or an approved database operator and encrypted external backup store.
- Define retention, access policies, encryption, RPO/RTO and an accountable owner.
- Keep encrypted backup copies outside the cluster; never commit customer data.
- Schedule restore drills into a separate environment, verify row counts and
  business invariants, exercise API reads, and record restore time and data loss.
- Backups across three databases are not an atomic distributed snapshot. Checkout
  recovery must reconcile orders and inventory; do not restore one database
  independently without reviewing the cross-service consistency impact. Restore
  both to the same point in time: the inventory ledger
  (`inventory_operations`) is what lets the orders reconciler decide whether a
  reservation landed, so an inventory snapshot older than the orders database
  invalidates it.
- After a restore, restart the orders service. Any `PENDING` outbox row is
  retried with its original idempotency key (inventory deduplicates it), and
  attempts left in `NEEDS_RECONCILIATION` are settled automatically from the
  ledger.
- Never replace production data until recovery has been explicitly approved.

Legacy backups, if the optional legacy profile is running, can still be created
with `docker compose exec -T database pg_dump -U bookuser -d bookstore -Fc`.
