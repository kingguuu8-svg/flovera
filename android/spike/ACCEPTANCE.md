# Acceptance Log

Use this file to record the actual build and verification results for the spike.

## Environment

- Repository: `E:\main\ai-in-linux`
- Android CLI: captured with `android info`
- Target: emulator first, real arm64 device later
- Windows Gradle JDK: must set `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`; default Java is Java 8 and is not sufficient
- Host QEMU JDK: OpenJDK 17 in WSL for regression commands

## Commands

- `android create empty-activity --name="AI Linux VM Spike" --output=android/spike --minSdk=31`
- `android info`
- `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat assembleDebug`
- `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat testDebugUnitTest`
- `android sdk install 'build-tools/36.0.0' 'platform-tools' 'emulator' 'system-images;android-36;google_apis;x86_64'`
- `android describe --project_dir=android/spike`
- `android emulator start --cold Medium_Phone_API_36.1`
- `android emulator start --cold PocketCLI_API34`
- `android run --apks ...`
- `android layout`
- `android screen capture`
- `bash scripts/build-android-spike-apk.sh --runtime-root artifacts/qemu-runtime/app-local --force`
- `bash scripts/verify-android-spike-device.sh --apk android/spike/app/build/outputs/apk/debug/app-debug.apk`
- `wsl bash scripts/build-alpine-rootfs.sh --arch aarch64 --force`
- `wsl bash scripts/build-qemu-initramfs.sh --arch aarch64 --rootfs artifacts/rootfs/alpine-aarch64 --modloop artifacts/qemu/kernel/aarch64/boot/modloop-virt --force`
- `wsl bash scripts/verify-qemu-vm.sh --arch aarch64 --initramfs-root --initrd artifacts/qemu/initramfs/ai-linux-aarch64.cpio.gz --kernel artifacts/qemu/kernel/aarch64/boot/vmlinuz-virt --timeout 240`

## Results

- Build: PASS, `android/spike/app/build/outputs/apk/debug/app-debug.apk` produced by `gradlew.bat assembleDebug`
- UEFI/executable preview fix build: PASS, `gradlew.bat assembleDebug` with Android Studio JBR
- Unit test task: PASS, `gradlew.bat testDebugUnitTest` with Android Studio JBR; current task result is `NO-SOURCE`
- Final preview fix build: PASS, `gradlew.bat assembleDebug` after adding `INTERNET` and QEMU process monitoring
- Final preview fix unit task: PASS, `gradlew.bat testDebugUnitTest`; current task result is `NO-SOURCE`
- Android CLI `android info`: PASS
- Android CLI `android sdk install`: PASS, installed `build-tools/36.0.0`, `platform-tools`, `emulator`, and `system-images/android-36/google_apis/x86_64`
- Android CLI `android describe`: FAIL, Windows launcher tries to execute `gradlew` as a Win32 binary and hits `CreateProcess error=193`
- Emulator boot: FAIL/blocked. Latest `android emulator start --cold Medium_Phone_API_36.1` started an emulator process, then the process died and timed out; `adb devices` was empty and the emulator log still showed hanging QEMU threads
- `android run`: blocked by no online emulator
- `android layout`: blocked by no online emulator
- `android screen capture`: blocked by no online emulator
- Android spike APK runtime staging: uses `artifacts/qemu-runtime/app-local` as source, injects `libqemu-system-aarch64.so` and shared libraries into `android/spike/app/src/main/jniLibs/arm64-v8a`, then rebuilds `app-debug.apk`
- Android spike APK runtime staging preview: PASS with `bash scripts/build-android-spike-apk.sh --runtime-root artifacts/qemu-runtime/app-local --force`; produced a debug APK and staged 353 native runtime libraries with QEMU `RUNPATH=$ORIGIN`
- Android spike device verification: preflights `adb` online state, SDK >= 31, `arm64-v8a`, battery >= 25 unless overridden, and `run-as` support; then installs the rebuilt APK and stages firmware/kernel/initramfs/key into the app-private inputs directory
- Android spike device verification preview while phone disconnected: PASS-safe; script stopped at `adb device is not online` before install or private-directory writes
- Git tracked large-file scan: PASS; generated JNI libs, APK, and reports remain ignored
- Host QEMU regression: PASS after rebuilding the aarch64 initramfs with `modloop-virt`
- Expected Android real-device inputs now include `QEMU_EFI.fd`, `vmlinuz-virt`, `ai-linux-aarch64.cpio.gz`, and `id_ed25519`
- Android manifest includes `android.permission.INTERNET` because JSch connects to forwarded local SSH and QEMU usernet uses sockets on real devices
- QEMU process monitoring now waits for `process.waitFor()` and updates `vmRunning=false`, `vmExitCode`, and the exit log when the current process exits

## Root causes and blockers

- `android describe` is blocked by the Windows-side Android CLI launcher path, not by the spike project.
- Emulator boot never reached an online `adb` state in this environment; the log showed the guest booting but hanging before the ADB-ready phase.
- The aarch64 QEMU failure was caused by a missing `modloop-virt` bundle in the first initramfs build; rebuilding with `--modloop` fixed the SSH readiness path.
- Real-device VM execution remains blocked until an Android 12+ arm64 device and the external QEMU/firmware/kernel/initramfs inputs are available.
