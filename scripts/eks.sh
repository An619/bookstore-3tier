#!/bin/sh
# Deploy the bookstore microservices to an existing EKS cluster.
#
#   sh scripts/eks.sh plan      # terraform plan (no changes)
#   sh scripts/eks.sh apply     # create the cluster, VPC, ECR repos and EBS CSI
#   sh scripts/eks.sh images    # build + push images tagged with the commit SHA
#   sh scripts/eks.sh secrets   # create the three database Secrets (kept if present)
#   sh scripts/eks.sh deploy    # helm upgrade --install from ECR images
#                               # database storage: EKS_DB_STORAGE=ephemeral (default) | pvc | external
#   sh scripts/eks.sh release   # write a reviewed Argo CD manifest for this SHA
#   sh scripts/eks.sh uninstall # remove Helm releases + the load balancer (run before destroy)
#   sh scripts/eks.sh status    # cluster, pods and the frontend address
#   sh scripts/eks.sh destroy   # terraform destroy (asks you to type a confirmation)
#
# Billing: apply creates billable AWS resources. destroy removes them.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

namespace=${EKS_NAMESPACE:-bookstore}
db_storage=${EKS_DB_STORAGE:-ephemeral}
cluster=${EKS_CLUSTER:-bookstore-lab-eks}
region=${AWS_REGION:-ap-south-1}
project=${EKS_PROJECT:-bookstore}
environment=${EKS_ENVIRONMENT:-lab}
revision=$(git rev-parse --short=12 HEAD)
tools_dir=${LAB_TOOLS_DIR:-.tools}
secrets_file=${EKS_SECRETS_FILE:-$tools_dir/eks/secrets.env}
tf_dir=${EKS_TF_DIR:-terraform/eks}
action=${1:-status}

info() { printf '==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

need() {
  for tool in "$@"; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool is required for '$action'. Install it and retry."
  done
}

registry() {
  need aws
  account=$(aws sts get-caller-identity --query Account --output text 2>/dev/null) \
    || die 'AWS credentials are not configured (aws sts get-caller-identity failed).'
  [ -n "$account" ] || die 'Could not determine the AWS account.'
  echo "$account.dkr.ecr.$region.amazonaws.com"
}

# A subshell cannot abort the parent on failure, so callers validate the result.
require_registry() {
  value=$(registry) || exit 1
  case "$value" in
    ''|*[!0-9a-z.-]*) die 'Could not resolve the ECR registry for this account.' ;;
  esac
  echo "$value"
}

confirm_apply() {
  [ "${EKS_AUTO_APPROVE:-false}" = true ] && return 0
  printf 'Type %s to create billable AWS resources in %s: ' "$cluster" "$region"
  read -r confirmation
  [ "$confirmation" = "$cluster" ] || die 'Confirmation did not match; nothing was created.'
}

# ECR repositories are created by Terraform; fail early with a clear message.
check_repositories() {
  reg=$1
  for component in frontend catalog-service orders-service inventory-service; do
    repository="$project-$environment-$component"
    aws ecr describe-repositories --region "$region" --repository-names "$repository" >/dev/null 2>&1 \
      || die "ECR repository $repository is missing. Run: sh scripts/eks.sh apply"
  done
}

kubeconfig() {
  need aws kubectl
  aws eks update-kubeconfig --region "$region" --name "$cluster" >/dev/null 2>&1 \
    || die "Could not write a kubeconfig for $cluster. Does it exist and is your IAM role allowed to describe it?"
}

# Translate EKS_DB_STORAGE into chart values. EBS is never required:
#   ephemeral (default) - emptyDir, no StorageClass or CSI driver, data is lost
#                         when a database pod is replaced
#   pvc                 - use an existing StorageClass you name (EBS, EFS, ...)
#   external            - no database pods at all; point the services at
#                         managed PostgreSQL hosts
validate_storage_config() {
  case "$db_storage" in
    ephemeral) ;;
    pvc)
      [ -n "${EKS_STORAGE_CLASS:-}" ] \
        || die 'EKS_DB_STORAGE=pvc needs EKS_STORAGE_CLASS=<existing class> (for example efs-sc). No StorageClass is created for you.'
      ;;
    external)
      [ -n "${EKS_DB_HOST_CATALOG:-}" ] && [ -n "${EKS_DB_HOST_ORDERS:-}" ] \
        && [ -n "${EKS_DB_HOST_INVENTORY:-}" ] \
        || die 'EKS_DB_STORAGE=external needs EKS_DB_HOST_CATALOG, EKS_DB_HOST_ORDERS and EKS_DB_HOST_INVENTORY.'
      ;;
    *)
      die "Unknown EKS_DB_STORAGE '$db_storage'. Use ephemeral, pvc or external."
      ;;
  esac
}

