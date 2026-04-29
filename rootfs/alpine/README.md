# Alpine Rootfs

本目录用于 Alpine minimal rootfs 路线。

目标包集合：

```text
ca-certificates
curl
python3
git
nodejs
openssh-client
```

`busybox` 和 `sh` 来自 Alpine minirootfs 基线，不在 `packages.txt` 中重复声明。

第一版构建脚本后续放在 `scripts/`，本目录只保存 Alpine 路线的配置和说明。

## 当前文件

- `packages.txt`：第一阶段最小可用包集合。

## 构建和验证

```sh
bash scripts/build-alpine-rootfs.sh --arch x86_64 --force
bash scripts/verify-alpine-rootfs.sh --rootfs artifacts/rootfs/alpine-x86_64
```

当前先验证 host 架构 rootfs，后续 QEMU 阶段再验证 Android 目标的 `aarch64` VM 启动链路。
