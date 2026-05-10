# QEMU 工作机边界与参考案例

本文落实 `05-open-questions.md` 中“借鉴成熟实现而不是照抄”的第二个开放问题。

本轮之后，“沙箱”只作为执行边界的简称。第一阶段正式目标收缩为 [QEMU Guest Workspace Runtime](07-qemu-guest-workspace-runtime.md)：Android 启动一台固定的 QEMU Linux guest，guest 内预装 agent 和工具链，Android 只做薄控制和观察。

用户侧体验进一步由 [第一阶段 Android Linux 电脑体验](08-first-stage-android-linux-computer-ux.md) 固化：Android App 只呈现开机、暂停、恢复、关机、终端和基础状态。

## 当前设计目标

第一阶段不是完整安全产品，也不是桌面 Linux。它的目标是给 AI agent 一台可启动、可观察、可恢复的 Linux 工作机。

必须满足：

- 普通 Android App 可运行，不依赖 root、fastboot、系统分区修改或 Linux 桌面。
- 执行环境与宿主 UI 分离，Linux guest 不承担 GUI。
- QEMU 是第一阶段唯一成熟执行底座，不再继续寻找第二套沙箱引擎。
- guest 镜像承担 workspace、agent、工具链和项目版本语义。
- Android 只管理 Linux 开机/暂停/恢复/关机、terminal、日志、预览端口和恢复入口。
- 生成产物和用户工作区分离，`artifacts/`、APK、runtime、kernel、initramfs 和私钥不进 git。
- 每个非平凡变更必须有轮次记录、验收标准和可回滚 commit。

第一阶段明确不追求：

- 完整多租户安全隔离证明。
- Linux 桌面、systemd、Docker 编排或完整包管理平台。
- 自动权限市场、插件系统、长期记忆或多 agent。
- 直接复制 AVF、Firecracker、gVisor、Flatpak、Crostini 的平台实现。
- 先设计完整 action/event bridge 平台。

## 工作机边界模型

```text
Android thin controller
  ├── prepare inputs
  ├── start / stop QEMU
  ├── pause / resume QEMU
  ├── terminal view
  ├── show process and serial logs
  ├── open forwarded preview ports
  └── trigger restore from known image

QEMU runtime
  ├── qemu-system-aarch64
  ├── fixed launch arguments
  ├── fixed kernel / firmware / initramfs or disk image
  └── user networking / hostfwd

Linux guest workspace
  ├── /workspace
  ├── agent or Codex-compatible worker
  ├── shell / python / node / git / curl
  ├── lightweight service process
  └── workspace logs and git commits
```

第一阶段 Android 端控制面应保持很小：

```text
prepareInputs()
startVm()
pauseVm()
resumeVm()
stopVm()
openTerminal()
showLogs()
openPreviewPort(port)
runReadinessProbe()
restoreKnownImage()
```

当前 SSH 只能归类为 readiness probe 和临时控制通道。后续可以替换为串口命令、vsock、virtio-serial、HTTP/gRPC guest agent 或其他最小通道。它不承担完整 workspace 协议。

## 可参考工程案例

| 工程案例 | 借鉴什么 | 不照抄什么 | 对本项目的适配 |
|---|---|---|---|
| Android Virtualization Framework / Microdroid | VM 生命周期管理、每个 VM 独立进程、宿主通过服务接口 start/monitor/stop、vsock 通信、debug/production 模式分离。 | 不把 AVF 作为 Android 12+ 普通 App 第一阶段依赖；不依赖系统权限、pKVM、AIDL 系统服务或 Microdroid OS image。 | 借鉴生命周期和宿主/guest 分层；当前固定为 QEMU 子进程。 |
| Firecracker | 极简 VMM、显式控制面、jailer/资源限制思路、小设备模型、减少攻击面。 | 不复制 KVM、jailer、cgroups、Linux namespace 实现；Android 普通 App 没有这些宿主权限。 | 借鉴“只暴露必要设备和 API”的设计；QEMU 参数必须被 VM manager 封装，不能泄漏到业务层。 |
| gVisor | 把 guest 行为和 host syscall 面隔离，使用 Sentry/Gofer 分层处理执行与文件系统访问。 | 不实现用户态内核，不重写 Linux syscall 层。 | 借鉴“guest 内执行不等于宿主权限”的边界意识；实际 workspace 语义留在 guest 内 agent。 |
| Flatpak | 静态权限最小化、portal 作为受控越界访问、避免 blanket filesystem access。 | 不复制桌面 portal、D-Bus、XDG runtime 或 GUI 权限模型。 | 默认只让 guest 操作 `/workspace`；host 文件导入后置，不作为第一阶段主能力。 |
| ChromeOS Crostini / Termina | VM 承担安全边界，容器承载 Linux userland，宿主负责文件集成、终端、生命周期和恢复。 | 不复制 ChromeOS 系统服务、LXD、多容器管理或 crosvm 依赖。 | 借鉴“宿主集成层 + VM + user workspace”的分层；Android UI 只做控制和预览，不进 Linux GUI。 |

