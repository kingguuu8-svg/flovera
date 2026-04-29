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

