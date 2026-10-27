#!/bin/sh
# Restore ONLY into newly created, isolated databases, never application databases.
set -eu
if [ "$#" -ne 1 ]; then
  echo 'Usage: sh scripts/database-restore-test.sh BACKUP_DIRECTORY' >&2
  exit 2
fi
backup=$1
# Validate every input before creating databases.
for service in catalog orders inventory; do
  test -s "$backup/$service.dump" || { echo "Missing backup: $service" >&2; exit 1; }
done
for service in catalog orders inventory; do
  database="restore_verify_$(date +%s)_$$"
  # Do not install cleanup until CREATE succeeds, to avoid deleting an existing DB.
  docker compose exec -T "$service-database" sh -ec \
    'exec createdb --username="$POSTGRES_USER" "$1"' sh "$database"
  cleanup() {
    docker compose exec -T "$service-database" sh -ec \
      'exec dropdb --username="$POSTGRES_USER" "$1"' sh "$database"
  }
  trap cleanup EXIT HUP INT TERM
  docker compose exec -T "$service-database" sh -ec \
    'exec pg_restore --username="$POSTGRES_USER" --dbname="$1" --exit-on-error --no-owner --no-acl' \
    sh "$database" < "$backup/$service.dump"
  case "$service" in
    catalog) table=catalog_books ;;
    orders) table=customer_orders ;;
    inventory) table=inventory_items ;;
  esac
  docker compose exec -T "$service-database" sh -ec \
    'exec psql --username="$POSTGRES_USER" --dbname="$1" --set=ON_ERROR_STOP=1 --command="SELECT COUNT(*) FROM $2;"' \
    sh "$database" "$table"
  cleanup
  trap - EXIT HUP INT TERM
  echo "Restore verified for $service; isolated test database removed"
done