参考资料：

- Android AVF overview: https://source.android.com/docs/core/virtualization
- Android VirtualizationService: https://source.android.com/docs/core/virtualization/virtualization-service
- Android Microdroid: https://source.android.com/docs/core/virtualization/microdroid
- Firecracker: https://github.com/firecracker-microvm/firecracker
- Firecracker jailer: https://github.com/firecracker-microvm/firecracker/blob/main/docs/jailer.md
- gVisor architecture introduction: https://gvisor.dev/docs/architecture_guide/intro/
- gVisor security model: https://gvisor.dev/docs/architecture_guide/security/
- Flatpak sandbox permissions: https://docs.flatpak.org/en/latest/sandbox-permissions.html
- ChromeOS security whitepaper: https://www.chromium.org/chromium-os/developer-library/reference/security/security-whitepaper/
- ChromiumOS containers and VMs: https://chromium.googlesource.com/chromiumos/docs/+/HEAD/containers_and_vms.md

## 第一阶段适配原则

### 1. 控制面和工作面分离

Android 侧只管理 Linux 开机/暂停/恢复/关机、terminal、日志、端口和恢复入口。Linux guest 负责 agent、workspace、文件、命令和项目版本。

错误做法：

- UI 直接拼 QEMU 命令。
- Android 重新实现 workspace 文件/版本语义。
- 把 SSH/JSch/dropbear 当成长期产品协议。
- guest 内脚本反过来修改 Android 工作台结构。

正确方向：

- Android controller 负责启动、停止、状态和端口。
- QEMU runtime 固定版本和启动参数。
- guest agent 负责 `/workspace`、命令、文件、日志和 git。

### 2. 默认最小权限

第一阶段允许网络和本地服务，是为了验证 AI 工作空间，不是因为所有任务都天然需要这些能力。

默认策略：

- guest 只默认写 `/workspace`。
- host 文件导入后置，第一阶段不作为核心路径。
- host 端口转发必须显式登记。
- Android 只展示 VM 日志、agent 日志和预览端口，不重新定义项目日志系统。
- 外部 runtime、kernel、initramfs、私钥全部作为 ignored 输入。

### 3. 工具链 workaround 不进入核心协议

当前 Android spike 里存在 Termux QEMU runtime、APK native library 注入、`romfile=`、PEM ECDSA key 等兼容处理。这些只能属于工具链层和验收层。

核心协议不能出现：

- Termux package 细节。
- QEMU CLI 参数细节。
- JSch API 细节。
- `id_ed25519` 这种兼容文件名。

### 4. 先冻结 guest 工作机，再扩展 UI

前端工作台和 WebView 可以存在，但必须挂在一台可重复启动、可观察、可恢复的 QEMU guest 工作机之后。

第一阶段优先冻结：

- 固定 QEMU 版本。
- 固定 guest 镜像。
- 固定启动参数。
- 固定 `/workspace`。
- 固定 readiness probe。
- 固定预览端口策略。

没有这些运行时边界，不扩大 WebView、block tree 或自由画布实现面。

## 后续工程任务

1. 固化 QEMU runtime 版本、来源、许可证和依赖闭包。
2. 固化一个可重复构建的 guest 镜像，并在镜像内预装 agent 和工具链。
3. 把 Android spike 文档改成“薄控制层 + 验收通道”，明确 SSH/JSch/dropbear 只是 readiness probe。
4. 统一 SSH identity 命名，避免 `id_ed25519` 与 PEM ECDSA 实际格式不一致。
5. 在正式 UI 工作台前，先证明 guest 内 agent 能稳定管理 `/workspace`。
