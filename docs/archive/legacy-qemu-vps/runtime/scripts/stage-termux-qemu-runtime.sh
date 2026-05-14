#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Stage a Termux qemu-system-aarch64-headless runtime for Android app testing.

Usage:
  bash scripts/stage-termux-qemu-runtime.sh [options]

Options:
  --mirror URL      Termux main repo root. Default: https://termux.librehat.com/apt/termux-main
  --out DIR         Output root. Default: artifacts/qemu-runtime
  --force           Remove and rebuild output directories under --out.
  -h, --help        Show this help.

The script downloads the Termux aarch64 package index, recursively resolves
package dependencies for qemu-system-aarch64-headless, extracts the packages,
and creates an app-local tree:

  OUT/app-local/bin/qemu-system-aarch64
  OUT/app-local/lib/
  OUT/app-local/share/

If patchelf is available, qemu-system-aarch64 RUNPATH is changed to
$ORIGIN/../lib.
USAGE
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing required command: $1"
}

safe_remove() {
  local path="$1"
  local root="$2"
  local resolved_path
  local resolved_root
  resolved_path="$(realpath -m "$path")"
  resolved_root="$(realpath -m "$root")"
  case "$resolved_path" in
    "$resolved_root"/*) rm -rf -- "$resolved_path" ;;
    *) fail "refusing to remove path outside output root: $resolved_path" ;;
  esac
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIRROR="https://termux.librehat.com/apt/termux-main"
OUT_ROOT="$REPO_ROOT/artifacts/qemu-runtime"
FORCE=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --mirror)
      [ "$#" -ge 2 ] || fail "--mirror requires a value"
      MIRROR="${2%/}"
      shift 2
      ;;
    --out)
      [ "$#" -ge 2 ] || fail "--out requires a value"
      OUT_ROOT="$2"
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

OUT_ROOT="$(realpath -m "$OUT_ROOT")"
DEB_DIR="$OUT_ROOT/termux-debs"
EXTRACT_DIR="$OUT_ROOT/extract"
APP_LOCAL="$OUT_ROOT/app-local"
REPORT_DIR="$REPO_ROOT/artifacts/reports"
REPORT="$REPORT_DIR/stage-termux-qemu-runtime.txt"

require_cmd curl
require_cmd python3
require_cmd ar
require_cmd tar
require_cmd readelf
require_cmd sha256sum
require_cmd realpath

if [ "$FORCE" -eq 1 ]; then
  mkdir -p "$OUT_ROOT"
  safe_remove "$DEB_DIR" "$OUT_ROOT"
  safe_remove "$EXTRACT_DIR" "$OUT_ROOT"
  safe_remove "$APP_LOCAL" "$OUT_ROOT"
fi

mkdir -p "$DEB_DIR" "$EXTRACT_DIR" "$APP_LOCAL/bin" "$APP_LOCAL/lib" "$APP_LOCAL/share" "$REPORT_DIR"

PACKAGES_FILE="$OUT_ROOT/Packages"
curl -fsSL "$MIRROR/dists/stable/main/binary-aarch64/Packages" -o "$PACKAGES_FILE"

python3 - "$PACKAGES_FILE" "$OUT_ROOT/package-closure.txt" <<'PY'
import re
import sys
from collections import deque

packages_path, out_path = sys.argv[1], sys.argv[2]
text = open(packages_path, encoding="utf-8").read()
records = {}
for stanza in text.strip().split("\n\n"):
    fields = {}
    current = None
    for line in stanza.splitlines():
        if line.startswith(" ") and current:
            fields[current] += "\n" + line
            continue
        if ":" in line:
            key, value = line.split(":", 1)
            current = key
            fields[key] = value.strip()
    name = fields.get("Package")
    if name:
        records[name] = fields

def parse_deps(raw):
    deps = []
    for item in raw.split(","):
        item = item.strip()
        if not item:
            continue
        first = item.split("|", 1)[0].strip()
        first = re.sub(r"\s*\(.*?\)", "", first)
        first = first.split(":", 1)[0].strip()
        if first:
            deps.append(first)
    return deps

wanted = []
missing = []
queue = deque(["qemu-system-aarch64-headless"])
seen = set()
while queue:
    name = queue.popleft()
    if name in seen:
        continue
    seen.add(name)
    record = records.get(name)
    if not record:
        missing.append(name)
        continue
    wanted.append(name)
    for dep in parse_deps(record.get("Depends", "")):
        if dep not in seen:
            queue.append(dep)

with open(out_path, "w", encoding="utf-8") as f:
    for name in wanted:
        record = records[name]
        f.write(f"{name}\t{record.get('Version','')}\t{record.get('Filename','')}\t{record.get('SHA256','')}\n")
    if missing:
        f.write("\n# missing package records\n")
        for name in missing:
            f.write(f"{name}\n")
PY

while IFS=$'\t' read -r pkg version filename sha256; do
  [ -n "${pkg:-}" ] || continue
  case "$pkg" in \#*) continue ;; esac
  [ -n "${filename:-}" ] || continue
  deb="$DEB_DIR/${filename##*/}"
  if [ ! -f "$deb" ]; then
    curl -fL "$MIRROR/$filename" -o "$deb"
  fi
  if [ -n "${sha256:-}" ]; then
    printf '%s  %s\n' "$sha256" "$deb" | sha256sum -c -
  fi
  pkg_extract="$EXTRACT_DIR/$pkg"
  rm -rf "$pkg_extract"
  mkdir -p "$pkg_extract"
  (cd "$pkg_extract" && ar x "$deb")
  data_archive="$(find "$pkg_extract" -maxdepth 1 -type f -name 'data.tar.*' | head -n 1)"
  [ -n "$data_archive" ] || fail "package has no data archive: $deb"
  mkdir -p "$pkg_extract/data"
  tar -xf "$data_archive" -C "$pkg_extract/data"
