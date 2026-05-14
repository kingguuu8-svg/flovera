#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Boot the Ubuntu arm64 cloud image under QEMU and verify the phase-1 Linux computer baseline.

Usage:
  bash scripts/verify-ubuntu-cloud-vm.sh --image FILE --seed FILE [options]

Options:
  --image FILE       Ubuntu arm64 cloud image downloaded by download-ubuntu-cloud-image.sh.
  --seed FILE        NoCloud seed image created by build-ubuntu-nocloud-seed.sh.
  --ssh-key FILE     SSH private key. Default: artifacts/qemu/ssh/ai_linux_vm_ed25519.
  --ssh-port PORT    Host port forwarded to guest SSH. Default: auto-select.
  --http-port PORT   Host port forwarded to guest HTTP test. Default: auto-select.
  --memory MB        QEMU memory in MiB. Default: 2048.
  --timeout SECONDS  Readiness timeout. Default: 420.
  --keep-running     Leave QEMU running after verification.
  -h, --help         Show this help.

The script verifies:
  - arm64 guest boot
  - SSH terminal path
  - cloud-init completion
  - HTTPS, Python, Git, /workspace
  - guest HTTP service through hostfwd
  - QMP query-status, stop, and cont
USAGE
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing required command: $1"
}

free_port() {
  python3 - <<'PY'
import socket
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

qmp_execute() {
  local command="$1"
  python3 - "$QMP_SOCK" "$command" <<'PY'
import json
import socket
import sys

path, command = sys.argv[1], sys.argv[2]

def recv_message(sock):
    data = b""
    while b"\r\n" not in data:
        chunk = sock.recv(4096)
        if not chunk:
            raise RuntimeError("QMP socket closed")
        data += chunk
    return json.loads(data.split(b"\r\n", 1)[0].decode("utf-8"))

with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
    sock.connect(path)
    recv_message(sock)
    sock.sendall(json.dumps({"execute": "qmp_capabilities"}).encode("utf-8") + b"\r\n")
    recv_message(sock)
    sock.sendall(json.dumps({"execute": command}).encode("utf-8") + b"\r\n")
    response = recv_message(sock)
print(json.dumps(response, sort_keys=True))
PY
}

ssh_guest() {
  ssh \
    -o BatchMode=yes \
    -o ConnectTimeout=5 \
    -o LogLevel=ERROR \
    -o StrictHostKeyChecking=no \
    -o UserKnownHostsFile=/dev/null \
    -i "$SECURE_SSH_KEY" \
    -p "$SSH_PORT" \
    ubuntu@127.0.0.1 \
    "$@"
}

ssh_guest_background() {
  ssh \
    -f \
    -n \
    -o BatchMode=yes \
    -o ConnectTimeout=5 \
    -o LogLevel=ERROR \
    -o StrictHostKeyChecking=no \
    -o UserKnownHostsFile=/dev/null \
    -i "$SECURE_SSH_KEY" \
    -p "$SSH_PORT" \
    ubuntu@127.0.0.1 \
    "$@"
}

wait_for_ssh() {
  local deadline
  deadline=$((SECONDS + TIMEOUT))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if ssh_guest "echo ssh-ready" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

copy_runtime_log() {
  if [ -f "$LOG_RUNTIME" ]; then
    cp "$LOG_RUNTIME" "$LOG" >/dev/null 2>&1 || true
  fi
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE=""
SEED=""
SSH_KEY="$REPO_ROOT/artifacts/qemu/ssh/ai_linux_vm_ed25519"
SSH_PORT=""
HTTP_PORT=""
MEMORY_MB=2048
TIMEOUT=420
KEEP_RUNNING=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --image)
      [ "$#" -ge 2 ] || fail "--image requires a value"
      IMAGE="$2"
      shift 2
      ;;
    --seed)
      [ "$#" -ge 2 ] || fail "--seed requires a value"
      SEED="$2"
      shift 2
      ;;
    --ssh-key)
      [ "$#" -ge 2 ] || fail "--ssh-key requires a value"
      SSH_KEY="$2"
      shift 2
      ;;
    --ssh-port)
      [ "$#" -ge 2 ] || fail "--ssh-port requires a value"
      SSH_PORT="$2"
      shift 2
      ;;
    --http-port)
      [ "$#" -ge 2 ] || fail "--http-port requires a value"
      HTTP_PORT="$2"
      shift 2
      ;;
    --memory)
      [ "$#" -ge 2 ] || fail "--memory requires a value"
      MEMORY_MB="$2"
      shift 2
      ;;
    --timeout)
      [ "$#" -ge 2 ] || fail "--timeout requires a value"
      TIMEOUT="$2"
      shift 2
      ;;
    --keep-running)
      KEEP_RUNNING=1
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

