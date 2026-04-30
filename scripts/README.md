# scripts

这里放可复现的构建和验证脚本。

后续脚本应覆盖：

- 下载 Alpine rootfs
- 安装最小包集合
- 生成 ext4 或 qcow2 镜像
- 启动 QEMU
- 验证网络
- 验证 `/workspace` 持久化
- 验证 HTTP 服务
- 将 `artifacts/qemu-runtime/app-local` 注入 `android/spike/app/src/main/jniLibs/arm64-v8a`
- 重建 Android spike APK
- 通过 adb 将设备输入放入 app 私有目录并做真机验收

脚本必须可重复运行，不能依赖未记录的手工步骤。

## 当前脚本

```sh
bash scripts/build-alpine-rootfs.sh --arch x86_64 --force
bash scripts/verify-alpine-rootfs.sh --rootfs artifacts/rootfs/alpine-x86_64

bash scripts/build-alpine-rootfs.sh --arch aarch64 --force
bash scripts/verify-alpine-rootfs.sh --rootfs artifacts/rootfs/alpine-aarch64 --emulator /usr/bin/qemu-aarch64-static
```

Windows 宿主建议通过 WSL 执行：

```powershell
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/build-alpine-rootfs.sh --arch x86_64 --force"
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/verify-alpine-rootfs.sh --rootfs artifacts/rootfs/alpine-x86_64"
```

说明：

- `x86_64` 用于当前开发机 chroot 验证。
- `aarch64` 是 Android/QEMU 目标架构，跨架构安装包和 chroot 验证需要 `qemu-user-static`/`binfmt` 支持。
- 构建产物输出到 `artifacts/`，默认不提交 git。

## QEMU VM 验证

```powershell
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/build-qemu-image.sh --rootfs artifacts/rootfs/alpine-x86_64 --force"
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/verify-qemu-vm.sh --image artifacts/qemu/ai-linux-x86_64.ext4"

wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/download-alpine-qemu-kernel.sh --arch aarch64 --force"
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/build-qemu-initramfs.sh --arch aarch64 --rootfs artifacts/rootfs/alpine-aarch64 --modloop artifacts/qemu/kernel/aarch64/boot/modloop-virt --force"
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/verify-qemu-vm.sh --arch aarch64 --initramfs-root --initrd artifacts/qemu/initramfs/ai-linux-aarch64.cpio.gz --kernel artifacts/qemu/kernel/aarch64/boot/vmlinuz-virt --timeout 240"
```

QEMU 验证会通过 SSH 跨 VM 边界重复检查 shell、网络、包管理、Python、Git、Node、`/workspace` 和 HTTP 服务。

## Android spike runtime 注入

先把已验证的 QEMU runtime 注入 APK native libs，再重建 debug APK：

```bash
bash scripts/build-android-spike-apk.sh --runtime-root artifacts/qemu-runtime/app-local --force
```

该脚本会把：

- `artifacts/qemu-runtime/app-local/bin/qemu-system-aarch64`
- `artifacts/qemu-runtime/app-local/lib/*.so*`

复制到 `android/spike/app/src/main/jniLibs/arm64-v8a/`，并把 QEMU 的
`RUNPATH` 改成 `$ORIGIN`，然后执行 Gradle `assembleDebug`。

`android/spike/app/src/main/jniLibs/arm64-v8a/` 被 `.gitignore` 忽略，所以
不会把大二进制提交进仓库。

## Android 真机验收

安装重建后的 APK，并把设备输入放进 app 私有目录：

```bash
bash scripts/verify-android-spike-device.sh --apk android/spike/app/build/outputs/apk/debug/app-debug.apk
```

验收脚本只会操作：

- APK 安装
- `/data/user/0/com.example.ailinuxvmspike/files/ai-linux-spike/inputs`
- app 启动提示

它不会 root、不会 fastboot、不会改系统分区，也不会卸载 app。
