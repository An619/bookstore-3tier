#!/bin/sh
# Print a bearer token from the local Keycloak dev realm.
# LAB ONLY: the demo user and password are public and must never be used
# outside a disposable environment.
set -eu
url=${KEYCLOAK_URL:-http://localhost:8090}
realm=${KEYCLOAK_REALM:-bookstore}
client=${KEYCLOAK_CLIENT:-bookstore-web}
user=${KEYCLOAK_USER:-demo}
password=${KEYCLOAK_PASSWORD:-demo-password}

response=$(curl --fail --silent --show-error \
  -d "client_id=$client" \
  -d "username=$user" \
  -d "password=$password" \
  -d "grant_type=password" \
  "$url/realms/$realm/protocol/openid-connect/token") || {
  echo "Could not reach Keycloak at $url. Start it with: make auth" >&2
  exit 1
}

token=$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
printf '%s\n' "$token"
