#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Boot the AI Linux image under QEMU and verify through the VM boundary.

Usage:
  bash scripts/verify-qemu-vm.sh --image FILE [options]

Options:
  --image FILE       ext4 disk image created by build-qemu-image.sh.
  --kernel FILE      Linux kernel. Default: latest /boot/vmlinuz-*.
  --initrd FILE      Initramfs. Default: matching /boot/initrd.img-* if available.
  --ssh-key FILE     SSH private key. Default: artifacts/qemu/ssh/ai_linux_vm_ed25519.
  --ssh-port PORT    Host port forwarded to guest SSH. Default: auto-select.
  --http-port PORT   Host port forwarded to guest HTTP test. Default: auto-select.
  --timeout SECONDS  Readiness timeout. Default: 90.
  -h, --help         Show this help.

The verification repeats the rootfs checks by executing commands over SSH
inside a QEMU VM, then starts a guest HTTP service and reaches it from host.
USAGE
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing required command: $1"
}

latest_file() {
  local pattern="$1"
  ls -1 $pattern 2>/dev/null | sort -V | tail -n 1
}

free_port() {
  python3 - <<'PY'
import socket
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE=""
KERNEL=""
INITRD=""
SSH_KEY="$REPO_ROOT/artifacts/qemu/ssh/ai_linux_vm_ed25519"
SSH_PORT=""
HTTP_PORT=""
TIMEOUT=90

while [ "$#" -gt 0 ]; do
  case "$1" in
    --image)
      [ "$#" -ge 2 ] || fail "--image requires a value"
      IMAGE="$2"
      shift 2
      ;;
    --kernel)
      [ "$#" -ge 2 ] || fail "--kernel requires a value"
      KERNEL="$2"
      shift 2
      ;;
    --initrd)
      [ "$#" -ge 2 ] || fail "--initrd requires a value"
      INITRD="$2"
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
    --timeout)
      [ "$#" -ge 2 ] || fail "--timeout requires a value"
      TIMEOUT="$2"
      shift 2
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
IMAGE="$(realpath -m "$IMAGE")"
SSH_KEY="$(realpath -m "$SSH_KEY")"
KERNEL="${KERNEL:-$(latest_file /boot/vmlinuz-*)}"
[ -n "$KERNEL" ] || fail "no kernel found; pass --kernel"
KERNEL="$(realpath -m "$KERNEL")"

if [ -z "$INITRD" ]; then
  VERSION="${KERNEL##*/vmlinuz-}"
  if [ -f "/boot/initrd.img-$VERSION" ]; then
    INITRD="/boot/initrd.img-$VERSION"
  else
    INITRD="$(latest_file /boot/initrd.img-*)"
  fi
fi

[ -f "$IMAGE" ] || fail "image does not exist: $IMAGE"
[ -f "$KERNEL" ] || fail "kernel does not exist: $KERNEL"
[ -f "$SSH_KEY" ] || fail "SSH key does not exist: $SSH_KEY"

require_cmd qemu-system-x86_64
require_cmd ssh
require_cmd curl
require_cmd python3
require_cmd realpath

SSH_PORT="${SSH_PORT:-$(free_port)}"
HTTP_PORT="${HTTP_PORT:-$(free_port)}"

REPORT_DIR="$REPO_ROOT/artifacts/reports"
QEMU_RUN_DIR="$REPO_ROOT/artifacts/qemu/run"
mkdir -p "$REPORT_DIR" "$QEMU_RUN_DIR"
REPORT="$REPORT_DIR/verify-qemu-vm-$(date -u +%Y%m%dT%H%M%SZ).txt"
LOG="$QEMU_RUN_DIR/qemu-$(date -u +%Y%m%dT%H%M%SZ).log"
PIDFILE="$QEMU_RUN_DIR/qemu.pid"
SECURE_SSH_KEY="$(mktemp)"
rm -f "$PIDFILE"
cp "$SSH_KEY" "$SECURE_SSH_KEY"
chmod 600 "$SECURE_SSH_KEY"

exec > >(tee "$REPORT") 2>&1

QEMU_ARGS=(
  -machine accel=tcg
  -cpu max
  -m 512M
  -smp 1
  -no-reboot
  -kernel "$KERNEL"
  -append "root=/dev/vda rw console=ttyS0 panic=1 init=/usr/local/sbin/ai-vm-init"
  -drive "file=$IMAGE,format=raw,if=virtio"
  -netdev "user,id=net0,hostfwd=tcp:127.0.0.1:$SSH_PORT-:22,hostfwd=tcp:127.0.0.1:$HTTP_PORT-:8000"
  -device virtio-net-pci,netdev=net0
  -pidfile "$PIDFILE"
  -daemonize
  -serial "file:$LOG"
  -display none
)

if [ -n "$INITRD" ] && [ -f "$INITRD" ]; then
  QEMU_ARGS+=(-initrd "$INITRD")
fi

