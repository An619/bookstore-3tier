#!/bin/sh
# Post-deploy verification: prove a deployed stack is actually healthy.
#
#   sh scripts/verify-deploy.sh                     # kind lab on localhost:8080
#   sh scripts/verify-deploy.sh --url http://host   # any reachable entry point
#   sh scripts/verify-deploy.sh --resilience        # also kill a pod and retest
#
# Checks, in order:
#   1. every workload finishes its rollout;
#   2. no pod is in CrashLoopBackOff, unready, or restarting excessively;
#   3. Flyway applied the expected migration version in every service database
#      that runs in the cluster (skipped for externally managed databases);
#   4. the end-to-end checkout smoke test passes through the public entry point;
#   5. optional: deleting the orders pod does not lose the API.
#
# Exit code 0 means every executed check passed. Nothing is created or destroyed
# beyond the disposable data the smoke test makes and cleans up.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

namespace=${VERIFY_NAMESPACE:-bookstore}
base_url=${VERIFY_URL:-http://localhost:8080}
expected_flyway=${EXPECTED_FLYWAY_VERSION:-2}
max_restarts=${VERIFY_MAX_RESTARTS:-0}
timeout=${VERIFY_TIMEOUT:-300}
do_smoke=true
do_resilience=false

usage() {
  cat <<'TEXT'
Usage: sh scripts/verify-deploy.sh [options]

  --url URL             entry point to test        (default http://localhost:8080)
  --namespace NAME      Kubernetes namespace       (default bookstore)
  --flyway-version N    expected last migration    (default 2)
  --max-restarts N      tolerated container restarts (default 0)
  --timeout SECONDS     per-step timeout           (default 300)
  --skip-smoke          skip the checkout smoke test
  --resilience          delete the orders pod and retest the API
  -h, --help            this text
TEXT
}

info() { printf '==> %s\n' "$*"; }
ok() { printf '  ok   %s\n' "$*"; }
bad() { printf '  FAIL %s\n' "$*" >&2; failures=$((failures + 1)); }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case $1 in
    --url) base_url=${2:?--url needs a value}; shift ;;
    --namespace|-n) namespace=${2:?--namespace needs a value}; shift ;;
    --flyway-version) expected_flyway=${2:?--flyway-version needs a value}; shift ;;
    --max-restarts) max_restarts=${2:?--max-restarts needs a value}; shift ;;
    --timeout) timeout=${2:?--timeout needs a value}; shift ;;
    --skip-smoke) do_smoke=false ;;
    --resilience) do_resilience=true ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
  shift
done

base_url=${base_url%/}
command -v kubectl >/dev/null 2>&1 || die 'kubectl is required to verify a cluster deployment.'
command -v python3 >/dev/null 2>&1 || die 'python3 is required (the smoke test uses it).'

failures=0

check_rollouts() {
  info "Waiting for rollouts in namespace '$namespace' (timeout ${timeout}s)"
  for workload in catalog-service orders-service inventory-service bookstore-frontend; do
    if kubectl -n "$namespace" rollout status "deployment/$workload" --timeout="${timeout}s" >/dev/null 2>&1; then
      ok "deployment/$workload is rolled out"
    elif kubectl -n "$namespace" get "deployment/$workload" >/dev/null 2>&1; then
      bad "deployment/$workload did not finish its rollout"
      kubectl -n "$namespace" get pods -l "app.kubernetes.io/name=$workload" 2>/dev/null || true
    else
      bad "deployment/$workload does not exist"
    fi
  done
}

# Pod-level health, including restart counts that a rollout status call hides.
check_pods() {
  info 'Checking pod readiness and restart counts'
  kubectl -n "$namespace" get pods -o json 2>/dev/null | python3 -c '
import json, sys

max_restarts = int(sys.argv[1])
namespace = sys.argv[2]
try:
    pods = json.load(sys.stdin).get("items", [])
except ValueError:
    print("  FAIL could not read pods as JSON")
    sys.exit(1)

problems = []
for pod in pods:
    name = pod["metadata"]["name"]
    phase = pod.get("status", {}).get("phase", "Unknown")
    statuses = pod.get("status", {}).get("containerStatuses", []) or []
    if phase not in ("Running", "Succeeded") and not name.endswith("-postgres-0"):
        problems.append(f"{name} is {phase}")
        continue
    if not statuses:
        problems.append(f"{name} has no container status yet")
        continue
    for status in statuses:
        restarts = status.get("restartCount", 0)
        container = status.get("name", "?")
        waiting = (status.get("state", {}).get("waiting") or {}).get("reason")
        if waiting and waiting not in ("ContainerCreating", "PodInitializing"):
            problems.append(f"{name}/{container} is waiting: {waiting}")
        if not status.get("ready", False) and phase == "Running":
            problems.append(f"{name}/{container} is not ready")
        if restarts > max_restarts:
            problems.append(f"{name}/{container} restarted {restarts} times (allowed {max_restarts})")

if problems:
    for problem in problems:
        print(f"  FAIL {problem}")
    print(f"  FAIL {len(problems)} pod problem(s) in namespace {namespace}")
    sys.exit(1)
print(f"  ok   {len(pods)} pods healthy, no crash loops, restarts <= {max_restarts}")
' "$max_restarts" "$namespace" || failures=$((failures + 1))
}

