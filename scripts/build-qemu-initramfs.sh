#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Create a bootable initramfs from an AI Linux rootfs.

Usage:
  bash scripts/build-qemu-initramfs.sh --rootfs DIR --arch ARCH [options]

Options:
  --rootfs DIR      Source rootfs directory.
  --arch ARCH       Architecture label. Supported: aarch64.
  --out FILE        Output cpio.gz. Default: artifacts/qemu/initramfs/ai-linux-ARCH.cpio.gz
  --ssh-key FILE    SSH private key path. Default: artifacts/qemu/ssh/ai_linux_vm_ed25519
  --modloop FILE    Optional Alpine modloop squashfs to include kernel modules.
  --force           Replace an existing output file.
  -h, --help        Show this help.
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
    *) fail "refusing to remove path outside artifacts/qemu/initramfs: $resolved_path" ;;
  esac

  rm -f -- "$resolved_path"
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOTFS=""
ARCH=""
OUT=""
SSH_KEY="$REPO_ROOT/artifacts/qemu/ssh/ai_linux_vm_ed25519"
MODLOOP=""
FORCE=0
INITRAMFS_ROOT="$REPO_ROOT/artifacts/qemu/initramfs"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --rootfs)
      [ "$#" -ge 2 ] || fail "--rootfs requires a value"
      ROOTFS="$2"
      shift 2
      ;;
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
    --ssh-key)
      [ "$#" -ge 2 ] || fail "--ssh-key requires a value"
      SSH_KEY="$2"
      shift 2
      ;;
    --modloop)
      [ "$#" -ge 2 ] || fail "--modloop requires a value"
      MODLOOP="$2"
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
[ -n "$ARCH" ] || fail "--arch is required"
case "$ARCH" in
  aarch64) ;;
  *) fail "unsupported initramfs arch: $ARCH" ;;
esac

ROOTFS="$(realpath -m "$ROOTFS")"
OUT="${OUT:-$INITRAMFS_ROOT/ai-linux-$ARCH.cpio.gz}"
OUT="$(realpath -m "$OUT")"
SSH_KEY="$(realpath -m "$SSH_KEY")"
if [ -n "$MODLOOP" ]; then
  MODLOOP="$(realpath -m "$MODLOOP")"
fi

[ -d "$ROOTFS" ] || fail "rootfs directory does not exist: $ROOTFS"
[ -x "$ROOTFS/bin/sh" ] || fail "rootfs does not contain executable /bin/sh"
[ -f "$SSH_KEY" ] || fail "SSH key does not exist: $SSH_KEY"
[ -f "$SSH_KEY.pub" ] || fail "SSH public key does not exist: $SSH_KEY.pub"
if [ -n "$MODLOOP" ]; then
  [ -f "$MODLOOP" ] || fail "modloop does not exist: $MODLOOP"
fi
[ "$(id -u)" -eq 0 ] || fail "initramfs creation should run as root to preserve ownership/devices"

require_cmd realpath
require_cmd find
require_cmd cpio
require_cmd gzip
require_cmd mknod
if [ -n "$MODLOOP" ]; then
  require_cmd unsquashfs
fi

mkdir -p "$INITRAMFS_ROOT" "$(dirname "$OUT")"
if [ -e "$OUT" ]; then
  [ "$FORCE" -eq 1 ] || fail "output file already exists: $OUT; pass --force"
  safe_remove_output "$OUT" "$INITRAMFS_ROOT"
fi

