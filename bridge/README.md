# bridge

这里记录 Android thin controller 与 Linux guest 之间的最小可替换通道。

第一阶段不先实现完整产品级 bridge。主线是 QEMU 启动固定 Linux guest，guest 内 agent 负责 `/workspace`、文件、命令、日志和 git 版本。

当前 bridge 只需要支持：

- readiness probe
- terminal session
- VM/agent 日志读取
- 预览端口暴露
- 少量人工触发的 guest 命令

可选 transport：

- SSH/JSch/dropbear
- 串口命令
- vsock
- virtio-serial
- HTTP/gRPC guest agent

这些 transport 都不能成为业务层协议。业务语义优先留在 guest 内 agent；只有当 guest 内 agent 稳定后，再决定是否把 bridge 扩展为结构化 action/event 协议。

第一阶段用户侧不暴露 bridge 概念。用户看到的是 Linux terminal；bridge 只是后台把 Android terminal 和 guest shell 接起来。
