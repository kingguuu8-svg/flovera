#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Inject the staged QEMU runtime into the Android spike APK and rebuild it.

Usage:
  bash scripts/build-android-spike-apk.sh [options]

Options:
  --runtime-root DIR   Staged runtime root. Default: artifacts/qemu-runtime/app-local
  --project-dir DIR    Android spike project root. Default: android/spike
  --gradle-task TASK   Gradle task to run after injection. Default: assembleDebug
  --skip-build         Only inject JNI libs; do not run Gradle.
  --force              Remove generated JNI library outputs before copying.
  -h, --help           Show this help.

Expected staged runtime layout:
  RUNTIME_ROOT/bin/qemu-system-aarch64
  RUNTIME_ROOT/lib/*.so

The script copies the QEMU executable to:
  android/spike/app/src/main/jniLibs/arm64-v8a/libqemu-system-aarch64.so

and copies the runtime libraries into the same directory so the binary can use
RUNPATH=$ORIGIN inside the APK-installed nativeLibraryDir. Versioned NEEDED
entries such as libz.so.1 are rewritten to unversioned libz.so because Android
Gradle native library packaging only installs standard lib*.so entries.
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
    "$resolved_root") rm -rf -- "$resolved_path" ;;
    *) fail "refusing to remove path outside target root: $resolved_path" ;;
  esac
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_ROOT="$REPO_ROOT/artifacts/qemu-runtime/app-local"
PROJECT_DIR="$REPO_ROOT/android/spike"
JNI_DIR="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
GRADLE_TASK="assembleDebug"
FORCE=0
SKIP_BUILD=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --runtime-root)
      [ "$#" -ge 2 ] || fail "--runtime-root requires a value"
      RUNTIME_ROOT="${2%/}"
      shift 2
      ;;
    --project-dir)
      [ "$#" -ge 2 ] || fail "--project-dir requires a value"
      PROJECT_DIR="${2%/}"
      shift 2
      ;;
    --gradle-task)
      [ "$#" -ge 2 ] || fail "--gradle-task requires a value"
      GRADLE_TASK="$2"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD=1
      shift
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

REPO_ROOT="$(realpath -m "$REPO_ROOT")"
RUNTIME_ROOT="$(realpath -m "$RUNTIME_ROOT")"
PROJECT_DIR="$(realpath -m "$PROJECT_DIR")"
JNI_DIR="$(realpath -m "$JNI_DIR")"

RUNTIME_BIN="$RUNTIME_ROOT/bin/qemu-system-aarch64"
RUNTIME_LIB_DIR="$RUNTIME_ROOT/lib"
TARGET_BIN="$JNI_DIR/libqemu-system-aarch64.so"
REPORT_DIR="$REPO_ROOT/artifacts/reports"
REPORT="$REPORT_DIR/android-spike-runtime-staging.txt"

require_cmd cp
require_cmd find
require_cmd patchelf
require_cmd readelf
require_cmd sha256sum
require_cmd realpath

[ -d "$RUNTIME_ROOT" ] || fail "missing runtime root: $RUNTIME_ROOT"
[ -f "$RUNTIME_BIN" ] || fail "missing runtime binary: $RUNTIME_BIN"
[ -d "$RUNTIME_LIB_DIR" ] || fail "missing runtime library directory: $RUNTIME_LIB_DIR"
[ -d "$PROJECT_DIR/app/src/main" ] || fail "missing Android project root: $PROJECT_DIR"

mkdir -p "$JNI_DIR" "$REPORT_DIR"

if [ "$FORCE" -eq 1 ]; then
  find "$JNI_DIR" -maxdepth 1 \( -type f -o -type l \) -name '*.so*' -delete
else
  find "$JNI_DIR" -maxdepth 1 \( -type f -o -type l \) -name 'libqemu-system-aarch64.so' -delete
  find "$JNI_DIR" -maxdepth 1 \( -type f -o -type l \) -name '*.so*' ! -name 'libqemu-system-aarch64.so' -delete
fi

cp -f "$RUNTIME_BIN" "$TARGET_BIN"
chmod 755 "$TARGET_BIN"
patchelf --set-rpath '$ORIGIN' "$TARGET_BIN"

copied_libs=0
while IFS= read -r -d '' lib; do
  cp -L "$lib" "$JNI_DIR/"
  copied_libs=$((copied_libs + 1))
done < <(find "$RUNTIME_LIB_DIR" -mindepth 1 -maxdepth 1 \( -type f -o -type l \) -name '*.so' -print0)

patched_needed=0
patched_missing_replacement=0
while IFS= read -r -d '' elf; do
  if ! readelf -h "$elf" >/dev/null 2>&1; then
    continue
  fi
  patchelf --set-soname "$(basename "$elf")" "$elf" || true
  patchelf --set-rpath '$ORIGIN' "$elf" || true
  while IFS= read -r needed; do
    case "$needed" in
      *.so.*)
        replacement="${needed%%.so.*}.so"
        if [ -e "$JNI_DIR/$replacement" ]; then
          patchelf --replace-needed "$needed" "$replacement" "$elf"
          patched_needed=$((patched_needed + 1))
        else
          patchelf --replace-needed "$needed" "$replacement" "$elf"
          patched_needed=$((patched_needed + 1))
          patched_missing_replacement=$((patched_missing_replacement + 1))
        fi
        ;;
    esac
  done < <(readelf -d "$elf" | awk '/NEEDED/ {gsub(/.*\[/,""); gsub(/\].*/,""); print}')
done < <(find "$JNI_DIR" -maxdepth 1 -type f -name 'lib*.so' -print0)

runpath_line="$(readelf -d "$TARGET_BIN" | awk '/RUNPATH/ { print; exit }')"
case "$runpath_line" in
  *'$ORIGIN'* ) ;;
  *) fail "runtime binary runpath is not \$ORIGIN: ${runpath_line:-<missing>}" ;;
esac

if [ "$SKIP_BUILD" -eq 0 ]; then
  if command -v powershell.exe >/dev/null 2>&1 && command -v wslpath >/dev/null 2>&1; then
    project_dir_win="$(wslpath -w "$PROJECT_DIR")"
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "\$ErrorActionPreference='Stop'; \$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; \$env:PATH=\"\$env:JAVA_HOME\bin;\$env:PATH\"; & '${project_dir_win}\\gradlew.bat' -p '${project_dir_win}' '${GRADLE_TASK}' --console=plain"
  elif [ -x "$PROJECT_DIR/gradlew" ]; then
    (cd "$PROJECT_DIR" && "$PROJECT_DIR/gradlew" "$GRADLE_TASK" --console=plain)
  elif [ -x "$PROJECT_DIR/gradlew.bat" ]; then
    (cd "$PROJECT_DIR" && "$PROJECT_DIR/gradlew.bat" "$GRADLE_TASK" --console=plain)
  else
    fail "missing Gradle wrapper in $PROJECT_DIR"
  fi
fi

{
  echo "status=ok"
  echo "runtime_root=$RUNTIME_ROOT"
  echo "project_dir=$PROJECT_DIR"
  echo "jni_dir=$JNI_DIR"
  echo "gradle_task=$GRADLE_TASK"
  echo "skip_build=$SKIP_BUILD"
  echo "runtime_binary_sha256=$(sha256sum "$TARGET_BIN" | awk '{print $1}')"
  echo "runtime_binary_runpath=$runpath_line"
  echo "runtime_library_count=$copied_libs"
  echo "patched_versioned_needed=$patched_needed"
  echo "patched_missing_replacement=$patched_missing_replacement"
  echo "apk_path=$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
} | tee "$REPORT"