WORK_DIR="$(mktemp -d)"
MODLOOP_EXTRACT=""
cleanup() {
  if [ -n "$MODLOOP_EXTRACT" ]; then
    rm -rf "$MODLOOP_EXTRACT"
  fi
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

tar --numeric-owner -C "$ROOTFS" -cpf - . | tar --numeric-owner -C "$WORK_DIR" -xpf -

if [ -n "$MODLOOP" ]; then
  MODLOOP_EXTRACT="$(mktemp -d)"
  rmdir "$MODLOOP_EXTRACT"
  unsquashfs -q -d "$MODLOOP_EXTRACT" "$MODLOOP" modules >/dev/null
  MODULE_DIR="$(find "$MODLOOP_EXTRACT/modules" -mindepth 2 -maxdepth 2 -type f -name modules.dep -printf '%h\n' | head -n 1)"
  [ -n "$MODULE_DIR" ] || fail "modloop does not contain /modules/KERNEL_VERSION"
  KERNEL_VERSION="$(basename "$MODULE_DIR")"
  TARGET_MODULE_DIR="$WORK_DIR/lib/modules/$KERNEL_VERSION"
  mkdir -p "$TARGET_MODULE_DIR"

  find "$MODULE_DIR" -maxdepth 1 -type f -name 'modules.*' -exec cp -a {} "$TARGET_MODULE_DIR/" \;

  for module in \
    kernel/net/core/failover.ko \
    kernel/drivers/net/net_failover.ko \
    kernel/drivers/net/virtio_net.ko \
    kernel/drivers/net/ethernet/intel/e1000/e1000.ko; do
    [ -f "$MODULE_DIR/$module" ] || fail "required module missing from modloop: $module"
    mkdir -p "$TARGET_MODULE_DIR/$(dirname "$module")"
    cp -a "$MODULE_DIR/$module" "$TARGET_MODULE_DIR/$module"
  done
fi

mkdir -p "$WORK_DIR/dev" "$WORK_DIR/proc" "$WORK_DIR/sys" "$WORK_DIR/run" "$WORK_DIR/tmp" "$WORK_DIR/workspace" "$WORK_DIR/root/.ssh" "$WORK_DIR/usr/local/sbin"
chmod 1777 "$WORK_DIR/tmp"
chown 0:0 "$WORK_DIR/root" "$WORK_DIR/root/.ssh"
chmod 700 "$WORK_DIR/root" "$WORK_DIR/root/.ssh"

for node in console null zero random urandom tty ttyAMA0; do
  rm -f "$WORK_DIR/dev/$node"
done
mknod -m 600 "$WORK_DIR/dev/console" c 5 1
mknod -m 666 "$WORK_DIR/dev/null" c 1 3
mknod -m 666 "$WORK_DIR/dev/zero" c 1 5
mknod -m 666 "$WORK_DIR/dev/random" c 1 8
mknod -m 666 "$WORK_DIR/dev/urandom" c 1 9
mknod -m 666 "$WORK_DIR/dev/tty" c 5 0
mknod -m 660 "$WORK_DIR/dev/ttyAMA0" c 204 64

install -m 600 "$SSH_KEY.pub" "$WORK_DIR/root/.ssh/authorized_keys"
chown 0:0 "$WORK_DIR/root/.ssh/authorized_keys"
chmod 600 "$WORK_DIR/root/.ssh/authorized_keys"

cat > "$WORK_DIR/usr/local/sbin/ai-vm-init" <<'EOF'
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
modprobe virtio_net 2>/dev/null || true
modprobe e1000 2>/dev/null || true
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
  if ! udhcpc -i "$IFACE" -q -t 10 -T 1; then
    echo "ai-vm-init: dhcp failed, using QEMU usernet static address" >/dev/console
    ifconfig "$IFACE" 10.0.2.15 netmask 255.255.255.0 up || true
    route add default gw 10.0.2.2 "$IFACE" || true
    printf 'nameserver 10.0.2.3\n' > /etc/resolv.conf
  fi
else
  echo "ai-vm-init: no non-loopback network interface found" >/dev/console
fi

echo "ai-vm-init: starting dropbear" >/dev/console
exec /usr/sbin/dropbear -R -E -F -p 0.0.0.0:22 -s
EOF
chmod +x "$WORK_DIR/usr/local/sbin/ai-vm-init"

(cd "$WORK_DIR" && find . -print0 | cpio --null -o --format=newc 2>/dev/null | gzip -9) > "$OUT"
trap - EXIT
cleanup

REPORT_DIR="$REPO_ROOT/artifacts/reports"
mkdir -p "$REPORT_DIR"
REPORT="$REPORT_DIR/build-qemu-initramfs-$ARCH.txt"
{
  echo "status=ok"
  echo "arch=$ARCH"
  echo "rootfs=$ROOTFS"
  echo "initramfs=$OUT"
  if [ -n "$MODLOOP" ]; then
    echo "modloop=$MODLOOP"
  fi
  echo "size=$(du -h "$OUT" | awk '{print $1}')"
} | tee "$REPORT"