[ -n "$IMAGE" ] || fail "--image is required"
[ -n "$SEED" ] || fail "--seed is required"

require_cmd curl
require_cmd python3
require_cmd qemu-img
require_cmd qemu-system-aarch64
require_cmd realpath
require_cmd ssh
require_cmd timeout

IMAGE="$(realpath -m "$IMAGE")"
SEED="$(realpath -m "$SEED")"
SSH_KEY="$(realpath -m "$SSH_KEY")"
[ -f "$IMAGE" ] || fail "image does not exist: $IMAGE"
[ -f "$SEED" ] || fail "seed image does not exist: $SEED"
[ -f "$SSH_KEY" ] || fail "SSH key does not exist: $SSH_KEY"

UEFI="/usr/share/qemu-efi-aarch64/QEMU_EFI.fd"
[ -f "$UEFI" ] || fail "missing QEMU aarch64 UEFI firmware: $UEFI"

SSH_PORT="${SSH_PORT:-$(free_port)}"
HTTP_PORT="${HTTP_PORT:-$(free_port)}"

REPORT_DIR="$REPO_ROOT/artifacts/reports"
RUN_DIR="$REPO_ROOT/artifacts/qemu/ubuntu/run"
mkdir -p "$REPORT_DIR" "$RUN_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT="$REPORT_DIR/verify-ubuntu-cloud-vm-$STAMP.txt"
LOG="$RUN_DIR/qemu-ubuntu-$STAMP.log"
RUNTIME_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ai-linux-ubuntu-vm.XXXXXX")"
RUNTIME_IMAGE="$RUNTIME_DIR/$(basename "$IMAGE")"
RUNTIME_SEED="$RUNTIME_DIR/seed.iso"
LOG_RUNTIME="$RUNTIME_DIR/qemu-ubuntu-$STAMP.log"
OVERLAY="$RUNTIME_DIR/ubuntu-overlay-$STAMP.qcow2"
PIDFILE="$RUNTIME_DIR/qemu-ubuntu-$STAMP.pid"
QMP_SOCK="$RUNTIME_DIR/qmp-$STAMP.sock"
SECURE_SSH_KEY="$(mktemp)"
cp "$SSH_KEY" "$SECURE_SSH_KEY"
chmod 600 "$SECURE_SSH_KEY"
cp "$IMAGE" "$RUNTIME_IMAGE"
cp "$SEED" "$RUNTIME_SEED"

exec > >(tee "$REPORT") 2>&1

cleanup() {
  copy_runtime_log
  rm -f "$SECURE_SSH_KEY"
  if [ "$KEEP_RUNNING" -eq 1 ]; then
    echo "keep_running=1"
    echo "pidfile=$PIDFILE"
    echo "qmp_socket=$QMP_SOCK"
    echo "runtime_dir=$RUNTIME_DIR"
    return
  fi
  if [ -f "$PIDFILE" ]; then
    local pid
    pid="$(cat "$PIDFILE" 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" >/dev/null 2>&1; then
      kill "$pid" >/dev/null 2>&1 || true
      timeout 10 bash -c "while kill -0 '$pid' >/dev/null 2>&1; do sleep 0.2; done" >/dev/null 2>&1 || true
      if kill -0 "$pid" >/dev/null 2>&1; then
        kill -9 "$pid" >/dev/null 2>&1 || true
      fi
    fi
  fi
  copy_runtime_log
  rm -rf "$RUNTIME_DIR"
}
trap cleanup EXIT

