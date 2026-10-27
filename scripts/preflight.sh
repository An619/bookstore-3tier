#!/bin/sh
# Reports installed tools only; never reads or prints credential values.
set -eu
missing=0
for tool in git java mvn node npm python3 docker helm kubectl; do
  if command -v "$tool" >/dev/null 2>&1; then
    printf 'OK      %s\n' "$tool"
  else
    printf 'MISSING %s\n' "$tool"
    missing=1
  fi
done
if command -v docker >/dev/null 2>&1; then
  docker info >/dev/null 2>&1 || { echo 'MISSING running Docker daemon'; missing=1; }
  docker compose version >/dev/null 2>&1 || { echo 'MISSING Docker Compose v2'; missing=1; }
fi
if command -v kubectl >/dev/null 2>&1; then
  kubectl cluster-info --request-timeout=10s >/dev/null 2>&1 || {
    echo 'MISSING reachable Kubernetes context'; missing=1;
  }
fi
exit "$missing"
