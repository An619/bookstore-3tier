#!/bin/sh
# Lint every chart and render each supported profile. Failures print ::error::
# lines so they surface as CI annotations without needing the job log.
set -u
if ! command -v helm >/dev/null 2>&1; then
  echo 'helm is required to check charts' >&2
  exit 1
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failures=0

fail() {
  printf '::error::%s\n' "$*"
  failures=$((failures + 1))
}

lint() {
  chart=$1
  helm lint "$chart" > "$work/lint.log" 2>&1 || {
    sed 's/^/[helm lint] /' "$work/lint.log" >&2
    fail "helm lint failed for $chart"
  }
}

# render NAME CHART [extra --set ...]; then assert a pattern is present/absent.
render() {
  name=$1 chart=$2 expected=$3 forbidden=${4:-}
  shift 4
  out="$work/$name.yaml"
  if ! helm template "$name" "$chart" --namespace bookstore "$@" > "$out" 2> "$work/$name.err"; then
    sed 's/^/[helm template] /' "$work/$name.err" >&2
    fail "helm template failed for $chart ($name)"
    return 0
  fi
  if [ -n "$expected" ] && ! grep -q -- "$expected" "$out"; then
    fail "$chart ($name): expected to find '$expected'"
  fi
  if [ -n "$forbidden" ] && grep -q -- "$forbidden" "$out"; then
    fail "$chart ($name): did not expect '$forbidden'"
  fi
}

for chart in catalog-service orders-service inventory-service; do
  lint "helm/$chart"
  render "$chart-default" "helm/$chart" 'kind: PersistentVolumeClaim' '' \
    --set database.createSecret=true --set database.password=lab-only
  render "$chart-ephemeral" "helm/$chart" 'emptyDir: {}' 'kind: PersistentVolumeClaim' \
    --set database.createSecret=true --set database.password=lab-only \
    --set database.persistence.enabled=false
  render "$chart-storageclass" "helm/$chart" 'storageClassName: efs-sc' '' \
    --set database.createSecret=true --set database.password=lab-only \
    --set database.persistence.storageClassName=efs-sc
  render "$chart-external" "helm/$chart" 'kind: Deployment' 'kind: StatefulSet' \
    --set database.deploy=false --set database.host=external-db.example.com \
    --set database.existingSecret=catalog-db-secret
  render "$chart-light" "helm/$chart" 'memory: 128Mi' '' \
    --set database.createSecret=true --set database.password=lab-only \
    --set resources.requests.memory=128Mi --set database.storage=1Gi
done
lint helm/frontend
render frontend-default helm/frontend 'kind: Service' ''
render frontend-nodeport helm/frontend 'nodePort: 30080' '' \
  --set service.type=NodePort --set service.nodePort=30080
lint helm/bookstore
render bookstore-default helm/bookstore 'kind: Deployment' ''

if [ "$failures" -ne 0 ]; then
  echo "$failures chart check(s) failed" >&2
  exit 1
fi
echo 'All chart profiles render correctly.'
