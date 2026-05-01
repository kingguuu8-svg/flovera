# 沙箱设计目标与参考案例

本文落实 `05-open-questions.md` 中“借鉴成熟实现而不是照抄”的第二个开放问题。

目标不是重新发明沙箱，而是把成熟工程里的稳定抽象迁移到本项目的 Android 普通 App 约束下。

## 当前设计目标

第一阶段沙箱不是完整安全产品，也不是桌面 Linux。它的目标是给 AI agent 一个可执行、可观察、可回滚、可替换底层的最小 Linux 工作空间。

必须满足：

- 普通 Android App 可运行，不依赖 root、fastboot、系统分区修改或 Linux 桌面。
- 执行环境与宿主 UI 分离，Linux guest 不承担 GUI。
- 所有长期控制能力抽象成接口，不把 QEMU、SSH、dropbear、JSch 写成产品协议。
- 文件、命令、网络、日志、端口、版本和错误都必须可被宿主侧观察。
- 生成产物和用户工作区分离，`artifacts/`、APK、runtime、kernel、initramfs 和私钥不进 git。
- 每个非平凡变更必须有轮次记录、验收标准和可回滚 commit。

第一阶段明确不追求：

- 完整多租户安全隔离证明。
- Linux 桌面、systemd、Docker 编排或完整包管理平台。
- 自动权限市场、插件系统、长期记忆或多 agent。
- 直接复制 AVF、Firecracker、gVisor、Flatpak、Crostini 的平台实现。

## 沙箱边界模型

```text
Android / Host control plane
  ├── VM lifecycle manager
  ├── action / bridge interface
  ├── file import / export boundary
  ├── log and event collector
  ├── port forward registry
  ├── resource policy
  └── snapshot / rollback controller

Linux execution sandbox
  ├── minimal rootfs
  ├── /workspace
  ├── shell / python / node / git / curl
  ├── lightweight service process
  ├── guest agent or temporary SSH bridge
  └── stdout / stderr / status output
```

长期接口应围绕能力定义，而不是围绕具体命令定义：

```text
start()
stop()
status()
exec(action)
writeFile(path, content)
readFile(path)
listFiles(path)
getLogs()
forwardPort(guestPort)
snapshot()
rollback(snapshotId)
```

当前 SSH 只能归类为 readiness probe 和临时控制通道。后续可以替换为 vsock、virtio-serial、HTTP/gRPC guest agent 或其他 bridge。

## 可参考工程案例

| 工程案例 | 借鉴什么 | 不照抄什么 | 对本项目的适配 |
|---|---|---|---|
| Android Virtualization Framework / Microdroid | VM 生命周期管理、每个 VM 独立进程、宿主通过服务接口 start/monitor/stop、vsock 通信、debug/production 模式分离。 | 不把 AVF 作为 Android 12+ 普通 App 第一阶段依赖；不依赖系统权限、pKVM、AIDL 系统服务或 Microdroid OS image。 | 保留 `VM manager + bridge + event` 抽象；当前用 QEMU 子进程模拟，未来可替换为 AVF/crosvm。 |
| Firecracker | 极简 VMM、显式控制面、jailer/资源限制思路、小设备模型、减少攻击面。 | 不复制 KVM、jailer、cgroups、Linux namespace 实现；Android 普通 App 没有这些宿主权限。 | 借鉴“只暴露必要设备和 API”的设计；QEMU 参数必须被 VM manager 封装，不能泄漏到业务层。 |
| gVisor | 把 guest 行为和 host syscall 面隔离，使用 Sentry/Gofer 分层处理执行与文件系统访问。 | 不实现用户态内核，不重写 Linux syscall 层。 | 借鉴“执行面”和“文件桥”分层：guest 内命令执行不能直接等同于宿主文件权限。 |
| Flatpak | 静态权限最小化、portal 作为受控越界访问、避免 blanket filesystem access。 | 不复制桌面 portal、D-Bus、XDG runtime 或 GUI 权限模型。 | 把 host 文件导入/导出设计成显式 action；默认只让 guest 操作 `/workspace`。 |
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

### 1. 控制面和执行面分离

宿主侧只管理生命周期、文件边界、日志、端口和状态。Linux guest 只执行命令和运行服务。

错误做法：

- UI 直接拼 QEMU 命令。
- 业务层直接依赖 SSH/JSch。
- guest 内脚本反过来修改宿主工作台结构。

正确方向：

- `VmManager` 负责启动、停止、状态和端口。
- `Bridge` 负责 `exec/readFile/writeFile/getLogs`。
- `Workspace` 负责文件、版本和回滚。

### 2. 默认最小权限

第一阶段允许网络和本地服务，是为了验证 AI 工作空间，不是因为所有任务都天然需要这些能力。

默认策略：

- guest 只默认写 `/workspace`。
- host 文件必须显式导入。
- host 端口转发必须显式登记。
- 运行日志必须可追踪到 action 或 service。
- 外部 runtime、kernel、initramfs、私钥全部作为 ignored 输入。

### 3. 工具链 workaround 不进入核心协议

当前 Android spike 里存在 Termux QEMU runtime、APK native library 注入、`romfile=`、PEM ECDSA key 等兼容处理。这些只能属于工具链层和验收层。

核心协议不能出现：

- Termux package 细节。
- QEMU CLI 参数细节。
- JSch API 细节。
- `id_ed25519` 这种兼容文件名。

### 4. 先冻结最小 bridge，再扩展 UI

前端工作台和 WebView 可以存在，但必须挂在结构化控制协议之后。

第一阶段优先冻结：

- `exec`
- `readFile`
- `writeFile`
- `listFiles`
- `getLogs`
- `startService`
- `stopService`
- `forwardPort`
- `snapshot`
- `rollback`

没有这些接口边界，不扩大 WebView、block tree 或自由画布实现面。

## 后续工程任务

1. 在 `bridge/README.md` 中定义第一版 bridge capability，而不是继续让 Android spike 隐式定义控制协议。
2. 把 Android spike 文档改成“参考实现 + 验收通道”，明确 SSH/JSch/dropbear 不是长期协议。
3. 统一 SSH identity 命名，避免 `id_ed25519` 与 PEM ECDSA 实际格式不一致。
4. 增加一份 sandbox threat model，但只描述第一阶段真实边界，不夸大安全承诺。
5. 在正式 UI 工作台前，先定义 action schema 和事件 schema。
