#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Install the rebuilt Android spike APK, stage private inputs, and launch the app.

Usage:
  bash scripts/verify-android-spike-device.sh [options]

Options:
  --apk FILE             APK to install. Default: android/spike/app/build/outputs/apk/debug/app-debug.apk
  --package NAME         Android package name. Default: com.example.ailinuxvmspike
  --inputs-root DIR      Source directory for device inputs. Default: artifacts/android-spike/real-device-inputs
  --adb PATH             adb binary. Default: auto-detect adb/adb.exe
  --allow-low-battery    Skip the battery >= 25 check.
  -h, --help             Show this help.

This script only touches the app private directory:
  /data/user/0/PKG/files/ai-linux-spike/inputs

It does not root the device, modify system partitions, or uninstall the app.
USAGE
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing required command: $1"
}

adb_shell() {
  "$ADB" shell "$@"
}

adb_runas() {
  adb_shell run-as "$PACKAGE" "$@"
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$REPO_ROOT/android/spike/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.example.ailinuxvmspike"
INPUTS_ROOT="$REPO_ROOT/artifacts/android-spike/real-device-inputs"
ADB=""
ALLOW_LOW_BATTERY=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --apk)
      [ "$#" -ge 2 ] || fail "--apk requires a value"
      APK="$2"
      shift 2
      ;;
    --package)
      [ "$#" -ge 2 ] || fail "--package requires a value"
      PACKAGE="$2"
      shift 2
      ;;
    --inputs-root)
      [ "$#" -ge 2 ] || fail "--inputs-root requires a value"
      INPUTS_ROOT="${2%/}"
      shift 2
      ;;
    --adb)
      [ "$#" -ge 2 ] || fail "--adb requires a value"
      ADB="$2"
      shift 2
      ;;
    --allow-low-battery)
      ALLOW_LOW_BATTERY=1
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

REPO_ROOT="$(realpath -m "$REPO_ROOT")"
APK="$(realpath -m "$APK")"
INPUTS_ROOT="$(realpath -m "$INPUTS_ROOT")"

require_cmd tar
require_cmd awk
require_cmd grep
require_cmd realpath

if [ -z "$ADB" ]; then
  if command -v adb >/dev/null 2>&1; then
    ADB="$(command -v adb)"
  elif command -v adb.exe >/dev/null 2>&1; then
    ADB="$(command -v adb.exe)"
  elif compgen -G '/mnt/c/Users/*/AppData/Local/Android/Sdk/platform-tools/adb.exe' >/dev/null; then
    for candidate in /mnt/c/Users/*/AppData/Local/Android/Sdk/platform-tools/adb.exe; do
      if [ -x "$candidate" ]; then
        ADB="$candidate"
        break
      fi
    done
  elif command -v powershell.exe >/dev/null 2>&1 && command -v wslpath >/dev/null 2>&1; then
    windows_adb="$(
      powershell.exe -NoProfile -Command "\$sdk=Join-Path \$env:LOCALAPPDATA 'Android\Sdk'; Join-Path \$sdk 'platform-tools\adb.exe'" |
        tr -d '\r'
    )"
    if [ -n "$windows_adb" ]; then
      ADB="$(wslpath -u "$windows_adb")"
    fi
  fi
fi

[ -n "$ADB" ] || fail "missing adb; pass --adb or install Android platform-tools"
[ -x "$ADB" ] || fail "adb is not executable: $ADB"

ADB_APK="$APK"
case "$ADB" in
  *.exe)
    if command -v wslpath >/dev/null 2>&1; then
      ADB_APK="$(wslpath -w "$APK")"
    fi
    ;;
esac

[ -f "$APK" ] || fail "missing APK: $APK"
[ -d "$INPUTS_ROOT" ] || fail "missing input source directory: $INPUTS_ROOT"
[ -f "$INPUTS_ROOT/QEMU_EFI.fd" ] || fail "missing input file: $INPUTS_ROOT/QEMU_EFI.fd"
[ -f "$INPUTS_ROOT/vmlinuz-virt" ] || fail "missing input file: $INPUTS_ROOT/vmlinuz-virt"
[ -f "$INPUTS_ROOT/ai-linux-aarch64.cpio.gz" ] || fail "missing input file: $INPUTS_ROOT/ai-linux-aarch64.cpio.gz"
[ -f "$INPUTS_ROOT/id_ed25519" ] || fail "missing input file: $INPUTS_ROOT/id_ed25519"

device_state="$("$ADB" get-state 2>/dev/null || true)"
[ "$device_state" = "device" ] || fail "adb device is not online: ${device_state:-<unknown>}"

sdk_level="$(adb_shell getprop ro.build.version.sdk | tr -d '\r')"
[ -n "$sdk_level" ] || fail "unable to read ro.build.version.sdk"
if [ "$sdk_level" -lt 31 ]; then
  fail "device SDK must be >= 31; got $sdk_level"
fi

abi_list="$(adb_shell getprop ro.product.cpu.abilist | tr -d '\r')"
abi_primary="$(adb_shell getprop ro.product.cpu.abi | tr -d '\r')"
if ! printf '%s\n%s\n' "$abi_list" "$abi_primary" | grep -q 'arm64-v8a'; then
  fail "device ABI must include arm64-v8a; abilist=${abi_list:-<empty>} abi=${abi_primary:-<empty>}"
fi

battery_level="$(adb_shell dumpsys battery | awk -F': ' '/level:/ { gsub(/\r/, "", $2); print $2; exit }')"
[ -n "$battery_level" ] || fail "unable to read battery level"
if [ "$battery_level" -lt 25 ] && [ "$ALLOW_LOW_BATTERY" -ne 1 ]; then
  fail "battery level must be >= 25; got $battery_level (use --allow-low-battery to override)"
fi

printf 'Installing APK: %s\n' "$APK"
"$ADB" install -r -t "$ADB_APK"

if ! adb_runas id >/dev/null 2>&1; then
  fail "run-as failed for package $PACKAGE; install a debuggable build"
fi

if ! adb_runas sh -c 'command -v tar >/dev/null 2>&1'; then
  fail "device run-as shell does not provide tar"
fi

printf 'Staging inputs into /data/user/0/%s/files/ai-linux-spike/inputs\n' "$PACKAGE"
adb_runas sh -c 'mkdir -p files/ai-linux-spike/inputs'

tar -C "$INPUTS_ROOT" -cf - \
  QEMU_EFI.fd \
  vmlinuz-virt \
  ai-linux-aarch64.cpio.gz \
  id_ed25519 \
  | adb_runas sh -c 'tar -xf - -C files/ai-linux-spike/inputs'

adb_runas sh -c 'chmod 700 files/ai-linux-spike files/ai-linux-spike/inputs && chmod 600 files/ai-linux-spike/inputs/id_ed25519'

printf 'Launching app: %s/.MainActivity\n' "$PACKAGE"
"$ADB" shell am start -n "$PACKAGE/.MainActivity" >/dev/null

cat <<EOF
Device preflight passed.

Next step:
  Open the app if it is not already visible, then press Start VM.

Expected app-side logs:
  - Bundled QEMU executable is expected at .../libqemu-system-aarch64.so
  - Launching VM:
  - VM process started.

Private inputs were staged to:
  /data/user/0/$PACKAGE/files/ai-linux-spike/inputs
EOF
