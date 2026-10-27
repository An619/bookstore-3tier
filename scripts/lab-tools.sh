#!/bin/sh
# Ensure the lab tools exist. Sourced by scripts/lab.sh; safe to run directly
# (prints what is available and where). Installs kind into .tools/bin when it is
# missing; runs Helm through Docker when Helm is not installed. No sudo, no
# writes outside the repository.
set -eu

LAB_KIND_VERSION=${LAB_KIND_VERSION:-v0.33.0}
LAB_HELM_VERSION=${LAB_HELM_VERSION:-3.17.3}
LAB_TOOLS_DIR=${LAB_TOOLS_DIR:-.tools}
LAB_KIND_SHA256_LINUX_AMD64=aee6151561422756b764a4ae28e7f44cda5af5a9eead3cc9985112b1de8d8e0d
LAB_KIND_SHA256_LINUX_ARM64=20022bee6cfcd5086cb7234d218e3454e6090022f2a8f55d1fa7fcf42c3867a2
LAB_KIND_SHA256_DARWIN_AMD64=5a99f26f57246dc9319dd294803313197a0f34d33c525b3ea8b655db5916ece0
LAB_KIND_SHA256_DARWIN_ARM64=0c8c7dbe5e23594a198b786c4bc13dacc101fa6196b0cb0b23a1ca44e61f4b4f

lab_die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
lab_info() { printf '==> %s\n' "$*"; }

# host platform names used by kind release assets
lab_platform() {
  case "$(uname -s):$(uname -m)" in
    Linux:x86_64|Linux:amd64) echo linux-amd64 ;;
    Linux:aarch64|Linux:arm64) echo linux-arm64 ;;
    Darwin:x86_64|Darwin:amd64) echo darwin-amd64 ;;
    Darwin:arm64) echo darwin-arm64 ;;
    *) lab_die "Unsupported platform $(uname -s)/$(uname -m); install kind and Helm manually." ;;
  esac
}

lab_expected_kind_sha() {
  case "$1" in
    linux-amd64) echo "$LAB_KIND_SHA256_LINUX_AMD64" ;;
    linux-arm64) echo "$LAB_KIND_SHA256_LINUX_ARM64" ;;
    darwin-amd64) echo "$LAB_KIND_SHA256_DARWIN_AMD64" ;;
    darwin-arm64) echo "$LAB_KIND_SHA256_DARWIN_ARM64" ;;
  esac
}

lab_sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    lab_die 'Neither sha256sum nor shasum is available; cannot verify downloads.'
  fi
}

# Download to a destination only if its checksum matches; never leave a
# partially downloaded or unverified binary in place.
lab_verified_download() {
  url=$1 destination=$2 expected=$3
  mkdir -p "$(dirname "$destination")"
  temporary="$destination.partial"
  rm -f "$temporary"
  curl -fsSL --retry 2 --connect-timeout 15 -o "$temporary" "$url" \
    || { rm -f "$temporary"; return 1; }
  actual=$(lab_sha256_file "$temporary")
  if [ "$actual" != "$expected" ]; then
    rm -f "$temporary"
    printf 'ERROR: checksum mismatch for %s (expected %s, got %s)\n' "$url" "$expected" "$actual" >&2
    return 1
  fi
  chmod 0755 "$temporary"
  mv "$temporary" "$destination"
}

lab_ensure_kind() {
  if command -v kind >/dev/null 2>&1; then
    printf '%s' "$(command -v kind)"
    return 0
  fi
  platform=$(lab_platform)
  binary="$LAB_TOOLS_DIR/bin/kind-$LAB_KIND_VERSION-$platform"
  if [ ! -x "$binary" ]; then
    mkdir -p "$LAB_TOOLS_DIR/bin"
    expected=${LAB_KIND_SHA256:-$(lab_expected_kind_sha "$platform")}
    url=${LAB_KIND_URL:-"https://github.com/kubernetes-sigs/kind/releases/download/$LAB_KIND_VERSION/kind-$platform"}
    lab_info "Downloading kind $LAB_KIND_VERSION ($platform)" >&2
    lab_verified_download "$url" "$binary" "$expected" \
      || lab_die "Could not download kind. Install it manually from https://kind.sigs.k8s.io/docs/user/quick-start/#installation"
  fi
  printf '%s' "$binary"
}

# Helm is executed through its pinned container image when not installed, so no
# host download or checksum management is required.
lab_ensure_helm() {
  if command -v helm >/dev/null 2>&1; then
    echo 'host'
    return 0
  fi
  command -v docker >/dev/null 2>&1 || lab_die 'Docker is required to run Helm.'
  echo 'docker'
}

lab_require_docker() {
  command -v docker >/dev/null 2>&1 || lab_die 'Docker is required. Install Docker Engine or Docker Desktop.'
  docker info >/dev/null 2>&1 || lab_die 'The Docker daemon is not reachable. Start Docker and retry.'
}

lab_require_compose() {
  docker compose version >/dev/null 2>&1 || lab_die 'Docker Compose v2 is required (docker compose ...).'
}

# SonarQube needs a larger vm.max_map_count on Linux hosts.
lab_check_sonar_kernel() {
  [ "$(uname -s)" = Linux ] || return 0
  command -v sysctl >/dev/null 2>&1 || return 0
  current=$(sysctl -n vm.max_map_count 2>/dev/null || echo 0)
  case "$current" in
    ''|*[!0-9]*) return 0 ;;
  esac
  if [ "$current" -lt 262144 ]; then
    printf 'WARNING: vm.max_map_count is %s; SonarQube needs 262144.\n' "$current" >&2
    printf '         Run: sudo sysctl -w vm.max_map_count=262144\n' >&2
  fi
}

lab_require_kubectl() {
  command -v kubectl >/dev/null 2>&1
}
