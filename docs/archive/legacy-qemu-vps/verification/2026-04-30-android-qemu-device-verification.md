# Android QEMU device verification - 2026-04-30

## Scope

This record covers the real-device verification path for the Android spike.

It assumes the QEMU runtime has already been injected into
`android/spike/app/src/main/jniLibs/arm64-v8a`, the APK has been rebuilt, and
the phone is connected over adb.

It does not root the device, modify system partitions, or uninstall the app.

## Host-side commands

Rebuild the APK with the staged runtime:

```bash
bash scripts/build-android-spike-apk.sh --runtime-root artifacts/qemu-runtime/app-local --force
```

Install the rebuilt APK and stage device inputs:

```bash
bash scripts/verify-android-spike-device.sh --apk android/spike/app/build/outputs/apk/debug/app-debug.apk
```

If the battery is below 25%, add `--allow-low-battery`.

## Preflight checks

The verification script must confirm:

| Check | Expectation |
|---|---|
| adb online | `device` |
| Android SDK | `>= 31` |
| ABI | `arm64-v8a` present |
| Battery | `>= 25` unless overridden |
| run-as | succeeds for `com.example.ailinuxvmspike` |

## Device-side layout

The app-private inputs directory is:

```text
/data/user/0/com.example.ailinuxvmspike/files/ai-linux-spike/inputs
```

Expected files:

```text
QEMU_EFI.fd
vmlinuz-virt
ai-linux-aarch64.cpio.gz
id_ed25519
```

The QEMU runtime itself is bundled into the APK as:

```text
/data/app/.../lib/arm64/libqemu-system-aarch64.so
```

`VmController` executes that bundled runtime directly and keeps the external
inputs in the app-private directory.

## Expected app logs

The happy path should show:

| Stage | Log fragment |
|---|---|
| Runtime path | `Bundled QEMU executable is expected at .../libqemu-system-aarch64.so` |
| Launch | `Launching VM:` |
| Launch | `Executable: .../libqemu-system-aarch64.so` |
| Launch | `VM process started.` |
| Ready probe | `Ready probe succeeded.` |

If the VM exits, the controller should also report `VM process exited. exitCode=...`.

## Failure classes

| Class | Typical signal | Likely root cause |
|---|---|---|
| Device offline | `adb device is not online` | USB disconnect or adb authorization not granted |
| SDK too low | `device SDK must be >= 31` | Wrong handset or emulator image |
| ABI mismatch | `device ABI must include arm64-v8a` | Non-arm64 device |
| Battery gate | `battery level must be >= 25` | Low battery without override |
| run-as blocked | `run-as failed` | APK not debuggable or package not installed |
| APK install | `adb install` failure | Bad APK path or signing mismatch |
| Input staging | tar/run-as failure | Missing file under `artifacts/android-spike/real-device-inputs` or shell tooling issue |
| Runtime missing | `Bundled QEMU executable` missing | APK was not rebuilt after runtime injection |
| QEMU launch | `Failed to start VM` | Native runtime path or ELF dependency problem |

## Operator note

After the script finishes, open the app UI and press `Start VM`.

If you also press `Prepare Assets`, you should see `Prepared assets under ...`
and the release-note files appear under the app's private `released-assets`
directory.

The script does not attempt to guess tap coordinates or perform UI automation.
