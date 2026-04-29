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

脚本必须可重复运行，不能依赖未记录的手工步骤。

## 当前脚本

```sh
bash scripts/build-alpine-rootfs.sh --arch x86_64 --force
bash scripts/verify-alpine-rootfs.sh --rootfs artifacts/rootfs/alpine-x86_64
```

Windows 宿主建议通过 WSL 执行：

```powershell
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/build-alpine-rootfs.sh --arch x86_64 --force"
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/verify-alpine-rootfs.sh --rootfs artifacts/rootfs/alpine-x86_64"
```

说明：

- `x86_64` 用于当前开发机 chroot 验证。
- `aarch64` 是 Android/QEMU 目标架构，但跨架构安装包需要额外 emulator/binfmt 支持。
- 构建产物输出到 `artifacts/`，默认不提交 git。

## QEMU VM 验证

```powershell
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/build-qemu-image.sh --rootfs artifacts/rootfs/alpine-x86_64 --force"
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/verify-qemu-vm.sh --image artifacts/qemu/ai-linux-x86_64.ext4"
```

QEMU 验证会通过 SSH 跨 VM 边界重复检查 shell、网络、包管理、Python、Git、Node、`/workspace` 和 HTTP 服务。