IMAGE_FORMAT="$(
  qemu-img info --output=json "$RUNTIME_IMAGE" |
    python3 -c 'import json,sys; print(json.load(sys.stdin).get("format", "qcow2"))'
)"
qemu-img create -f qcow2 -F "$IMAGE_FORMAT" -b "$RUNTIME_IMAGE" "$OVERLAY" >/dev/null

QEMU_ARGS=(
  -machine virt,accel=tcg
  -cpu cortex-a57
  -m "${MEMORY_MB}M"
  -smp 2
  -no-reboot
  -bios "$UEFI"
  -drive "if=none,file=$OVERLAY,format=qcow2,id=hd0"
  -device virtio-blk-pci,drive=hd0
  -drive "if=none,file=$RUNTIME_SEED,format=raw,readonly=on,id=seed0"
  -device virtio-blk-pci,drive=seed0
  -netdev "user,id=net0,hostfwd=tcp:127.0.0.1:$SSH_PORT-:22,hostfwd=tcp:127.0.0.1:$HTTP_PORT-:8000"
  -device virtio-net-pci,netdev=net0
  -qmp "unix:$QMP_SOCK,server=on,wait=off"
  -pidfile "$PIDFILE"
  -daemonize
  -serial "file:$LOG_RUNTIME"
  -display none
)

echo "image=$IMAGE"
echo "seed=$SEED"
echo "runtime_dir=$RUNTIME_DIR"
echo "runtime_image=$RUNTIME_IMAGE"
echo "runtime_seed=$RUNTIME_SEED"
echo "overlay=$OVERLAY"
echo "image_format=$IMAGE_FORMAT"
echo "ssh_port=$SSH_PORT"
echo "http_port=$HTTP_PORT"
echo "qemu_log=$LOG"
echo "qmp_socket=$QMP_SOCK"

qemu-system-aarch64 "${QEMU_ARGS[@]}"

wait_for_ssh || fail "SSH terminal did not become reachable within ${TIMEOUT}s; see $LOG"
echo "ssh=ok"

ssh_guest "timeout $TIMEOUT cloud-init status --wait >/dev/null"
echo "cloud_init=done"

ARCH="$(ssh_guest "uname -m" | tr -d '\r')"
[ "$ARCH" = "aarch64" ] || fail "expected aarch64 guest, got $ARCH"
echo "arch=$ARCH"

ssh_guest "ai-ready | grep -qx ready"
ssh_guest "test -d /workspace && test -w /workspace"
ssh_guest "python3 --version"
ssh_guest "git --version"
ssh_guest "curl -fsSI https://example.com >/dev/null"
ssh_guest "echo qemu-guest-workspace > /workspace/phase1.txt && test \"\$(cat /workspace/phase1.txt)\" = qemu-guest-workspace"
if ssh_guest "command -v node >/dev/null 2>&1"; then
  ssh_guest "node --version"
else
  echo "node=not-installed(optional)"
fi
echo "guest_baseline=ok"

ssh_guest "pkill -f '[p]ython3 -m http.server 8000' >/dev/null 2>&1 || true"
ssh_guest_background "cd /workspace && exec python3 -m http.server 8000 >/tmp/ai-linux-http.log 2>&1"
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:$HTTP_PORT/phase1.txt" 2>/dev/null | grep -qx qemu-guest-workspace; then
    echo "http_preview=ok"
    break
  fi
  sleep 1
done
curl -fsS "http://127.0.0.1:$HTTP_PORT/phase1.txt" | grep -qx qemu-guest-workspace || fail "guest HTTP preview did not become reachable"

qmp_execute query-status
qmp_execute stop
PAUSED_STATUS="$(qmp_execute query-status)"
echo "$PAUSED_STATUS" | grep -q '"status": "paused"' || fail "QMP stop did not pause VM: $PAUSED_STATUS"
qmp_execute cont
RUNNING_STATUS="$(qmp_execute query-status)"
echo "$RUNNING_STATUS" | grep -q '"status": "running"' || fail "QMP cont did not resume VM: $RUNNING_STATUS"
echo "qmp_pause_resume=ok"

ssh_guest "echo terminal-ok"
echo "status=ok"
