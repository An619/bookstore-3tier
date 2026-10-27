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

# POSIX sh has no local variables: prefix everything so callers' variables
# (like the chart loop variable) are never overwritten.
lint() {
  _lint_chart=$1
  helm lint "$_lint_chart" > "$work/lint.log" 2>&1 || {
    sed 's/^/[helm lint] /' "$work/lint.log" >&2
    fail "helm lint failed for $_lint_chart"
  }
}

# render NAME CHART [extra --set ...]; then assert a pattern is present/absent.
render() {
  _name=$1
  _chart=$2
  _expected=$3
  _forbidden=${4:-}
  shift 4
  _out="$work/$_name.yaml"
  if ! helm template "$_name" "$_chart" --namespace bookstore "$@" > "$_out" 2> "$work/$_name.err"; then
    sed 's/^/[helm template] /' "$work/$_name.err" >&2
    fail "helm template failed for $_chart ($_name)"
    return 0
  fi
  if [ -n "$_expected" ] && ! grep -q -- "$_expected" "$_out"; then
    fail "$_chart ($_name): expected to find '$_expected'"
  fi
  if [ -n "$_forbidden" ] && grep -q -- "$_forbidden" "$_out"; then
    fail "$_chart ($_name): did not expect '$_forbidden'"
  fi
}

for chart in catalog-service orders-service inventory-service; do
  lint "helm/$chart"
  # StatefulSets declare volumeClaimTemplates; the PVCs themselves are created
  # by the controller, so they never appear in rendered output.
  render "$chart-default" "helm/$chart" 'volumeClaimTemplates:' 'emptyDir: {}' \
    --set database.createSecret=true --set database.password=lab-only
  render "$chart-ephemeral" "helm/$chart" 'emptyDir: {}' 'volumeClaimTemplates:' \
    --set database.createSecret=true --set database.password=lab-only \
    --set database.persistence.enabled=false
  render "$chart-storageclass" "helm/$chart" 'storageClassName: "efs-sc"' 'emptyDir: {}' \
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
