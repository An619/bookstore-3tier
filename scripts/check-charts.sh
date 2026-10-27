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
  render "$chart-default" "helm/$chart" 'storage-mode: persistent' 'storage-mode: ephemeral' \
    --set database.createSecret=true --set database.password=lab-only
  render "$chart-ephemeral" "helm/$chart" 'storage-mode: ephemeral' 'volumeClaimTemplates:' \
    --set database.createSecret=true --set database.password=lab-only \
    --set database.persistence.enabled=false
  render "$chart-storageclass" "helm/$chart" 'storageClassName: "efs-sc"' 'storage-mode: ephemeral' \
    --set database.createSecret=true --set database.password=lab-only \
    --set database.persistence.storageClassName=efs-sc
  render "$chart-external" "helm/$chart" 'kind: Deployment' 'storage-mode:' \
    --set database.deploy=false --set database.host=external-db.example.com \
    --set database.existingSecret=catalog-db-secret
  render "$chart-light" "helm/$chart" 'memory: 128Mi' '' \
    --set database.createSecret=true --set database.password=lab-only \
    --set resources.requests.memory=128Mi --set database.storage=1Gi
done
# Observability and authorization switches.
for chart in catalog-service orders-service inventory-service; do
  render "$chart-tracing" "helm/$chart" 'OTEL_SAMPLING_PROBABILITY' '' \
    --set database.createSecret=true --set database.password=lab-only \
    --set observability.tracing.endpoint=http://otel-collector.monitoring:4318/v1/traces \
    --set observability.tracing.samplingProbability=0.1
done
# The lab must stay usable without an identity provider: no token requirement,
# and no half-configured security block.
for chart in catalog-service orders-service inventory-service; do
  render "$chart-anonymous" "helm/$chart" '' 'SECURITY_JWT_ENABLED' \
    --set database.createSecret=true --set database.password=lab-only
done
for chart in orders-service inventory-service; do
  render "$chart-secured" "helm/$chart" 'SECURITY_JWT_ISSUER_URI' '' \
    --set database.createSecret=true --set database.password=lab-only \
    --set security.jwt.enabled=true \
    --set security.jwt.issuerUri=https://issuer.example.com/realms/bookstore
done
# Managed PostgreSQL: TLS and pool limits reach the container.
render orders-tls helm/orders-service 'sslmode=require' '' \
  --set database.createSecret=true --set database.password=lab-only \
  --set database.sslmode=require --set database.hikari.maximumPoolSize=25
render orders-pool helm/orders-service 'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE' ''
# Checkout alerts are opt-in.
render orders-alerts-on helm/orders-service 'CheckoutOutboxBacklog' '' \
  --set database.createSecret=true --set database.password=lab-only \
  --set alerts.enabled=true
render orders-alerts-off helm/orders-service '' 'CheckoutOutboxBacklog' \
  --set database.createSecret=true --set database.password=lab-only

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
