# ADR 0001: 第一阶段采用 Alpine + QEMU 主线

## 状态

Accepted

## 背景

项目目标是在 Android 12+ 普通设备方向上，为 AI Agent 提供一个最小 Linux 执行空间。第一阶段不做 APK，而是在当前开发环境中先制作和验证 Linux 系统本身。

## 决策

第一阶段主线采用：

```text
Alpine minimal rootfs + QEMU system VM
```

## 原因

- Alpine 足够小，同时保留 `apk` 包管理器。
- QEMU 是真 VM，不依赖 AVF 系统权限。
- 该路线更符合“AI 的最小 Linux 虚拟机”概念。
- 先验证 rootfs、网络、服务、持久化，再处理 Android 包装层。

## 被排除的主线

| 路线 | 不作为主线的原因 |
|---|---|
| proot | 不是真 VM，只适合作为备用验证 |
| AVF/pKVM | Android 12+ 普通第三方 App 不应依赖它作为基础能力 |
| Buildroot | 初期过早，会牺牲包管理和 AI 自主扩展能力 |
| Debian slim | 兼容性好，但第一阶段轻量目标不如 Alpine |

## 后果

- 第一阶段会优先设计 rootfs 和 QEMU 启动链路。
- 构建脚本应保留未来替换 VM backend 的接口边界。
- 如果 Alpine 的 musl 兼容性成为阻塞，再引入 Debian slim 对照路线。

