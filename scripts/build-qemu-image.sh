#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Create an ext4 disk image that boots the AI Linux rootfs under QEMU.

Usage:
  bash scripts/build-qemu-image.sh --rootfs DIR [options]

Options:
  --rootfs DIR         Source rootfs directory.
  --out FILE           Output image. Default: artifacts/qemu/ai-linux-x86_64.ext4
  --size SIZE          Image size. Default: 512M.
  --ssh-key FILE       SSH private key path. Default: artifacts/qemu/ssh/ai_linux_vm_ed25519
  --force              Replace an existing output image under artifacts/qemu.
  -h, --help           Show this help.

The script embeds the SSH public key into the image and adds a tiny
init program at /usr/local/sbin/ai-vm-init. The init program configures
networking and starts dropbear for host-to-guest command execution.
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
    *) fail "refusing to remove path outside artifacts/qemu: $resolved_path" ;;
  esac

  rm -f -- "$resolved_path"
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOTFS=""
OUT="$REPO_ROOT/artifacts/qemu/ai-linux-x86_64.ext4"
SIZE="512M"
SSH_KEY="$REPO_ROOT/artifacts/qemu/ssh/ai_linux_vm_ed25519"
FORCE=0
QEMU_ROOT="$REPO_ROOT/artifacts/qemu"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --rootfs)
      [ "$#" -ge 2 ] || fail "--rootfs requires a value"
      ROOTFS="$2"
      shift 2
      ;;
    --out)
      [ "$#" -ge 2 ] || fail "--out requires a value"
      OUT="$2"
      shift 2
      ;;
    --size)
      [ "$#" -ge 2 ] || fail "--size requires a value"
      SIZE="$2"
      shift 2
      ;;
    --ssh-key)
      [ "$#" -ge 2 ] || fail "--ssh-key requires a value"
      SSH_KEY="$2"
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

[ -n "$ROOTFS" ] || fail "--rootfs is required"
ROOTFS="$(realpath -m "$ROOTFS")"
OUT="$(realpath -m "$OUT")"
SSH_KEY="$(realpath -m "$SSH_KEY")"

[ -d "$ROOTFS" ] || fail "rootfs directory does not exist: $ROOTFS"
[ -x "$ROOTFS/bin/sh" ] || fail "rootfs does not contain executable /bin/sh"
[ "$(id -u)" -eq 0 ] || fail "image creation requires root for loop mount and device nodes"

require_cmd realpath
require_cmd truncate
require_cmd mkfs.ext4
require_cmd mount
require_cmd umount
require_cmd ssh-keygen
require_cmd tar
require_cmd mknod

mkdir -p "$QEMU_ROOT" "$(dirname "$OUT")" "$(dirname "$SSH_KEY")"

if [ -e "$OUT" ]; then
  [ "$FORCE" -eq 1 ] || fail "output image already exists: $OUT; pass --force to replace it"
  safe_remove_output "$OUT" "$QEMU_ROOT"
fi

if [ ! -f "$SSH_KEY" ]; then
  ssh-keygen -t ed25519 -N '' -f "$SSH_KEY" -C ai-linux-vm >/dev/null
fi

[ -f "$SSH_KEY.pub" ] || fail "missing SSH public key: $SSH_KEY.pub"

MOUNT_DIR="$(mktemp -d)"
cleanup() {
  if mountpoint -q "$MOUNT_DIR"; then
    umount "$MOUNT_DIR"
  fi
  rmdir "$MOUNT_DIR" 2>/dev/null || true
}
trap cleanup EXIT

truncate -s "$SIZE" "$OUT"
mkfs.ext4 -q -F "$OUT"
mount -o loop "$OUT" "$MOUNT_DIR"

tar --numeric-owner -C "$ROOTFS" -cpf - . | tar --numeric-owner -C "$MOUNT_DIR" -xpf -

mkdir -p "$MOUNT_DIR/dev" "$MOUNT_DIR/proc" "$MOUNT_DIR/sys" "$MOUNT_DIR/run" "$MOUNT_DIR/tmp" "$MOUNT_DIR/root/.ssh" "$MOUNT_DIR/usr/local/sbin"
chmod 1777 "$MOUNT_DIR/tmp"
chown 0:0 "$MOUNT_DIR/root" "$MOUNT_DIR/root/.ssh"
chmod 700 "$MOUNT_DIR/root" "$MOUNT_DIR/root/.ssh"

for node in console null zero random urandom tty ttyS0; do
  rm -f "$MOUNT_DIR/dev/$node"
done
mknod -m 600 "$MOUNT_DIR/dev/console" c 5 1
mknod -m 666 "$MOUNT_DIR/dev/null" c 1 3
mknod -m 666 "$MOUNT_DIR/dev/zero" c 1 5
mknod -m 666 "$MOUNT_DIR/dev/random" c 1 8
mknod -m 666 "$MOUNT_DIR/dev/urandom" c 1 9
mknod -m 666 "$MOUNT_DIR/dev/tty" c 5 0
mknod -m 660 "$MOUNT_DIR/dev/ttyS0" c 4 64

install -m 600 "$SSH_KEY.pub" "$MOUNT_DIR/root/.ssh/authorized_keys"
chown 0:0 "$MOUNT_DIR/root/.ssh/authorized_keys"
chmod 600 "$MOUNT_DIR/root/.ssh/authorized_keys"

cat > "$MOUNT_DIR/usr/local/sbin/ai-vm-init" <<'EOF'
#!/bin/sh
set -eu

export PATH=/sbin:/bin:/usr/sbin:/usr/bin:/usr/local/sbin:/usr/local/bin

mountpoint -q /proc || mount -t proc proc /proc
mountpoint -q /sys || mount -t sysfs sysfs /sys
mountpoint -q /dev || mount -t devtmpfs devtmpfs /dev || true
mkdir -p /run /tmp /workspace
chmod 1777 /tmp

hostname ai-linux
ifconfig lo up || true
IFACE=""
for path in /sys/class/net/*; do
  name="${path##*/}"
  if [ "$name" != "lo" ]; then
    IFACE="$name"
    break
  fi
done
if [ -n "$IFACE" ]; then
  ifconfig "$IFACE" up || true
  udhcpc -i "$IFACE" -q -t 10 -T 1 || true
else
  echo "ai-vm-init: no non-loopback network interface found" >/dev/console
fi

cat > /etc/motd <<'MOTD'
AI Linux VM ready.
MOTD

echo "ai-vm-init: starting dropbear" >/dev/console
exec /usr/sbin/dropbear -R -E -F -p 0.0.0.0:22 -s
EOF
chmod +x "$MOUNT_DIR/usr/local/sbin/ai-vm-init"

cat > "$MOUNT_DIR/etc/ai-qemu-release" <<EOF
AI_QEMU_IMAGE="true"
IMAGE_SIZE="$SIZE"
SSH_PUBLIC_KEY="$(cat "$SSH_KEY.pub")"
EOF

sync
umount "$MOUNT_DIR"
trap - EXIT
rmdir "$MOUNT_DIR"

REPORT_DIR="$REPO_ROOT/artifacts/reports"
mkdir -p "$REPORT_DIR"
REPORT="$REPORT_DIR/build-qemu-image.txt"
{
  echo "status=ok"
  echo "rootfs=$ROOTFS"
  echo "image=$OUT"
  echo "size=$SIZE"
  echo "ssh_key=$SSH_KEY"
  echo "image_usage=$(du -h "$OUT" | awk '{print $1}')"
} | tee "$REPORT"
