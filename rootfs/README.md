# rootfs

这里存放 Linux guest 文件系统的定义、构建脚本说明和发行版子目录。

当前主线是 `rootfs/alpine/`。

第一阶段 rootfs 的角色是承载 QEMU guest workspace。它应提供 shell、网络、git、Python/Node 和 agent 所需基础环境；workspace 语义由 guest 内 agent 和 `/workspace` 承担。

原则：

- rootfs 定义进 git
- rootfs 构建产物不进 git
- 大型产物输出到 `artifacts/`
- 每个新增包都要能解释为什么需要
