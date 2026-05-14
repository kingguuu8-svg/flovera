#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Create a NoCloud seed image for the Ubuntu arm64 QEMU guest.

Usage:
  bash scripts/build-ubuntu-nocloud-seed.sh [options]

Options:
  --out FILE       Output seed image. Default: artifacts/qemu/ubuntu/seed/seed.iso
  --ssh-key FILE   SSH private key. Default: artifacts/qemu/ssh/ai_linux_vm_ed25519
  --hostname NAME  Guest hostname. Default: ai-linux
  --force          Replace an existing seed image.
  -h, --help       Show this help.

The script writes cloud-init user-data that creates /workspace and enables SSH
login for user "ubuntu". It intentionally avoids first-boot apt installs so the
VM reaches an interactive terminal quickly and predictably under TCG.
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
    *) fail "refusing to remove path outside artifacts/qemu/ubuntu/seed: $resolved_path" ;;
  esac

  rm -f -- "$resolved_path"
}

find_iso_builder() {
  if command -v cloud-localds >/dev/null 2>&1; then
    echo "cloud-localds"
  elif command -v genisoimage >/dev/null 2>&1; then
    echo "genisoimage"
  elif command -v mkisofs >/dev/null 2>&1; then
    echo "mkisofs"
  elif command -v xorrisofs >/dev/null 2>&1; then
    echo "xorrisofs"
  else
    return 1
  fi
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$REPO_ROOT/artifacts/qemu/ubuntu/seed/seed.iso"
SSH_KEY="$REPO_ROOT/artifacts/qemu/ssh/ai_linux_vm_ed25519"
HOSTNAME="ai-linux"
FORCE=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --out)
      [ "$#" -ge 2 ] || fail "--out requires a value"
      OUT="$2"
      shift 2
      ;;
    --ssh-key)
      [ "$#" -ge 2 ] || fail "--ssh-key requires a value"
      SSH_KEY="$2"
      shift 2
      ;;
    --hostname)
      [ "$#" -ge 2 ] || fail "--hostname requires a value"
      HOSTNAME="$2"
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

require_cmd realpath
require_cmd ssh-keygen

OUT="$(realpath -m "$OUT")"
SSH_KEY="$(realpath -m "$SSH_KEY")"
SEED_ROOT="$REPO_ROOT/artifacts/qemu/ubuntu/seed"
REPORT_DIR="$REPO_ROOT/artifacts/reports"
mkdir -p "$SEED_ROOT" "$(dirname "$SSH_KEY")" "$REPORT_DIR" "$(dirname "$OUT")"

if [ -e "$OUT" ]; then
  [ "$FORCE" -eq 1 ] || fail "output already exists: $OUT; pass --force"
  safe_remove_output "$OUT" "$SEED_ROOT"
fi

if [ ! -f "$SSH_KEY" ]; then
  ssh-keygen -t ed25519 -N '' -f "$SSH_KEY" -C ai-linux-vm >/dev/null
fi
[ -f "$SSH_KEY.pub" ] || fail "missing SSH public key: $SSH_KEY.pub"

ISO_BUILDER="$(find_iso_builder)" || fail "missing seed image builder; install cloud-image-utils, genisoimage, mkisofs, or xorriso"

WORK_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

SSH_PUBLIC_KEY="$(cat "$SSH_KEY.pub")"

cat > "$WORK_DIR/user-data" <<EOF
#cloud-config
hostname: $HOSTNAME
manage_etc_hosts: true
ssh_pwauth: false
disable_root: true
package_update: false
users:
  - name: ubuntu
    groups: [adm, sudo]
    shell: /bin/bash
    sudo: "ALL=(ALL) NOPASSWD:ALL"
    ssh_authorized_keys:
      - $SSH_PUBLIC_KEY
write_files:
  - path: /etc/profile.d/ai-workspace.sh
    permissions: "0644"
    content: |
      export AI_LINUX_WORKSPACE=/workspace
  - path: /usr/local/bin/ai-ready
    permissions: "0755"
    content: |
      #!/bin/sh
      echo ready
runcmd:
  - mkdir -p /workspace
  - chown ubuntu:ubuntu /workspace
  - chmod 755 /workspace
  - systemctl enable ssh || true
  - systemctl restart ssh || true
  - echo "ai-linux guest ready" > /etc/ai-linux-ready
EOF

cat > "$WORK_DIR/meta-data" <<EOF
instance-id: ai-linux-ubuntu-arm64
local-hostname: $HOSTNAME
EOF

case "$ISO_BUILDER" in
  cloud-localds)
    cloud-localds "$OUT" "$WORK_DIR/user-data" "$WORK_DIR/meta-data"
    ;;
  genisoimage | mkisofs | xorrisofs)
    "$ISO_BUILDER" -quiet -output "$OUT" -volid cidata -joliet -rock \
      "$WORK_DIR/user-data" "$WORK_DIR/meta-data"
    ;;
  *)
    fail "unsupported ISO builder: $ISO_BUILDER"
    ;;
esac

REPORT="$REPORT_DIR/build-ubuntu-nocloud-seed.txt"
{
  echo "status=ok"
  echo "seed=$OUT"
  echo "ssh_key=$SSH_KEY"
  echo "hostname=$HOSTNAME"
  echo "iso_builder=$ISO_BUILDER"
  echo "size=$(du -h "$OUT" | awk '{print $1}')"
} | tee "$REPORT"
