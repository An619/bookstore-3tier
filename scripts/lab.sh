#!/bin/sh
# One-command local lab for the bookstore microservices.
#
#   sh scripts/lab.sh up            # tools + cluster + images + deploy + smoke test
#   sh scripts/lab.sh up --ci       # also start Jenkins + SonarQube
#   sh scripts/lab.sh up --argocd   # deploy through Argo CD instead of Helm
#   sh scripts/lab.sh up --light    # smaller requests/storage for a 4 GB machine
#   sh scripts/lab.sh status | logs [pod] | down | url
#
# Everything runs on this machine; no cloud resources and no committed secrets.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
# shellcheck source=scripts/lab-tools.sh
. scripts/lab-tools.sh

cluster=${LAB_CLUSTER:-bookstore-lab}
namespace=${LAB_NAMESPACE:-bookstore}
http_port=${LAB_HTTP_PORT:-8080}
node_port=${LAB_NODE_PORT:-30080}
tools_dir=${LAB_TOOLS_DIR:-.tools}
secrets_file=${LAB_SECRETS_FILE:-deploy/local/.secrets/lab.env}
kubeconfig=${LAB_KUBECONFIG:-$tools_dir/kubeconfig}
revision=${LAB_REVISION:-$(git rev-parse --abbrev-ref HEAD)}
action=${1:-up}
if [ "$#" -gt 0 ]; then shift; fi

skip_build=false
light_mode=false
use_argocd=false
start_ci=false
open_browser=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --skip-build) skip_build=true ;;
    --light) light_mode=true ;;
    --argocd) use_argocd=true ;;
    --ci) start_ci=true ;;
    --revision) revision=${2:?--revision needs a value}; shift ;;
    --port) http_port=${2:?--port needs a value}; shift ;;
    --no-open) open_browser=false ;;
    --open) open_browser=true ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) lab_die "Unknown option: $1" ;;
  esac
  shift
done

mkdir -p "$tools_dir"

helm_mode=''
kind_bin=''

check_host_resources() {
  [ "$(uname -s)" = Linux ] || return 0
  if [ -r /proc/meminfo ]; then
    total_mb=$(awk '/^MemTotal:/ {print int($2/1024)}' /proc/meminfo)
    if [ "${total_mb:-0}" -lt 4500 ]; then
      printf 'WARNING: %s MB RAM detected; the full lab wants ~6 GB.\n' "$total_mb" >&2
      printf '         Use: sh scripts/lab.sh up --light\n' >&2
    fi
  fi
  cpus=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 0)
  if [ "${cpus:-0}" -lt 2 ]; then
    printf 'WARNING: %s CPU detected; image builds will be slow.\n' "$cpus" >&2
  fi
  available_kb=$(df -Pk "$root" 2>/dev/null | awk 'NR==2 {print $4}')
  if [ -n "${available_kb:-}" ] && [ "$available_kb" -lt 12000000 ]; then
    printf 'WARNING: under 12 GB free disk; image builds and volumes may fail.\n' >&2
  fi
}

setup_tools() {
  lab_require_docker
  check_host_resources
  command -v python3 >/dev/null 2>&1 || lab_die 'python3 is required.'
  command -v curl >/dev/null 2>&1 || lab_die 'curl is required to download kind.'
  kind_bin=$(lab_ensure_kind)
  helm_mode=$(lab_ensure_helm)
  # The browser-facing port must match the kind port mapping.
  mkdir -p "$(dirname "$kubeconfig")"
  generated_config="$tools_dir/kind-config.yaml"
  sed "s/containerPort: 30080/containerPort: $node_port/; s/hostPort: 8080/hostPort: $http_port/; \
       s/containerPort: 30443/containerPort: $((node_port + 63))/; s/hostPort: 8443/hostPort: $((http_port + 1))/" \
    deploy/local/kind-config.yaml > "$generated_config"
}