cleanup() {
  rm -f "$SECURE_SSH_KEY"
  if [ -f "$PIDFILE" ]; then
    local pid
    pid="$(cat "$PIDFILE" 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" >/dev/null 2>&1; then
      kill "$pid" >/dev/null 2>&1 || true
      python3 - "$pid" <<'PY' >/dev/null 2>&1 || true
import os
import sys
import time
pid = int(sys.argv[1])
deadline = time.time() + 10
while time.time() < deadline:
    try:
        os.kill(pid, 0)
    except OSError:
        raise SystemExit(0)
    time.sleep(0.1)
PY
      if kill -0 "$pid" >/dev/null 2>&1; then
        kill -9 "$pid" >/dev/null 2>&1 || true
      fi
    fi
  fi
}
trap cleanup EXIT

printf 'image=%s\n' "$IMAGE"
printf 'kernel=%s\n' "$KERNEL"
printf 'initrd=%s\n' "${INITRD:-none}"
printf 'ssh_port=%s\n' "$SSH_PORT"
printf 'http_port=%s\n' "$HTTP_PORT"
printf 'qemu_log=%s\n' "$LOG"
printf 'started_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"

qemu-system-x86_64 "${QEMU_ARGS[@]}"

SSH_BASE=(
  ssh
  -i "$SECURE_SSH_KEY"
  -p "$SSH_PORT"
  -o StrictHostKeyChecking=no
  -o UserKnownHostsFile=/dev/null
  -o LogLevel=ERROR
  -o ConnectTimeout=3
  root@127.0.0.1
)

python3 - "$SSH_PORT" "$SECURE_SSH_KEY" "$TIMEOUT" <<'PY'
import subprocess
import sys
import time

port, key, timeout = sys.argv[1], sys.argv[2], int(sys.argv[3])
deadline = time.time() + timeout
cmd = [
    "ssh", "-i", key, "-p", port,
    "-o", "StrictHostKeyChecking=no",
    "-o", "UserKnownHostsFile=/dev/null",
    "-o", "LogLevel=ERROR",
    "-o", "ConnectTimeout=3",
    "root@127.0.0.1", "echo ready",
]

last = ""
while time.time() < deadline:
    proc = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if proc.returncode == 0 and "ready" in proc.stdout:
        raise SystemExit(0)
    last = proc.stdout
    time.sleep(1)

raise SystemExit(f"SSH did not become ready within {timeout}s. Last output: {last}")
PY

run_guest() {
  "${SSH_BASE[@]}" "$1"
}

pass() {
  printf '[PASS] %s\n' "$1"
}

run_guest "echo ready"
pass "SSH command bridge executes commands"

run_guest "cat /etc/alpine-release"
pass "Alpine release is readable"

run_guest "/sbin/apk --version"
run_guest "/sbin/apk update >/dev/null"
pass "apk package manager works"

run_guest "curl -fsS -o /dev/null -w '%{http_code}\n' https://example.com"
pass "HTTPS network works"

run_guest "python3 --version"
pass "Python runtime works"

run_guest "git --version"
run_guest "rm -rf /workspace/.verify-vm && mkdir -p /workspace/.verify-vm/git && cd /workspace/.verify-vm/git && git init -q -b main && git status --short"
pass "Git works in /workspace"

run_guest "node --version"
pass "Node.js runtime works"

run_guest "ssh -V 2>&1 | head -n 1"
pass "OpenSSH client exists"

run_guest "mkdir -p /workspace/.verify-vm && printf verified > /workspace/.verify-vm/persistence-check.txt && test \"\$(cat /workspace/.verify-vm/persistence-check.txt)\" = verified"
pass "/workspace is writable"

run_guest "/usr/local/bin/ai-env-check"
pass "AI environment check command works"

run_guest "rm -rf /workspace/.verify-vm/http && mkdir -p /workspace/.verify-vm/http && printf ai-linux-http-ok > /workspace/.verify-vm/http/index.html && cd /workspace/.verify-vm/http && nohup python3 -m http.server 8000 --bind 0.0.0.0 </dev/null >server.log 2>&1 & echo \$! > server.pid"

python3 - "$HTTP_PORT" "$TIMEOUT" <<'PY'
import urllib.error
import urllib.request
import sys
import time

port, timeout = int(sys.argv[1]), int(sys.argv[2])
deadline = time.time() + timeout
last_error = None

while time.time() < deadline:
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/", timeout=1) as response:
            body = response.read().decode("utf-8", "replace")
        if "ai-linux-http-ok" in body:
            raise SystemExit(0)
        last_error = f"unexpected body: {body!r}"
    except (OSError, urllib.error.URLError) as exc:
        last_error = exc
        time.sleep(0.2)

raise SystemExit(f"HTTP service did not return expected content: {last_error}")
PY

curl -fsS "http://127.0.0.1:$HTTP_PORT/" | grep -q "ai-linux-http-ok"
pass "Guest Python HTTP service is reachable from host through QEMU port forwarding"

run_guest "if [ -f /workspace/.verify-vm/http/server.pid ]; then kill \"\$(cat /workspace/.verify-vm/http/server.pid)\" 2>/dev/null || true; fi; rm -rf /workspace/.verify-vm /var/cache/apk/*"

printf 'finished_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'status=ok\n'