# Flyway owns the schema, so the migration history is part of "it works".
check_flyway() {
  info "Checking Flyway drift in the service databases (expected V$expected_flyway)"
  for target in "catalog-service-postgres-0 cataloguser catalogdb" \
                "orders-service-postgres-0 serviceuser ordersdb" \
                "inventory-service-postgres-0 serviceuser inventorydb"; do
    set -- $target
    pod=$1 user=$2 db=$3
    if ! kubectl -n "$namespace" get pod "$pod" >/dev/null 2>&1; then
      ok "$pod does not run here (externally managed database) - skipped"
      continue
    fi
    history=$(kubectl -n "$namespace" exec "$pod" -- \
      psql -U "$user" -d "$db" -tAc \
      'SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank' 2>/dev/null \
      | tr -d '\r' || true)
    applied=$(printf '%s\n' "$history" | sed -n '/[0-9]/p' | tail -n 1 | tr -d '[:space:]')
    if [ "$applied" = "$expected_flyway" ]; then
      ok "$db applied V$applied"
    else
      bad "$db reports Flyway version '${applied:-none}' (expected $expected_flyway)"
    fi
  done
}

check_smoke() {
  info "Running the end-to-end smoke test against $base_url"
  if python3 scripts/smoke-test.py --base-url "$base_url" --timeout "$timeout"; then
    ok 'checkout saga: 202, CONFIRMED, idempotent replay, compensation'
  else
    bad 'the smoke test failed (see the output above)'
  fi
}

# A self-healing system is the point of Kubernetes: prove the API survives a kill.
check_resilience() {
  info 'Resilience: deleting the orders pod and retesting the API'
  before=$(kubectl -n "$namespace" get pods -l app.kubernetes.io/name=orders-service \
    --no-headers 2>/dev/null | wc -l | tr -d ' ')
  if [ "${before:-0}" -eq 0 ]; then
    bad 'no orders-service pod to delete; is the workload running?'
    return 0
  fi
  if ! kubectl -n "$namespace" delete pod -l app.kubernetes.io/name=orders-service --wait=false >/dev/null 2>&1; then
    bad 'could not delete the orders-service pod'
    return 0
  fi
  if kubectl -n "$namespace" rollout status deployment/orders-service --timeout="${timeout}s" >/dev/null 2>&1; then
    ok 'orders-service recovered a replacement pod'
  else
    bad 'orders-service did not become ready after the pod was deleted'
    return 0
  fi
  if [ "$do_smoke" = true ]; then
    info 'Re-checking readiness after the restart'
    if python3 scripts/smoke-test.py --base-url "$base_url" --timeout 120 --check-only; then
      ok 'the API still serves data after the pod was killed'
    else
      bad 'the API stopped serving data after the pod restart'
    fi
  fi
}

info "Verifying namespace '$namespace' through $base_url"
check_rollouts
check_pods
check_flyway
if [ "$do_smoke" = true ]; then
  check_smoke
else
  info 'Skipping the smoke test (--skip-smoke)'
fi
if [ "$do_resilience" = true ]; then
  check_resilience
fi

if [ "$failures" -ne 0 ]; then
  printf '\nFAILED: %s check(s) failed.\n' "$failures" >&2
  printf 'Diagnose with: kubectl -n %s get pods;kubectl -n %s describe pod <pod>\n' \
    "$namespace" "$namespace" >&2
  exit 1
fi
printf '\nPASS: deployment verified (workloads, pods, migrations, checkout, resilience=%s).\n' \
  "$do_resilience"
