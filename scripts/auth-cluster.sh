#!/bin/sh
# Run the LAB Keycloak realm inside the Kubernetes cluster, so JWT enforcement
# can be exercised after `make lab` / `make eks-deploy` - not just in Compose.
#
#   sh scripts/auth-cluster.sh start    # create admin Secret, realm ConfigMap, Deployment
#   sh scripts/auth-cluster.sh token    # port-forward :8090 and print a demo bearer token
#   sh scripts/auth-cluster.sh secure   # redeploy orders+inventory with JWT enforcement on
#   sh scripts/auth-cluster.sh insecure # put the charts back to JWT off
#   sh scripts/auth-cluster.sh stop     # remove everything this script created
#
# The realm definition lives in deploy/local/auth/bookstore-realm.json, so the
# Compose profile and the cluster import the same users, clients and scopes.
# LAB ONLY: start-dev, plain HTTP, public demo password.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

namespace=${BOOKSTORE_NAMESPACE:-bookstore}
release_prefix=${AUTH_RELEASE_PREFIX:-}
realm_file=${AUTH_REALM_FILE:-deploy/local/auth/bookstore-realm.json}
manifest=${AUTH_MANIFEST:-deploy/kubernetes/auth/keycloak.yaml}
local_port=${AUTH_LOCAL_PORT:-8090}
jwk_set_uri=${AUTH_JWK_SET_URI:-http://keycloak:8080/realms/bookstore/protocol/openid-connect/certs}
issuer_uri=${AUTH_ISSUER_URI:-}
secrets_file=${LAB_TOOLS_DIR:-.tools}/eks/secrets.env
action=${1:-start}

info() { printf '==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
need() { for tool in "$@"; do command -v "$tool" >/dev/null 2>&1 || die "$tool is required."; done; }

# .tools is git-ignored and already holds the lab's generated passwords, so the
# admin password is reused across runs instead of being regenerated each time.
admin_password() {
  python3 scripts/lab-secrets.py ensure --file "$secrets_file" KEYCLOAK_ADMIN_PASSWORD >/dev/null
  python3 scripts/lab-secrets.py get --file "$secrets_file" --key KEYCLOAK_ADMIN_PASSWORD
}

wait_for_realm() {
  info "Waiting for the bookstore realm in deployment/keycloak"
  kubectl -n "$namespace" rollout status deployment/keycloak --timeout=300s >/dev/null
  info "Keycloak is ready inside the cluster (service keycloak:8080)"
}

start() {
  need kubectl
  [ -f "$realm_file" ] || die "$realm_file is missing."
  kubectl get namespace "$namespace" >/dev/null 2>&1 \
    || die "Namespace '$namespace' does not exist. Run 'make lab' (or eks-deploy) first."

  password=$(admin_password)
  kubectl -n "$namespace" create secret generic keycloak-admin \
    --from-literal="password=$password" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  info 'Admin Secret ready (password stored in .tools/eks/secrets.env)'

  kubectl -n "$namespace" create configmap bookstore-realm \
    --from-file="bookstore-realm.json=$realm_file" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  info "Realm ConfigMap imported from $realm_file"

  kubectl apply -f "$manifest" >/dev/null
  info "Applied $manifest"
  wait_for_realm
  printf '\nNext: sh scripts/auth-cluster.sh token, then sh scripts/auth-cluster.sh secure\n'
}

token() {
  need kubectl
  kubectl -n "$namespace" get deployment keycloak >/dev/null 2>&1 \
    || die 'Keycloak is not deployed. Run: sh scripts/auth-cluster.sh start'
  info "Port-forwarding keycloak to 127.0.0.1:$local_port"
  # The forward is backgrounded for the lifetime of the token request only.
  kubectl -n "$namespace" port-forward service/keycloak "$local_port:8080" >/dev/null 2>&1 &
  forward=$!
  trap 'kill "$forward" 2>/dev/null || true' EXIT INT TERM
  attempt=0
  while [ "$attempt" -lt 30 ]; do
    attempt=$((attempt + 1))
    if KEYCLOAK_URL="http://127.0.0.1:$local_port" sh scripts/auth-token.sh 2>/dev/null; then
      exit 0
    fi
    sleep 2
  done
  die "Keycloak did not issue a token on 127.0.0.1:$local_port"
}

# Redeploy the two enforced services with the in-cluster realm as the JWKS source.
secure() {
  need kubectl helm
  helm upgrade --install "${release_prefix}orders-service" helm/orders-service \
    --namespace "$namespace" --reuse-values \
    --set security.jwt.enabled=true \
    --set "security.jwt.jwkSetUri=$jwk_set_uri" >/dev/null
  helm upgrade --install "${release_prefix}inventory-service" helm/inventory-service \
    --namespace "$namespace" --reuse-values \
    --set security.jwt.enabled=true \
    --set "security.jwt.jwkSetUri=$jwk_set_uri" >/dev/null
  if [ -n "$issuer_uri" ]; then
    info "Issuer set locally only; the charts use the JWKS URI $jwk_set_uri"
  fi
  for workload in orders-service inventory-service; do
    kubectl -n "$namespace" rollout status "deployment/${release_prefix}${workload}" --timeout=300s >/dev/null
  done
  info 'JWT enforcement is ON. An anonymous API call should now return 401.'
  cat <<TEXT

Try it:
  kubectl -n $namespace port-forward service/<frontend-service> 8080:80 &
  curl -s -o /dev/null -w '%{http_code}\\n' http://127.0.0.1:8080/api/orders     # 401
  TOKEN=\$(sh scripts/auth-cluster.sh token)
  curl -s -H "Authorization: Bearer \$TOKEN" http://127.0.0.1:8080/api/orders     # 200
TEXT
}

insecure() {
  need kubectl helm
  for workload in orders-service inventory-service; do
    helm upgrade --install "${release_prefix}${workload}" "helm/${workload}" \
      --namespace "$namespace" --reuse-values --set security.jwt.enabled=false >/dev/null
    kubectl -n "$namespace" rollout status "deployment/${release_prefix}${workload}" --timeout=300s >/dev/null
  done
  info 'JWT enforcement is OFF again (lab default).'
}

stop() {
  need kubectl
  kubectl -n "$namespace" delete deployment keycloak --ignore-not-found >/dev/null
  kubectl -n "$namespace" delete service keycloak --ignore-not-found >/dev/null
  kubectl -n "$namespace" delete configmap bookstore-realm --ignore-not-found >/dev/null
  kubectl -n "$namespace" delete secret keycloak-admin --ignore-not-found >/dev/null
  info 'Keycloak removed from the cluster. Put the services back with: sh scripts/auth-cluster.sh insecure'
}

status() {
  need kubectl
  kubectl -n "$namespace" get deployment,service,pods -l app.kubernetes.io/name=keycloak 2>/dev/null || true
  kubectl -n "$namespace" get configmap bookstore-realm >/dev/null 2>&1 \
    && printf 'realm ConfigMap: present\n' || printf 'realm ConfigMap: missing\n'
}

case "$action" in
  start) start ;;
  token) token ;;
  secure) secure ;;
  insecure) insecure ;;
  stop) stop ;;
  status) status ;;
  -h|--help|help)
    sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
    ;;
  *) die "unknown action '$action' (start|token|secure|insecure|stop|status)" ;;
esac
