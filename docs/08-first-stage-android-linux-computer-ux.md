# 第一阶段 Android Linux 电脑体验

第一阶段的用户侧目标是：

```text
Android 上的一台本地 Linux 电脑。
用户可以开机、暂停、恢复、关机，并像连接 VPS 一样操作终端。
```

QEMU、SSH、QMP、端口转发、日志归集、网络参数和镜像路径都是后台实现细节。用户不需要理解这些概念，除非系统出错并需要展示诊断信息。

## 用户看到什么

第一阶段界面只围绕一台 Linux 电脑展开：

| 用户能力 | 用户侧表现 |
|---|---|
| 开启 Linux | `Start Linux` |
| 暂停 Linux | `Pause` |
| 恢复 Linux | `Resume` |
| 关闭 Linux | `Shutdown` |
| 操作终端 | 一个可输入命令的 terminal |
| 查看状态 | `starting` / `running` / `paused` / `stopped` / `error` |
| 查看必要错误 | 启动失败、镜像缺失、终端连接失败、网络不可用 |

第一阶段不把 QEMU 管理面板暴露给用户。用户使用的是 Linux，不是虚拟机控制台产品。

## 后台负责什么

后台需要把复杂性消化掉：

| 后台能力 | 用户如何感知 |
|---|---|
| QEMU 进程启动 | Linux 开机 |
| QEMU pause/resume | Linux 暂停/恢复 |
| QEMU 进程退出或关机 | Linux 关闭 |
| SSH/serial/agent 通道 | terminal 可输入命令 |
| user networking / hostfwd | Linux 能联网、服务能预览 |
| stdout/stderr/serial 日志 | 出错时可诊断 |
| 镜像和输入文件检查 | 缺失时给出明确错误 |
| 固定启动参数 | 用户无需配置 |

后台可以使用 SSH、串口、QMP、QEMU monitor、端口转发或 guest agent，但这些都不应成为用户要操作的概念。

## 终端体验

终端是第一阶段的主体验。

用户应该可以直接输入普通 Linux 命令：

```sh
pwd
ls
cd /workspace
python3 --version
git --version
curl -I https://example.com
python3 -m http.server 8000
```

终端行为应接近 VPS：

- 命令在 guest Linux 内执行。
- 默认工作目录应是 `/workspace` 或能快速进入 `/workspace`。
- 终端断开后可以重新连接。
- Linux 暂停时终端应停止响应或显示 paused 状态。
- Linux 恢复后终端应能继续连接或自动重连。
- Linux 关闭后终端应明确显示 disconnected/stopped。

## 第一阶段验收流程

```text
1. 打开 Android App
2. 点击 Start Linux
3. Linux 状态进入 running
4. terminal 可输入命令
5. 在 terminal 中执行 pwd / ls / python3 --version
6. 点击 Pause
7. Linux 状态进入 paused
8. 点击 Resume
9. terminal 恢复可用
10. 点击 Shutdown
11. Linux 状态进入 stopped
```

增强验收：

- `curl -I https://example.com` 可返回 HTTPS 响应。
- `python3 -m http.server 8000` 可被 Android 侧预览入口打开。
- `/workspace` 的持久化策略符合当前版本定义。
- 出错时能看到用户可理解的错误，而不是裸 QEMU 参数。

## 第一阶段不做

- 不做 Linux 桌面。
- 不做完整虚拟机管理器。
- 不暴露 QEMU 参数编辑器。
- 不做多机型兼容承诺。
- 不做复杂快照 UI。
- 不做完整 AI 工作台。
- 不要求用户理解 SSH、QMP、hostfwd、serial console。

## 对 Android spike 的影响

后续 Android spike 的 UI 语义应从技术验证按钮转向用户语义：

| 当前 spike 语义 | 第一阶段用户语义 |
|---|---|
| `Prepare Assets` | 后台自动准备，必要时显示 `Prepare Linux` |
| `Start VM` | `Start Linux` |
| `Stop VM` | `Shutdown` |
| `Run echo ready` | 后台 readiness check |
| 日志输出区 | 普通用户默认隐藏，错误时展开 |

新增用户侧能力：

- `Pause`
- `Resume`
- `Terminal`

## QEMU 功能映射

第一阶段需要的 QEMU 能力仍然很少：

| QEMU 能力 | 产品语义 |
|---|---|
| 启动 QEMU 进程 | Start Linux |
| 停止 QEMU 进程或 guest 关机 | Shutdown |
| QMP `stop` | Pause |
| QMP `cont` | Resume |
| QMP `query-status` | Linux 状态 |
| `-serial stdio` | 启动日志和救援通道 |
| `-netdev user,hostfwd=...` | SSH/terminal 和服务预览 |
| kernel/initrd/disk 输入 | 固定 Linux 系统 |

这些能力是为了支撑“像用一台电脑一样用 Linux”，不是为了把 QEMU 功能完整展示给用户。
