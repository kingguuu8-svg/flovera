#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Download Alpine netboot kernel/initramfs for QEMU.

Usage:
  bash scripts/download-alpine-qemu-kernel.sh --arch ARCH [options]

Options:
  --arch ARCH       Alpine architecture. Supported: aarch64.
  --out DIR         Output directory. Default: artifacts/qemu/kernel/ARCH.
  --force           Re-download and replace output directory.
  -h, --help        Show this help.

Environment:
  ALPINE_MIRROR     Default: https://dl-cdn.alpinelinux.org
USAGE
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing required command: $1"
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
    *) fail "refusing to remove path outside artifacts/qemu/kernel: $resolved_path" ;;
  esac

  rm -rf -- "$resolved_path"
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIRROR="${ALPINE_MIRROR:-https://dl-cdn.alpinelinux.org}"
ARCH=""
OUT=""
FORCE=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --arch)
      [ "$#" -ge 2 ] || fail "--arch requires a value"
      ARCH="$2"
      shift 2
      ;;
    --out)
      [ "$#" -ge 2 ] || fail "--out requires a value"
      OUT="$2"
      shift 2
      ;;
    --force)
      FORCE=1
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

[ -n "$ARCH" ] || fail "--arch is required"
case "$ARCH" in
  aarch64) ;;
  *) fail "unsupported arch for Alpine netboot kernel: $ARCH" ;;
esac

require_cmd curl
require_cmd tar
require_cmd sha256sum
require_cmd awk
require_cmd realpath

OUT="${OUT:-$REPO_ROOT/artifacts/qemu/kernel/$ARCH}"
OUT="$(realpath -m "$OUT")"
KERNEL_ROOT="$REPO_ROOT/artifacts/qemu/kernel"
DOWNLOAD_DIR="$REPO_ROOT/artifacts/downloads"
REPORT_DIR="$REPO_ROOT/artifacts/reports"
mkdir -p "$DOWNLOAD_DIR" "$REPORT_DIR" "$KERNEL_ROOT"

if [ -e "$OUT" ]; then
  [ "$FORCE" -eq 1 ] || fail "output directory already exists: $OUT; pass --force"
  safe_remove_output "$OUT" "$KERNEL_ROOT"
fi
mkdir -p "$OUT"

METADATA_URL="$MIRROR/alpine/latest-stable/releases/$ARCH/latest-releases.yaml"
METADATA="$(curl -fsSL "$METADATA_URL")"
BRANCH="$(printf '%s\n' "$METADATA" | awk '/branch: / {branch=$2} /flavor: alpine-netboot/ {print branch; exit}')"
VERSION="$(printf '%s\n' "$METADATA" | awk '/version: / {version=$2} /flavor: alpine-netboot/ {print version; exit}')"
FILE="$(printf '%s\n' "$METADATA" | awk '/file: alpine-netboot-/ {print $2; exit}')"
SHA256="$(printf '%s\n' "$METADATA" | awk '/flavor: alpine-netboot/ {found=1} found && /sha256: / {print $2; exit}')"

[ -n "$BRANCH" ] || fail "failed to parse Alpine branch from $METADATA_URL"
[ -n "$VERSION" ] || fail "failed to parse Alpine version from $METADATA_URL"
[ -n "$FILE" ] || fail "failed to parse Alpine netboot file from $METADATA_URL"
[ -n "$SHA256" ] || fail "failed to parse Alpine netboot sha256 from $METADATA_URL"

DOWNLOAD_URL="$MIRROR/alpine/$BRANCH/releases/$ARCH/$FILE"
DOWNLOAD_PATH="$DOWNLOAD_DIR/$FILE"

if [ ! -f "$DOWNLOAD_PATH" ] || ! printf '%s  %s\n' "$SHA256" "$DOWNLOAD_PATH" | sha256sum -c - >/dev/null 2>&1; then
  curl -fL "$DOWNLOAD_URL" -o "$DOWNLOAD_PATH"
fi
printf '%s  %s\n' "$SHA256" "$DOWNLOAD_PATH" | sha256sum -c -

tar -xzf "$DOWNLOAD_PATH" -C "$OUT"

KERNEL="$(find "$OUT" -type f -name 'vmlinuz-*' | sort | head -n 1)"
INITRAMFS="$(find "$OUT" -type f -name 'initramfs-*' | sort | head -n 1)"
[ -n "$KERNEL" ] || fail "failed to find vmlinuz-* in netboot archive"
[ -n "$INITRAMFS" ] || fail "failed to find initramfs-* in netboot archive"

REPORT="$REPORT_DIR/download-alpine-kernel-$ARCH.txt"
{
  echo "status=ok"
  echo "arch=$ARCH"
  echo "alpine_branch=$BRANCH"
  echo "alpine_version=$VERSION"
  echo "source=$DOWNLOAD_URL"
  echo "kernel=$KERNEL"
  echo "initramfs=$INITRAMFS"
} | tee "$REPORT"
