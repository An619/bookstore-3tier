#!/bin/sh
# Logical backups of each service database; no passwords printed or stored in Git.
set -eu
umask 077
output=${1:-backups/$(date -u +%Y%m%dT%H%M%SZ)}
mkdir -p "$output"
# Refuse to overwrite an existing backup set.
for service in catalog orders inventory; do
  test ! -e "$output/$service.dump" || { echo "Backup already exists: $service" >&2; exit 1; }
done
for service in catalog orders inventory; do
  temporary="$output/$service.dump.partial"
  trap 'rm -f "$temporary"' EXIT HUP INT TERM
  docker compose exec -T "$service-database" sh -ec \
    'exec pg_dump --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --format=custom --no-owner --no-acl' > "$temporary"
  test -s "$temporary"
  mv "$temporary" "$output/$service.dump"
  echo "Backed up $service to $output/$service.dump"
done
trap - EXIT HUP INT TERM
