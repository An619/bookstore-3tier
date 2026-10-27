#!/bin/sh
# Start the Compose stack with bounded retries for transient registry failures.
set -eu
attempts=${COMPOSE_UP_ATTEMPTS:-3}
log=${COMPOSE_UP_LOG:-/tmp/bookstore-compose-up.log}
: > "$log"
attempt=1
while [ "$attempt" -le "$attempts" ]; do
  echo "Starting Compose stack (attempt $attempt of $attempts)..." | tee -a "$log"
  if docker compose up --build -d 2>&1 | tee -a "$log"; then
    exit 0
  fi
  docker compose down -v --remove-orphans >> "$log" 2>&1 || true
  attempt=$((attempt + 1))
  [ "$attempt" -le "$attempts" ] && sleep 10
done
echo "Compose startup failed after $attempts attempts; see $log" >&2
exit 1
