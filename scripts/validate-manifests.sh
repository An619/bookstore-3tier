#!/bin/sh
# Validate every rendered manifest against the Kubernetes JSON schemas.
#
#   sh scripts/validate-manifests.sh          # needs helm + kubeconform on PATH
#   KUBECONFORM=/path/to/kubeconform sh ...
#
# Chart templates catch typos only when Helm renders them; kubeconform catches
# the field-level mistakes Helm accepts happily (a misspelled `matchLabels`, a
# PDB without a selector, an HPA with the wrong apiVersion). Rendering and
# validating here means the pipeline fails before a cluster ever sees the file.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

kubeconform=${KUBECONFORM:-kubeconform}
kubernetes_version=${K8S_VERSION:-1.31.0}
failures=0

info() { printf '==> %s\n' "$*"; }
fail() { printf '::error::%s\n' "$*" >&2; failures=$((failures + 1)); }

for tool in helm "$kubeconform"; do
  command -v "$tool" >/dev/null 2>&1 || {
    printf 'ERROR: %s is required (install it or set KUBECONFORM)\n' "$tool" >&2
    exit 1
  }
done

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# skip: custom resources whose CRDs are not installed here; their shape is
# checked by the operator, not by this script.
validate() {
  _file=$1
  _label=$2
  if ! "$kubeconform" -strict -summary \
      -kubernetes-version "$kubernetes_version" \
      -ignore-missing-schemas \
      -skip 'ServiceMonitor,PrometheusRule,Application,SealedSecret,ExternalSecret,ClusterSecretStore' \
      "$_file" > "$work/out.log" 2>&1; then
    sed 's/^/[kubeconform] /' "$work/out.log" >&2
    fail "$_label did not validate"
    return 0
  fi
  printf '  ok   %s\n' "$_label"
}

render_and_validate() {
  _name=$1
  _chart=$2
  shift 2
  if ! helm template "$_name" "$_chart" --namespace bookstore "$@" > "$work/$_name.yaml" 2> "$work/$_name.err"; then
    sed 's/^/[helm template] /' "$work/$_name.err" >&2
    fail "$_chart ($_name) did not render"
    return 0
  fi
  validate "$work/$_name.yaml" "$_chart ($_name)"
}

info 'Rendering and validating the charts'
for chart in frontend bookstore catalog-service orders-service inventory-service; do
  render_and_validate "$chart-default" "helm/$chart"
done

info 'Validating the opt-in chart features'
for chart in catalog-service orders-service inventory-service; do
  render_and_validate "$chart-lab" "helm/$chart" \
    --set database.createSecret=true --set database.password=lab-only
  render_and_validate "$chart-ha" "helm/$chart" \
    --set database.createSecret=true --set database.password=lab-only \
    --set replicaCount=3 --set autoscaling.enabled=true \
    --set backup.enabled=true --set backup.s3.bucket=example-backups
  render_and_validate "$chart-ephemeral" "helm/$chart" \
    --set database.createSecret=true --set database.password=lab-only \
    --set database.persistence.enabled=false --set backup.enabled=true
  render_and_validate "$chart-external" "helm/$chart" \
    --set database.deploy=false --set database.host=db.example.com \
    --set database.existingSecret=db-secret
done
render_and_validate frontend-nodeport helm/frontend \
  --set service.type=NodePort --set service.nodePort=30080

info 'Validating standalone manifests'
validate deploy/kubernetes/auth/keycloak.yaml 'deploy/kubernetes/auth/keycloak.yaml'
validate monitoring/slo-rules.yaml 'monitoring/slo-rules.yaml'
if [ -f deploy/eks/optional/ebs-storageclass.yaml ]; then
  validate deploy/eks/optional/ebs-storageclass.yaml 'deploy/eks/optional/ebs-storageclass.yaml'
fi

if [ "$failures" -ne 0 ]; then
  printf '%s manifest check(s) failed\n' "$failures" >&2
  exit 1
fi
printf 'All manifests match the Kubernetes %s schemas.\n' "$kubernetes_version"
