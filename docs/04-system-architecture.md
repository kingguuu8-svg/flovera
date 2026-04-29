# 系统架构

## 第一阶段抽象

```text
Host / Android future wrapper
  ├── VM manager
  ├── command bridge
  ├── file bridge
  ├── log bridge
  ├── port forwarder
  └── artifact manager

Linux guest
  ├── /workspace
  ├── shell
  ├── package manager
  ├── python/node/git/curl
  ├── lightweight service process
  └── stdout/stderr logs
```

## 模块职责

| 模块 | 职责 |
|---|---|
| `rootfs/` | 定义和构建 Linux 文件系统 |
| `vm/` | 定义虚拟机启动方式 |
| `bridge/` | 未来定义宿主和 guest 的控制协议 |
| `android/` | 未来放 Android 包装层 |
| `scripts/` | 放可复现构建和验证脚本 |
| `examples/` | 放最小验证用例 |
| `artifacts/` | 放本地生成产物，不进 git |

## 未来控制接口

后续 Android 或宿主侧不应直接依赖 QEMU 细节，而应依赖抽象接口：

```text
init()
start()
stop()
exec(command)
writeFile(path, content)
readFile(path)
listFiles(path)
getLogs()
forwardPort(guestPort, hostPort)
snapshot()
rollback(snapshotId)
```

这样底层可以从 QEMU 替换为 AVF、crosvm、proot 或其他方案。

