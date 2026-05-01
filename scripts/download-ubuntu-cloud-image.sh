#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Download the Ubuntu arm64 cloud image used as the QEMU guest base.

Usage:
  bash scripts/download-ubuntu-cloud-image.sh [options]

Options:
  --release NAME    Ubuntu cloud image release. Default: noble.
  --file NAME       Image file name. Default: RELEASE-server-cloudimg-arm64.img
  --out FILE        Output image. Default: artifacts/cloud-images/ubuntu/FILE
  --force           Re-download and replace the output image.
  -h, --help        Show this help.

Environment:
  UBUNTU_CLOUD_BASE_URL  Default: https://cloud-images.ubuntu.com
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
    *) fail "refusing to remove path outside artifacts/cloud-images: $resolved_path" ;;
  esac

  rm -f -- "$resolved_path"
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_URL="${UBUNTU_CLOUD_BASE_URL:-https://cloud-images.ubuntu.com}"
RELEASE="noble"
FILE=""
OUT=""
FORCE=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --release)
      [ "$#" -ge 2 ] || fail "--release requires a value"
      RELEASE="$2"
      shift 2
      ;;
    --file)
      [ "$#" -ge 2 ] || fail "--file requires a value"
      FILE="$2"
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

require_cmd awk
require_cmd curl
require_cmd realpath
require_cmd sha256sum

FILE="${FILE:-$RELEASE-server-cloudimg-arm64.img}"
CLOUD_IMAGE_ROOT="$REPO_ROOT/artifacts/cloud-images"
OUT="${OUT:-$CLOUD_IMAGE_ROOT/ubuntu/$FILE}"
OUT="$(realpath -m "$OUT")"
OUT_DIR="$(dirname "$OUT")"
REPORT_DIR="$REPO_ROOT/artifacts/reports"
mkdir -p "$OUT_DIR" "$REPORT_DIR"

if [ -e "$OUT" ]; then
  [ "$FORCE" -eq 1 ] || {
    echo "image already exists: $OUT"
    echo "pass --force to re-download"
    exit 0
  }
  safe_remove_output "$OUT" "$CLOUD_IMAGE_ROOT"
fi

IMAGE_URL="$BASE_URL/$RELEASE/current/$FILE"
SHA_URL="$BASE_URL/$RELEASE/current/SHA256SUMS"
SHA_FILE="$OUT_DIR/SHA256SUMS"

curl -fL "$SHA_URL" -o "$SHA_FILE"
EXPECTED_SHA="$(awk -v file="*$FILE" '$2 == file { print $1; exit }' "$SHA_FILE")"
if [ -z "$EXPECTED_SHA" ]; then
  EXPECTED_SHA="$(awk -v file="$FILE" '$2 == file { print $1; exit }' "$SHA_FILE")"
fi
[ -n "$EXPECTED_SHA" ] || fail "failed to find checksum for $FILE in $SHA_URL"

TMP_OUT="$OUT.tmp"
rm -f "$TMP_OUT"
curl -fL "$IMAGE_URL" -o "$TMP_OUT"
printf '%s  %s\n' "$EXPECTED_SHA" "$TMP_OUT" | sha256sum -c -
mv "$TMP_OUT" "$OUT"

REPORT="$REPORT_DIR/download-ubuntu-cloud-image.txt"
{
  echo "status=ok"
  echo "release=$RELEASE"
  echo "file=$FILE"
  echo "source=$IMAGE_URL"
  echo "image=$OUT"
  echo "sha256=$EXPECTED_SHA"
  echo "size=$(du -h "$OUT" | awk '{print $1}')"
} | tee "$REPORT"