# Per-chart values for the chosen storage mode. EBS is never required.
database_args() {
  component=$1
  case "$db_storage" in
    ephemeral)
      # emptyDir: no StorageClass or CSI driver; data is lost when the pod is
      # replaced. Fine for a lab, not for data you care about.
      set -- '--set' 'database.persistence.enabled=false'
      ;;
    pvc)
      set -- '--set' 'database.persistence.enabled=true' \
        '--set' "database.persistence.storageClassName=$EKS_STORAGE_CLASS"
      ;;
    external)
      case "$component" in
        catalog-service) host=${EKS_DB_HOST_CATALOG:-} ;;
        orders-service) host=${EKS_DB_HOST_ORDERS:-} ;;
        inventory-service) host=${EKS_DB_HOST_INVENTORY:-} ;;
      esac
      set -- '--set' 'database.deploy=false' '--set' "database.host=${host:-}"
      ;;
  esac
  printf '%s\n' "$@"
}

ensure_secrets() {
  if [ "$db_storage" = external ]; then
    info 'Using an external database: expecting Secrets catalog-db-secret, orders-db-secret and inventory-db-secret to exist already'
    for secret in catalog-db-secret orders-db-secret inventory-db-secret; do
      kubectl -n "$namespace" get secret "$secret" >/dev/null 2>&1 \
        || die "Secret $secret is missing; create it with username/password keys for your managed database."
    done
    return 0
  fi
  python3 scripts/lab-secrets.py ensure --file "$secrets_file" \
    CATALOG_DB_PASSWORD ORDERS_DB_PASSWORD INVENTORY_DB_PASSWORD >/dev/null
  kubectl create namespace "$namespace" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  for pair in catalog-db-secret:CATALOG_DB_PASSWORD:cataloguser \
              orders-db-secret:ORDERS_DB_PASSWORD:serviceuser \
              inventory-db-secret:INVENTORY_DB_PASSWORD:serviceuser; do
    secret=${pair%%:*}; rest=${pair#*:}; key=${rest%%:*}; user=${rest#*:}
    if kubectl -n "$namespace" get secret "$secret" >/dev/null 2>&1; then
      info "Keeping existing Secret $secret"
      continue
    fi
    password=$(python3 scripts/lab-secrets.py get --file "$secrets_file" --key "$key")
    kubectl -n "$namespace" create secret generic "$secret" \
      --from-literal="username=$user" --from-literal="password=$password" >/dev/null
    info "Created Secret $secret"
  done
}

helm_deploy() {
  need helm
  reg=$(require_registry)
  check_repositories "$reg"
  prefix="$project-$environment-"
  info "Deploying commit $revision from $reg (database storage: $db_storage)"
  for component in catalog-service orders-service inventory-service; do
    # shellcheck disable=SC2046
    set -- $(database_args "$component")
    helm upgrade --install "$component" "helm/$component" --namespace "$namespace" --create-namespace \
      --set "image.repository=$reg/$prefix$component" --set "image.tag=$revision" \
      --set-string image.pullPolicy=IfNotPresent --wait --timeout 10m "$@"
  done
  # The frontend is the only public entry point.
  helm upgrade --install bookstore-frontend helm/frontend --namespace "$namespace" \
    --set "image.repository=$reg/${prefix}frontend" --set "image.tag=$revision" \
    --set service.type=LoadBalancer --wait --timeout 10m
}

case "$action" in
  plan)
    need terraform
    terraform -chdir="$tf_dir" init -input=false >/dev/null
    terraform -chdir="$tf_dir" plan -input=false -out=tfplan
    ;;
  apply)
    need terraform
    [ -f "$tf_dir/terraform.tfvars" ] || die "$tf_dir/terraform.tfvars is missing. Copy terraform.tfvars.example and set the EKS version and your IP /32."
    info 'This creates billable AWS resources: VPC, EKS nodes, NAT gateway and ECR repositories.'
    confirm_apply
    terraform -chdir="$tf_dir" init -input=false
    terraform -chdir="$tf_dir" apply -input=false -auto-approve
    kubeconfig
    info 'Cluster ready. Next: sh scripts/eks.sh images'
    ;;
  images)
    need docker aws
    reg=$(registry)
    check_repositories "$reg"
    aws ecr get-login-password --region "$region" \
      | docker login --username AWS --password-stdin "$reg" >/dev/null
    prefix="$project-$environment-"
    for component in "frontend:frontend" "catalog-service:services/catalog-service" \
                     "orders-service:services/orders-service" "inventory-service:services/inventory-service"; do
      name=${component%%:*}; path=${component#*:}
      image="$reg/$prefix$name:$revision"
      info "Building $image"
      if [ "$name" = frontend ]; then
        docker build -q -t "$image" "$path" >/dev/null
      else
        # Root context: the Maven build also verifies contracts/.
        docker build -q -t "$image" -f "$path/Dockerfile" . >/dev/null
      fi
      docker push "$image" >/dev/null
    done
    info "Pushed 4 images tagged $revision"
    ;;
  secrets)
    kubeconfig
    ensure_secrets
    ;;
  deploy)
    kubeconfig
    validate_storage_config
    ensure_secrets
    helm_deploy
    kubectl -n "$namespace" rollout status deployment/catalog-service --timeout=600s
    kubectl -n "$namespace" rollout status deployment/orders-service --timeout=600s
    kubectl -n "$namespace" rollout status deployment/inventory-service --timeout=600s
    kubectl -n "$namespace" rollout status deployment/bookstore-frontend --timeout=600s
    info 'Run: sh scripts/eks.sh status'
    ;;
  release)
    reg=$(require_registry)
    prefix="$project-$environment-"
    output=${EKS_RELEASE_OUTPUT:-deploy/releases/bookstore-$environment.json}
    python3 scripts/prepare-release.py --registry "$reg" --repository-prefix "$prefix" \
      --revision "$revision" --branch "$(git rev-parse --abbrev-ref HEAD)" --output "$output"
    ;;
  verify)
    kubeconfig
    need kubectl
    info 'Waiting for the frontend load balancer to get an address'
    address=''
    # Ten minutes by default; EKS_LB_WAIT_ATTEMPTS exists for tests.
    for _ in $(seq 1 "${EKS_LB_WAIT_ATTEMPTS:-60}"); do
      address=$(kubectl -n "$namespace" get service bookstore-frontend \
        -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)
      [ -n "$address" ] && break
      sleep 10
    done
    [ -n "$address" ] || die 'The frontend Service has no load-balancer address yet. Check: kubectl -n '"$namespace"' describe service bookstore-frontend'
    info "Verifying https://$address"
    # The AWS load balancer needs a moment before it routes traffic.
    # EKS_VERIFY_ARGS forwards flags such as --skip-smoke or --max-restarts 2.
    # shellcheck disable=SC2086
    sh scripts/verify-deploy.sh --namespace "$namespace" --url "http://$address" \
      --timeout 600 ${EKS_VERIFY_ARGS:-}
    ;;
  status)
    kubeconfig
    kubectl -n "$namespace" get deploy,pods,svc 2>/dev/null || true
    info 'Frontend address (may take a few minutes after deploy):'
    kubectl -n "$namespace" get service bookstore-frontend \
      -o jsonpath='{.status.loadBalancer.ingress[0].hostname}{"\n"}' 2>/dev/null || true
    ;;
  uninstall)
    kubeconfig
    if ! kubectl -n "$namespace" get deploy >/dev/null 2>&1; then
      info "No workloads found in namespace $namespace (already removed)"
      exit 0
    fi
    need helm
    for release in bookstore-frontend inventory-service orders-service catalog-service; do
      if helm status "$release" --namespace "$namespace" >/dev/null 2>&1; then
        info "Uninstalling $release"
        helm uninstall "$release" --namespace "$namespace" --wait --timeout 5m || true
      fi
    done
    # A LoadBalancer Service can leave an ELB behind for a short time after
    # deletion; wait so terraform destroy does not orphan paid resources.
    attempt=1
    while kubectl -n "$namespace" get service bookstore-frontend >/dev/null 2>&1; do
      [ "$attempt" -ge 30 ] && { info 'frontend Service still deleting; check the AWS console for a leftover ELB'; break; }
      attempt=$((attempt + 1))
      sleep 10
    done
    info 'Workloads removed. Next: sh scripts/eks.sh destroy'
    ;;

  destroy)
    need terraform
    printf 'Type %s to destroy the %s cluster and ALL of its data: ' "$cluster" "$cluster"
    read -r confirmation
    [ "$confirmation" = "$cluster" ] || die 'Confirmation did not match; nothing was destroyed.'
    terraform -chdir="$tf_dir" destroy -input=false -auto-approve
    ;;
  *)
    die "Unknown action '$action'. Use: plan | apply | images | secrets | deploy | release | status | destroy"
    ;;
esac
