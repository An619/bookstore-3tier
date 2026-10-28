#!/bin/sh
# Start the Compose stack with bounded retries for transient registry failures.
# POSIX sh has no pipefail here, so output is redirected rather than piped; this
# preserves Docker Compose's exit status and still shows the log on failure.
set -eu
attempts=${COMPOSE_UP_ATTEMPTS:-3}
log=${COMPOSE_UP_LOG:-/tmp/bookstore-compose-up.log}
delay=${COMPOSE_RETRY_DELAY:-10}
: > "$log"
attempt=1
while [ "$attempt" -le "$attempts" ]; do
  echo "Starting Compose stack (attempt $attempt of $attempts)..." | tee -a "$log"
  if docker compose up --build -d >> "$log" 2>&1; then
    echo "Compose stack started; full log: $log"
    exit 0
  fi
  echo "Compose startup failed (attempt $attempt); collecting diagnostics" | tee -a "$log"
  docker compose logs --no-color >> "$log" 2>&1 || true
  docker compose down -v --remove-orphans >> "$log" 2>&1 || true
  attempt=$((attempt + 1))
  [ "$attempt" -le "$attempts" ] && sleep "$delay"
done
# Emit the tail so failures are visible directly in CI/Jenkins output.
tail -n 100 "$log" >&2
echo "Compose startup failed after $attempts attempts; full log: $log" >&2
exit 1
