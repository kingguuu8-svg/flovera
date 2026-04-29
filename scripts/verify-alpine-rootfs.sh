#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Verify an Alpine rootfs for the AI Linux workspace.

Usage:
  bash scripts/verify-alpine-rootfs.sh --rootfs DIR [options]

Options:
  --rootfs DIR      Rootfs directory to verify.
  --port PORT       Port for the HTTP service check. Default: auto-select.
  -h, --help        Show this help.

Checks:
  shell, Alpine release, apk, HTTPS, Python, Git, Node.js, SSH client,
  /workspace writeability, and a Python HTTP service reachable from host.
USAGE
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing required command: $1"
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOTFS=""
PORT=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --rootfs)
      [ "$#" -ge 2 ] || fail "--rootfs requires a value"
      ROOTFS="$2"
      shift 2
      ;;
    --port)
      [ "$#" -ge 2 ] || fail "--port requires a value"
      PORT="$2"
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

[ -n "$ROOTFS" ] || fail "--rootfs is required"
ROOTFS="$(realpath -m "$ROOTFS")"
[ -d "$ROOTFS" ] || fail "rootfs directory does not exist: $ROOTFS"
[ -x "$ROOTFS/bin/sh" ] || fail "rootfs does not contain executable /bin/sh"
[ "$(id -u)" -eq 0 ] || fail "chroot verification requires root"

require_cmd chroot
require_cmd curl
require_cmd python3
require_cmd realpath

REPORT_DIR="$REPO_ROOT/artifacts/reports"
mkdir -p "$REPORT_DIR"
REPORT="$REPORT_DIR/verify-alpine-$(date -u +%Y%m%dT%H%M%SZ).txt"

exec > >(tee "$REPORT") 2>&1

run_guest() {
  chroot "$ROOTFS" /bin/sh -c "$1"
}

pass() {
  printf '[PASS] %s\n' "$1"
}

printf 'rootfs=%s\n' "$ROOTFS"
printf 'report=%s\n' "$REPORT"
printf 'started_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"

run_guest "echo ready"
pass "shell executes commands"

run_guest "cat /etc/alpine-release"
pass "Alpine release is readable"

run_guest "/sbin/apk --version"
run_guest "/sbin/apk update >/dev/null"
pass "apk package manager works"

run_guest "curl -fsSI https://example.com | head -n 1"
pass "HTTPS network works"

run_guest "python3 --version"
pass "Python runtime works"

run_guest "git --version"
run_guest "rm -rf /workspace/.verify-rootfs && mkdir -p /workspace/.verify-rootfs/git && cd /workspace/.verify-rootfs/git && git init -q -b main && git status --short"
pass "Git works in /workspace"

run_guest "node --version"
pass "Node.js runtime works"

run_guest "ssh -V 2>&1 | head -n 1"
pass "OpenSSH client exists"

run_guest "mkdir -p /workspace/.verify-rootfs && printf verified > /workspace/.verify-rootfs/persistence-check.txt && test \"\$(cat /workspace/.verify-rootfs/persistence-check.txt)\" = verified"
pass "/workspace is writable"

run_guest "/usr/local/bin/ai-env-check"
pass "AI environment check command works"

if [ -z "$PORT" ]; then
  PORT="$(python3 - <<'PY'
import socket
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
)"
fi

HTTP_DIR="$ROOTFS/workspace/.verify-rootfs/http"
mkdir -p "$HTTP_DIR"
printf 'ai-linux-http-ok\n' > "$HTTP_DIR/index.html"

SERVER_PID=""
cleanup() {
  if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" >/dev/null 2>&1; then
    kill "$SERVER_PID" >/dev/null 2>&1 || true
    wait "$SERVER_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

chroot "$ROOTFS" /bin/sh -c "cd /workspace/.verify-rootfs/http && exec python3 -m http.server $PORT --bind 127.0.0.1" > "$HTTP_DIR/server.log" 2>&1 &
SERVER_PID="$!"

python3 - "$PORT" <<'PY'
import socket
import sys
import time

port = int(sys.argv[1])
deadline = time.time() + 10
last_error = None

while time.time() < deadline:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.25):
            sys.exit(0)
    except OSError as exc:
        last_error = exc
        time.sleep(0.1)

raise SystemExit(f"server did not become ready: {last_error}")
PY

curl -fsS "http://127.0.0.1:$PORT/" | grep -q "ai-linux-http-ok"
pass "Python HTTP service is reachable from host on port $PORT"

cleanup
trap - EXIT
run_guest "rm -rf /workspace/.verify-rootfs /var/cache/apk/"'*'

printf 'finished_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'status=ok\n'
