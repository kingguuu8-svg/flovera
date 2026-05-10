# ADR 0001: 第一阶段采用 Alpine + QEMU 主线

## 状态

Accepted

## 背景

项目目标是在 Android 12+ 普通设备方向上，为 AI Agent 提供一台可被 Android 控制的最小 Linux 工作机。第一阶段不做通用 Android 虚拟化产品，而是在当前开发环境中先制作和验证 QEMU guest 工作环境。

## 决策

第一阶段主线采用：

```text
Alpine minimal guest + QEMU system VM + guest agent workspace
```

## 原因

- Alpine 足够小，同时保留 `apk` 包管理器。
- QEMU 是真 VM，不依赖 AVF 系统权限。
- 该路线更符合“Android 可控制的一台 Linux 工作机”概念。
- workspace、文件、命令、日志和 git 版本优先交给 guest 内 agent 和 Linux 工具链。
- 先验证 guest 镜像、网络、服务、持久化和 agent，再处理 Android 包装层。

## 被排除的主线

| 路线 | 不作为主线的原因 |
|---|---|
| proot | 不是真 VM，只适合作为备用验证 |
| AVF/pKVM | Android 12+ 普通第三方 App 不应依赖它作为基础能力 |
| Buildroot | 初期过早，会牺牲包管理和 AI 自主扩展能力 |
| Debian slim | 兼容性好，但第一阶段轻量目标不如 Alpine |

## 后果

- 第一阶段会优先设计 guest 镜像、QEMU 启动链路和 agent 工作目录。
- 构建脚本应优先保证 QEMU runtime、guest 镜像和输入文件布局可复现。
- 如果 Alpine 的 musl 兼容性成为阻塞，再引入 Debian slim 对照路线。
