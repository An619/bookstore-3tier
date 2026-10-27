#!/bin/sh
# Deploy the bookstore microservices to an existing EKS cluster.
#
#   sh scripts/eks.sh plan      # terraform plan (no changes)
#   sh scripts/eks.sh apply     # create the cluster, VPC, ECR repos and EBS CSI
#   sh scripts/eks.sh images    # build + push images tagged with the commit SHA
#   sh scripts/eks.sh secrets   # create the three database Secrets (kept if present)
#   sh scripts/eks.sh deploy    # helm upgrade --install from ECR images
#   sh scripts/eks.sh release   # write a reviewed Argo CD manifest for this SHA
#   sh scripts/eks.sh status    # cluster, pods and the frontend address
#   sh scripts/eks.sh destroy   # terraform destroy (asks you to type a confirmation)
#
# Billing: apply creates billable AWS resources. destroy removes them.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

namespace=${EKS_NAMESPACE:-bookstore}
cluster=${EKS_CLUSTER:-bookstore-lab-eks}
region=${AWS_REGION:-ap-south-1}
project=${EKS_PROJECT:-bookstore}
environment=${EKS_ENVIRONMENT:-lab}
revision=$(git rev-parse --short=12 HEAD)
tools_dir=${LAB_TOOLS_DIR:-.tools}
secrets_file=${EKS_SECRETS_FILE:-$tools_dir/eks/secrets.env}
tf_dir=terraform/eks
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

ensure_storage() {
  # A default gp3 StorageClass backed by the EBS CSI driver; PostgreSQL PVCs
  # stay Pending without it.
  kubectl apply -f deploy/eks/storageclass.yaml >/dev/null
  kubectl get storageclass gp3 -o jsonpath='{.metadata.annotations.storageclass\.kubernetes\.io/is-default-class}' 2>/dev/null \
    | grep -q true || info 'note: gp3 is the lab default; existing default StorageClasses were left untouched'
}

ensure_secrets() {
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
  reg=$(registry)
  check_repositories "$reg"
  prefix="$project-$environment-"
  info "Deploying commit $revision from $reg"
  for component in catalog-service orders-service inventory-service; do
    helm upgrade --install "$component" "helm/$component" --namespace "$namespace" --create-namespace \
      --set "image.repository=$reg/$prefix$component" --set "image.tag=$revision" \
      --set-string image.pullPolicy=IfNotPresent --wait --timeout 10m
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
    info 'This creates billable AWS resources: VPC, EKS nodes, NAT gateway and ECR repositories.'
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
      docker build -q -t "$image" "$path" >/dev/null
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
    ensure_storage
    ensure_secrets
    helm_deploy
    kubectl -n "$namespace" rollout status deployment/catalog-service --timeout=600s
    kubectl -n "$namespace" rollout status deployment/orders-service --timeout=600s
    kubectl -n "$namespace" rollout status deployment/inventory-service --timeout=600s
    kubectl -n "$namespace" rollout status deployment/bookstore-frontend --timeout=600s
    info 'Run: sh scripts/eks.sh status'
    ;;
  release)
    reg=$(registry)
    prefix="$project-$environment-"
    output=${EKS_RELEASE_OUTPUT:-deploy/releases/bookstore-$environment.json}
    python3 scripts/prepare-release.py --registry "$reg" --repository-prefix "$prefix" \
      --revision "$revision" --branch "$(git rev-parse --abbrev-ref HEAD)" --output "$output"
    ;;
  status)
    kubeconfig
    kubectl -n "$namespace" get deploy,pods,svc 2>/dev/null || true
    info 'Frontend address (may take a few minutes after deploy):'
    kubectl -n "$namespace" get service bookstore-frontend \
      -o jsonpath='{.status.loadBalancer.ingress[0].hostname}{"\n"}' 2>/dev/null || true
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