done < "$OUT_ROOT/package-closure.txt"

QEMU_SOURCE="$(find "$EXTRACT_DIR/qemu-system-aarch64-headless" -path '*/data/com.termux/files/usr/bin/qemu-system-aarch64' -type f | head -n 1)"
[ -n "$QEMU_SOURCE" ] || fail "qemu-system-aarch64 not found in extracted package"
cp -a "$QEMU_SOURCE" "$APP_LOCAL/bin/qemu-system-aarch64"

find "$EXTRACT_DIR" -path '*/data/com.termux/files/usr/lib' -type d | while IFS= read -r lib_dir; do
  find "$lib_dir" -mindepth 1 -maxdepth 1 -exec cp -a {} "$APP_LOCAL/lib/" \;
done
find "$EXTRACT_DIR" -path '*/data/com.termux/files/usr/share/qemu/*' -type f | while IFS= read -r file; do
  rel="${file#*/data/com.termux/files/usr/share/}"
  mkdir -p "$APP_LOCAL/share/$(dirname "$rel")"
  cp -a "$file" "$APP_LOCAL/share/$rel"
done

if command -v patchelf >/dev/null 2>&1; then
  patchelf --set-rpath '$ORIGIN/../lib' "$APP_LOCAL/bin/qemu-system-aarch64"
fi

NEEDED="$OUT_ROOT/qemu-needed.txt"
MISSING="$OUT_ROOT/qemu-missing-direct-libs.txt"
readelf -d "$APP_LOCAL/bin/qemu-system-aarch64" |
  awk '/NEEDED/ {gsub(/.*\[/,""); gsub(/\].*/,""); print}' |
  sort -u > "$NEEDED"

: > "$MISSING"
while IFS= read -r lib; do
  case "$lib" in
    libc.so | libm.so | libdl.so) continue ;;
  esac
  [ -e "$APP_LOCAL/lib/$lib" ] || printf '%s\n' "$lib" >> "$MISSING"
done < "$NEEDED"

{
  echo "status=ok"
  echo "mirror=$MIRROR"
  echo "out_root=$OUT_ROOT"
  echo "package_count=$(grep -c '^[^#[:space:]]' "$OUT_ROOT/package-closure.txt")"
  echo "app_local=$APP_LOCAL"
  echo "app_local_size=$(du -sh "$APP_LOCAL" | awk '{print $1}')"
  echo "qemu_binary_size=$(du -h "$APP_LOCAL/bin/qemu-system-aarch64" | awk '{print $1}')"
  echo "qemu_sha256=$(sha256sum "$APP_LOCAL/bin/qemu-system-aarch64" | awk '{print $1}')"
  echo "runpath=$(readelf -d "$APP_LOCAL/bin/qemu-system-aarch64" | awk '/RUNPATH/ {print}')"
  if [ -s "$MISSING" ]; then
    echo "missing_direct_libs=$(paste -sd, "$MISSING")"
  else
    echo "missing_direct_libs=none"
  fi
} | tee "$REPORT"
