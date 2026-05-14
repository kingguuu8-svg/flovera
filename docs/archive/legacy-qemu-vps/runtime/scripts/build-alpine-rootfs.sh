#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Build an Alpine minimal rootfs for the AI Linux workspace.

Usage:
  bash scripts/build-alpine-rootfs.sh [options]

Options:
  --arch ARCH       Alpine architecture. Default: host architecture.
                    Supported baseline values: x86_64, aarch64.
  --out DIR         Output rootfs directory. Default: artifacts/rootfs/alpine-ARCH.
  --force           Remove an existing output directory under artifacts/rootfs.
  --skip-packages   Only extract the minirootfs; do not chroot or install packages.
  -h, --help        Show this help.

Environment:
  ALPINE_MIRROR     Default: https://dl-cdn.alpinelinux.org

Notes:
  Package installation uses chroot and therefore requires root on the host.
  Cross-architecture package installation requires qemu-user-static/binfmt.
USAGE
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing required command: $1"
}

host_arch() {
  case "$(uname -m)" in
    x86_64 | amd64) printf 'x86_64' ;;
    aarch64 | arm64) printf 'aarch64' ;;
    *) uname -m ;;
  esac
}

safe_remove_output() {
  local path="$1"
  local root="$2"
  local resolved_path
  local resolved_root

  resolved_path="$(realpath -m "$path")"
  resolved_root="$(realpath -m "$root")"

  case "$resolved_path" in
    "$resolved_root"/*) ;;
    *) fail "refusing to remove path outside artifacts/rootfs: $resolved_path" ;;
  esac

  rm -rf -- "$resolved_path"
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIRROR="${ALPINE_MIRROR:-https://dl-cdn.alpinelinux.org}"
ARCH="$(host_arch)"
OUT_DIR=""
FORCE=0
SKIP_PACKAGES=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --arch)
      [ "$#" -ge 2 ] || fail "--arch requires a value"
      ARCH="$2"
      shift 2
      ;;
    --out)
      [ "$#" -ge 2 ] || fail "--out requires a value"
      OUT_DIR="$2"
      shift 2
      ;;
    --force)
      FORCE=1
      shift
      ;;
    --skip-packages)
      SKIP_PACKAGES=1
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

case "$ARCH" in
  x86_64 | aarch64) ;;
  *) fail "unsupported arch for this phase: $ARCH" ;;
esac

OUT_DIR="${OUT_DIR:-$REPO_ROOT/artifacts/rootfs/alpine-$ARCH}"
DOWNLOAD_DIR="$REPO_ROOT/artifacts/downloads"
REPORT_DIR="$REPO_ROOT/artifacts/reports"
TARBALL_DIR="$REPO_ROOT/artifacts/tarballs"
PACKAGES_FILE="$REPO_ROOT/rootfs/alpine/packages.txt"
ARTIFACT_ROOT="$REPO_ROOT/artifacts/rootfs"

require_cmd curl
require_cmd tar
require_cmd sha256sum
require_cmd awk
require_cmd realpath

[ -f "$PACKAGES_FILE" ] || fail "missing packages file: $PACKAGES_FILE"

HOST_ARCH="$(host_arch)"
if [ "$SKIP_PACKAGES" -eq 0 ]; then
  [ "$(id -u)" -eq 0 ] || fail "package installation requires root"
  require_cmd chroot
  if [ "$ARCH" != "$HOST_ARCH" ]; then
    case "$ARCH" in
      aarch64)
        require_cmd qemu-aarch64-static
        ;;
      *)
        fail "cross-arch chroot is not configured for arch: $ARCH"
        ;;
    esac
  fi
fi

mkdir -p "$DOWNLOAD_DIR" "$REPORT_DIR" "$TARBALL_DIR" "$ARTIFACT_ROOT"

METADATA_URL="$MIRROR/alpine/latest-stable/releases/$ARCH/latest-releases.yaml"
METADATA="$(curl -fsSL "$METADATA_URL")"
BRANCH="$(printf '%s\n' "$METADATA" | awk '/branch: / {branch=$2} /flavor: alpine-minirootfs/ {print branch; exit}')"
VERSION="$(printf '%s\n' "$METADATA" | awk '/version: / {version=$2} /flavor: alpine-minirootfs/ {print version; exit}')"
FILE="$(printf '%s\n' "$METADATA" | awk '/file: alpine-minirootfs-/ {print $2; exit}')"
SHA256="$(printf '%s\n' "$METADATA" | awk '/flavor: alpine-minirootfs/ {found=1} found && /sha256: / {print $2; exit}')"

[ -n "$BRANCH" ] || fail "failed to parse Alpine branch from $METADATA_URL"
[ -n "$VERSION" ] || fail "failed to parse Alpine version from $METADATA_URL"
[ -n "$FILE" ] || fail "failed to parse Alpine minirootfs file from $METADATA_URL"
[ -n "$SHA256" ] || fail "failed to parse Alpine minirootfs sha256 from $METADATA_URL"

DOWNLOAD_URL="$MIRROR/alpine/$BRANCH/releases/$ARCH/$FILE"
DOWNLOAD_PATH="$DOWNLOAD_DIR/$FILE"

if [ ! -f "$DOWNLOAD_PATH" ] || ! printf '%s  %s\n' "$SHA256" "$DOWNLOAD_PATH" | sha256sum -c - >/dev/null 2>&1; then
  curl -fL "$DOWNLOAD_URL" -o "$DOWNLOAD_PATH"
fi

printf '%s  %s\n' "$SHA256" "$DOWNLOAD_PATH" | sha256sum -c -

if [ -e "$OUT_DIR" ]; then
  [ "$FORCE" -eq 1 ] || fail "output directory already exists: $OUT_DIR; pass --force to replace it"
  safe_remove_output "$OUT_DIR" "$ARTIFACT_ROOT"
fi

mkdir -p "$OUT_DIR"
tar -xzf "$DOWNLOAD_PATH" -C "$OUT_DIR"

cat > "$OUT_DIR/etc/apk/repositories" <<EOF
$MIRROR/alpine/$BRANCH/main
$MIRROR/alpine/$BRANCH/community
EOF

cp /etc/resolv.conf "$OUT_DIR/etc/resolv.conf"
mkdir -p "$OUT_DIR/workspace" "$OUT_DIR/usr/local/bin" "$OUT_DIR/etc/profile.d"

if [ "$SKIP_PACKAGES" -eq 0 ] && [ "$ARCH" != "$HOST_ARCH" ]; then
  mkdir -p "$OUT_DIR/usr/bin"
  cp "$(command -v qemu-aarch64-static)" "$OUT_DIR/usr/bin/qemu-aarch64-static"
fi

cat > "$OUT_DIR/etc/profile.d/ai-workspace.sh" <<'EOF'
export AI_WORKSPACE=/workspace
cd /workspace 2>/dev/null || true
EOF

cat > "$OUT_DIR/usr/local/bin/ai-env-check" <<'EOF'
#!/bin/sh
set -eu
echo "ai-linux: ready"
echo "workspace: ${AI_WORKSPACE:-/workspace}"
test -d /workspace
python3 --version
git --version
curl --version | head -n 1
node --version
ssh -V 2>&1 | head -n 1
EOF
chmod +x "$OUT_DIR/usr/local/bin/ai-env-check"

cat > "$OUT_DIR/etc/ai-linux-release" <<EOF
AI_LINUX_NAME="AI Linux Minimal"
BASE_DISTRIBUTION="Alpine Linux"
ALPINE_BRANCH="$BRANCH"
ALPINE_VERSION="$VERSION"
ARCH="$ARCH"
EOF

if [ "$SKIP_PACKAGES" -eq 0 ]; then
  PACKAGES="$(grep -Ev '^[[:space:]]*(#|$)' "$PACKAGES_FILE" | xargs)"
  chroot "$OUT_DIR" /bin/sh -c "/sbin/apk add --no-cache $PACKAGES"
  chroot "$OUT_DIR" /bin/sh -c "update-ca-certificates"
  rm -rf "$OUT_DIR/var/cache/apk/"*
  rm -f "$OUT_DIR/usr/bin/qemu-aarch64-static"
fi

TARBALL="$TARBALL_DIR/alpine-$ARCH-ai-linux-rootfs.tar.gz"
tar --numeric-owner -C "$OUT_DIR" -czf "$TARBALL" .

BUILD_REPORT="$REPORT_DIR/build-alpine-$ARCH.txt"
{
  echo "status=ok"
  echo "arch=$ARCH"
  echo "host_arch=$HOST_ARCH"
  echo "alpine_branch=$BRANCH"
  echo "alpine_version=$VERSION"
  echo "source=$DOWNLOAD_URL"
  echo "rootfs=$OUT_DIR"
  echo "tarball=$TARBALL"
  echo "packages_file=$PACKAGES_FILE"
  if [ "$SKIP_PACKAGES" -eq 0 ]; then
    echo "packages=$(grep -Ev '^[[:space:]]*(#|$)' "$PACKAGES_FILE" | xargs)"
  else
    echo "packages=skipped"
  fi
  echo "size=$(du -sh "$OUT_DIR" | awk '{print $1}')"
} | tee "$BUILD_REPORT"
