# AI Linux VM Spike

Subagent-first Android spike for the AI Linux QEMU path.

## Scope

- Single Activity, single screen
- Exactly four actions plus a log panel
- Asset release, process start/stop, stdout/stderr capture, and SSH readiness probe
- No WebView
- No AI agent loop
- No full workspace shell

## Android behavior

- `Prepare Assets` creates the on-device spike runtime directory and releases small template files.
- `Start VM` marks the external `qemu-system-aarch64` binary executable, then launches it from the on-device inputs directory.
- `Stop VM` terminates the running VM process.
- `Run echo ready` uses SSH against the forwarded port and expects `ready` on stdout.
- The app declares `android.permission.INTERNET` because the real-device path uses local TCP sockets for QEMU usernet and JSch SSH to `127.0.0.1:<sshPort>`.

## Expected on-device inputs

Place these files into the runtime inputs directory created by the app:

- `qemu-system-aarch64`
- `QEMU_EFI.fd`
- `vmlinuz-virt`
- `ai-linux-aarch64.cpio.gz`
- `id_ed25519`

The emulator stage is allowed to fail with a clear missing-input error.

## Windows build

The default Java on this preview host is Java 8. Use Android Studio JBR before running Gradle:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\android\spike\gradlew.bat -p android\spike assembleDebug
```

More local toolchain notes are recorded in [TOOLCHAIN.md](TOOLCHAIN.md), including the current `android describe` workaround.

## Verification

Run from this directory:

```powershell
android info
android describe --project_dir=android/spike
```

If the SDK is incomplete, install the missing packages with:

```powershell
android sdk install platform-tools emulator platforms/android-36 system-images;android-36;google_apis;x86_64
```

This spike intentionally keeps the surface area small so the emulator can prove the control path before a real device is available.
