#!/bin/sh
# Bring up the microservices on a disposable local kind cluster.
# Usage: sh deploy/local/bootstrap.sh [--argocd] [--revision BRANCH] [--skip-build]
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$root"
cluster=bookstore-lab
namespace=bookstore
revision=$(git rev-parse --abbrev-ref HEAD)
use_argocd=false
skip_build=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --argocd) use_argocd=true ;;
    --revision) revision=${2:?--revision needs a branch or tag}; shift ;;
    --skip-build) skip_build=true ;;
    -h|--help) sed -n '2,4p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done
for tool in docker kind kubectl helm python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing required tool: $tool" >&2; exit 1; }
done
docker info >/dev/null 2>&1 || { echo 'Docker daemon is not reachable' >&2; exit 1; }

if ! kind get clusters 2>/dev/null | grep -qx "$cluster"; then
  echo "Creating kind cluster $cluster..."
  kind create cluster --config deploy/local/kind-config.yaml
fi
kubectl config use-context "kind-$cluster" >/dev/null

if [ "$skip_build" = false ]; then
  echo 'Building images from the current working tree...'
  docker build -t bookstore-frontend:local frontend
  docker build -t bookstore-catalog-service:local services/catalog-service
  docker build -t bookstore-orders-service:local services/orders-service
  docker build -t bookstore-inventory-service:local services/inventory-service
fi
kind load docker-image --name "$cluster" \
  bookstore-frontend:local bookstore-catalog-service:local \
  bookstore-orders-service:local bookstore-inventory-service:local

kubectl create namespace "$namespace" --dry-run=client -o yaml | kubectl apply -f -

# Create credentials only when absent so reruns do not break a running database.
create_secret() {
  secret=$1 username=$2
  if kubectl -n "$namespace" get secret "$secret" >/dev/null 2>&1; then
    echo "Keeping existing Secret $secret"
  else
    password=$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')
    kubectl -n "$namespace" create secret generic "$secret" \
      --from-literal=username="$username" --from-literal=password="$password"
  fi
}
create_secret catalog-db-secret cataloguser
create_secret orders-db-secret serviceuser
create_secret inventory-db-secret serviceuser

if [ "$use_argocd" = true ]; then
  echo 'Installing Argo CD v3.5.6...'
  kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -
  kubectl apply --server-side -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/v3.5.6/manifests/install.yaml
  kubectl -n argocd rollout status deployment/argocd-server --timeout=300s
  sed "s|TARGET_REVISION|$revision|" deploy/local/argocd/applications.yaml | kubectl apply -f -
  echo "Argo CD is syncing branch '$revision'. Watch with: kubectl -n argocd get applications"
else
  echo 'Installing charts with Helm...'
  # Image tags/names match those loaded into kind; databases use local PVCs.
  helm upgrade --install catalog-service helm/catalog-service -n "$namespace" --wait --timeout 5m
  helm upgrade --install orders-service helm/orders-service -n "$namespace" --wait --timeout 5m
  helm upgrade --install inventory-service helm/inventory-service -n "$namespace" --wait --timeout 5m
  helm upgrade --install bookstore-frontend helm/frontend -n "$namespace" --wait --timeout 5m
fi

echo 'Waiting for application deployments...'
kubectl -n "$namespace" wait --for=condition=available --timeout=600s \
  deployment/catalog-service deployment/orders-service deployment/inventory-service
# Development-mode frontend chart name is the Helm release name.
kubectl -n "$namespace" wait --for=condition=available --timeout=600s deployment/bookstore-frontend

echo 'Running integration smoke test through a temporary port-forward...'
kubectl -n "$namespace" port-forward svc/bookstore-frontend 18080:80 >/tmp/bookstore-port-forward.log 2>&1 &
forward_pid=$!
trap 'kill "$forward_pid" 2>/dev/null || true' EXIT HUP INT TERM
python3 scripts/smoke-test.py --base-url http://127.0.0.1:18080 --timeout 120
kill "$forward_pid" 2>/dev/null || true
trap - EXIT HUP INT TERM

cat <<TEXT

Local deployment is reachable with:
  kubectl -n $namespace port-forward svc/bookstore-frontend 8080:80
  open http://localhost:8080

Teardown (deletes the disposable cluster and its data):
  kind delete cluster --name $cluster
TEXT
if [ "$use_argocd" = true ]; then
  cat <<'TEXT'
Argo CD UI (run in a separate terminal):
  kubectl -n argocd port-forward svc/argocd-server 8443:443
Initial admin password:
  kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
TEXT
fi
