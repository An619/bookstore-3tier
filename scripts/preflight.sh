#!/bin/sh
# Report which tools are available locally. Never reads or prints credential
# values. Exits nonzero when a tool needed by the local lab is missing.
set -eu
missing=0
for tool in git docker python3 curl; do
  if command -v "$tool" >/dev/null 2>&1; then
    printf 'OK      %s\n' "$tool"
  else
    printf 'MISSING %s (required)\n' "$tool"
    missing=1
  fi
done
for tool in kind helm kubectl java mvn node npm; do
  if command -v "$tool" >/dev/null 2>&1; then
    printf 'OK      %s\n' "$tool"
  else
    case "$tool" in
      kind) printf 'AUTO    kind (downloaded, checksum-verified, by make lab)\n' ;;
      helm) printf 'AUTO    helm (runs from a pinned container image)\n' ;;
      kubectl) printf 'OPTIONAL %s (needed for make lab-argocd, logs and status)\n' "$tool" ;;
      *) printf 'OPTIONAL %s (needed for make verify and the Jenkins pipeline)\n' "$tool" ;;
    esac
  fi
done
if command -v docker >/dev/null 2>&1; then
  docker info >/dev/null 2>&1 || { echo 'MISSING running Docker daemon'; missing=1; }
  docker compose version >/dev/null 2>&1 || { echo 'MISSING Docker Compose v2'; missing=1; }
fi
if [ "$missing" -ne 0 ]; then
  echo
  echo 'Install the required tools, then run: make lab'
fi
exit "$missing"