helm_run() {
  if [ "$helm_mode" = host ]; then
    KUBECONFIG="$kubeconfig" helm "$@"
  else
    case "$kubeconfig" in
      /*) kubeconfig_mount=$kubeconfig ;;
      *) kubeconfig_mount=$root/$kubeconfig ;;
    esac
    docker run --rm -e KUBECONFIG=/kube/config \
      -v "$root:/apps" -v "$kubeconfig_mount:/kube/config:ro" \
      -w /apps "alpine/helm:$LAB_HELM_VERSION" "$@"
  fi
}

cluster_exists() {
  "$kind_bin" get clusters 2>/dev/null | grep -qx "$cluster"
}

ensure_cluster() {
  if cluster_exists; then
    lab_info "Reusing kind cluster $cluster"
    KUBECONFIG="$kubeconfig" "$kind_bin" export kubeconfig --name "$cluster" >/dev/null
  else
    lab_info "Creating kind cluster $cluster (host port $http_port -> node port $node_port)"
    KUBECONFIG="$kubeconfig" "$kind_bin" create cluster \
      --name "$cluster" --config "$generated_config" --wait 180s
  fi
}

build_images() {
  if [ "$skip_build" = true ]; then
    lab_info 'Skipping image build (--skip-build)'
    return 0
  fi
  lab_info 'Building images from this working tree'
  docker build -q -t bookstore-frontend:local frontend >/dev/null
  for service in catalog orders inventory; do
    # Root context: the Maven build also verifies contracts/.
    docker build -q -t "bookstore-$service-service:local" \
      -f "services/$service-service/Dockerfile" . >/dev/null
  done
}

load_images() {
  lab_info 'Loading images into kind'
  "$kind_bin" load docker-image --name "$cluster" \
    bookstore-frontend:local bookstore-catalog-service:local \
    bookstore-orders-service:local bookstore-inventory-service:local >/dev/null
}

ensure_secrets() {
  python3 scripts/lab-secrets.py ensure --file "$secrets_file" \
    CATALOG_DB_PASSWORD ORDERS_DB_PASSWORD INVENTORY_DB_PASSWORD >/dev/null
  for pair in CATALOG_DB_PASSWORD:catalog-service ORDERS_DB_PASSWORD:orders-service INVENTORY_DB_PASSWORD:inventory-service; do
    key=${pair%%:*}; component=${pair#*:}
    python3 scripts/lab-secrets.py write --file "$secrets_file" --key "$key" \
      --path "$tools_dir/secrets/$key"
  done
}

# Helm chart values shared by the local lab. Passwords are supplied through
# --set-file so they never appear in the process list.
helm_upgrade() {
  release=$1 chart=$2; shift 2
  helm_run upgrade --install "$release" "helm/$chart" \
    --namespace "$namespace" --create-namespace --wait --timeout 10m "$@"
}

# Values that keep the lab within a small machine's memory and disk.
light_resource_args() {
  [ "$light_mode" = true ] || return 0
  printf '%s\n' \
    '--set' 'resources.requests.cpu=25m' \
    '--set' 'resources.requests.memory=128Mi' \
    '--set' 'resources.limits.memory=384Mi'
}

light_storage_args() {
  [ "$light_mode" = true ] || return 0
  printf '%s\n' '--set' 'database.storage=1Gi'
}

deploy_helm() {
  lab_info 'Deploying with Helm'
  # shellcheck disable=SC2046
  set -- $(light_resource_args) $(light_storage_args)
  helm_upgrade catalog-service catalog-service \
    --set database.createSecret=true \
    --set-file database.password="$tools_dir/secrets/CATALOG_DB_PASSWORD" "$@"
  helm_upgrade orders-service orders-service \
    --set database.createSecret=true \
    --set-file database.password="$tools_dir/secrets/ORDERS_DB_PASSWORD" "$@"
  helm_upgrade inventory-service inventory-service \
    --set database.createSecret=true \
    --set-file database.password="$tools_dir/secrets/INVENTORY_DB_PASSWORD" "$@"
  # Release name must match the Service DNS names used by the other charts.
  # shellcheck disable=SC2046
  set -- $(light_resource_args)
  helm_upgrade bookstore-frontend frontend \
    --set service.type=NodePort --set service.nodePort="$node_port" "$@" >/dev/null
}

deploy_argocd() {
  lab_require_kubectl || lab_die 'Argo CD deployment needs kubectl. Install kubectl, or use the default Helm path.'
  export KUBECONFIG="$kubeconfig"
  lab_info 'Installing Argo CD'
  kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl apply --server-side -n argocd -f "https://raw.githubusercontent.com/argoproj/argo-cd/${LAB_ARGOCD_VERSION:-v3.5.6}/manifests/install.yaml" >/dev/null
  kubectl -n argocd rollout status deployment/argocd-server --timeout=300s >/dev/null
  # The database Secrets and NodePort must exist before Argo CD syncs the charts.
  kubectl create namespace "$namespace" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  for pair in catalog-db-secret:CATALOG_DB_PASSWORD:cataloguser orders-db-secret:ORDERS_DB_PASSWORD:serviceuser inventory-db-secret:INVENTORY_DB_PASSWORD:serviceuser; do
    secret=${pair%%:*}; rest=${pair#*:}; key=${rest%%:*}; user=${rest#*:}
    if kubectl -n "$namespace" get secret "$secret" >/dev/null 2>&1; then
      continue
    fi
    password=$(python3 scripts/lab-secrets.py get --file "$secrets_file" --key "$key")
    kubectl -n "$namespace" create secret generic "$secret" \
      --from-literal="username=$user" --from-literal="password=$password" >/dev/null
  done
  sed -e "s|TARGET_REVISION|$revision|" deploy/local/argocd/applications.yaml > "$tools_dir/argocd-applications.yaml"
  cat >> "$tools_dir/argocd-applications.yaml" <<YAML
---
# NodePort override for the lab frontend so no port-forward is needed.
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: bookstore-frontend-lab-ports
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/An619/bookstore-3tier.git
    targetRevision: $revision
    path: helm/frontend
    helm:
      releaseName: bookstore-frontend
      parameters:
        - {name: service.type, value: NodePort}
        - {name: service.nodePort, value: "$node_port"}
  destination: {server: 'https://kubernetes.default.svc', namespace: $namespace}
  syncPolicy: {automated: {prune: true, selfHeal: true}, syncOptions: [CreateNamespace=true]}
YAML
  kubectl apply -f "$tools_dir/argocd-applications.yaml" >/dev/null
  lab_info "Waiting for Argo CD to sync branch '$revision'"
  for attempt in $(seq 1 40); do
    ready=$(kubectl -n "$namespace" get deploy --no-headers 2>/dev/null | grep -c '1/1' || true)
    [ "${ready:-0}" -ge 4 ] && break
    sleep 15
  done
}

smoke() {
  lab_info "Running smoke test against http://127.0.0.1:$http_port"
  python3 scripts/smoke-test.py --base-url "http://127.0.0.1:$http_port" --timeout 300
}

open_app() {
  [ "$open_browser" = true ] || return 0
  url="http://localhost:$http_port"
  if command -v xdg-open >/dev/null 2>&1; then xdg-open "$url" >/dev/null 2>&1 || true
  elif command -v open >/dev/null 2>&1; then open "$url" >/dev/null 2>&1 || true
  fi
}

print_summary() {
  cat <<TEXT

The application is running: http://localhost:$http_port

  make lab-verify   # prove it: pods, migrations, checkout, pod-kill recovery
  make lab-logs     # follow application logs
  make lab-down     # delete the disposable cluster and its data
  make lab-ci       # optional Jenkins + SonarQube lab
TEXT
  if [ "$use_argocd" = true ]; then
    cat <<TEXT

Argo CD UI: kubectl -n argocd port-forward svc/argocd-server 8443:443
Initial admin password: kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
TEXT
  fi
}

start_ci_lab() {
  lab_info 'Starting Jenkins + SonarQube lab'
  sh deploy/local/ci/lab.sh up
}

case "$action" in
  up)
    setup_tools
    ensure_cluster
    build_images
    load_images
    ensure_secrets
    if [ "$use_argocd" = true ]; then deploy_argocd; else deploy_helm; fi
    smoke
    print_summary
    open_app
    if [ "$start_ci" = true ]; then start_ci_lab; fi
    ;;
  down)
    if command -v kind >/dev/null 2>&1; then
      kind_bin=$(command -v kind)
    fi
    if [ -z "${kind_bin:-}" ]; then
      lab_info "kind is not installed; if a cluster named $cluster exists, remove it with 'kind delete cluster --name $cluster'"
    elif cluster_exists; then
      lab_info "Deleting kind cluster $cluster"
      "$kind_bin" delete cluster --name "$cluster"
    else
      lab_info "No kind cluster named $cluster found"
    fi
    rm -rf "$tools_dir/secrets" "$tools_dir/argocd-applications.yaml"
    ;;
  status)
    lab_require_docker
    kind_bin=$(lab_ensure_kind)
    cluster_exists && echo "cluster: $cluster (running)" || echo "cluster: $cluster (missing)"
    if cluster_exists && lab_require_kubectl; then
      KUBECONFIG="$kubeconfig" kubectl -n "$namespace" get pods,svc 2>/dev/null || true
    fi
    ;;
  logs)
    lab_require_kubectl || lab_die 'logs need kubectl.'
    KUBECONFIG="$kubeconfig" kubectl -n "$namespace" logs -f --tail=100 "${1:-deploy/bookstore-frontend}"
    ;;
  url)
    echo "http://localhost:$http_port"
    ;;
  *)
    lab_die "Unknown action '$action'. Use: up | down | status | logs | url"
    ;;
esac
