# 仓库处理流程

## 分支规则

| 分支类型 | 用途 | 示例 |
|---|---|---|
| `main` | 已验证基线 | `main` |
| `work/<topic>` | 正常开发 | `work/rootfs-alpine` |
| `exp/<topic>` | 实验路线 | `exp/buildroot-rootfs` |
| `fix/<topic>` | 修复问题 | `fix/qemu-network` |

`main` 不直接承接未验证实现。任何会改变已验证状态的工作，必须先新建分支。

## 文件修改流程

修改已有文件前必须执行：

```text
确认当前分支
  ↓
确认文件是否已有内容
  ↓
复制原文件到 .backups/YYYYMMDD-HHMMSS/<relative-path>
  ↓
再修改原文件
  ↓
运行对应验证
  ↓
提交变更
```

新增文件不需要备份，但必须放入既有目录规划中。如果目录规划不合适，先更新仓库结构文档。

## 提交流程

每次提交应满足：

- 变更范围单一
- commit message 说明目的
- 文档和实现保持同步
- 生成产物不提交，除非是小型、必要、可审查的样例

推荐提交信息：

```text
docs: define phase 1 linux runtime scope
build: add alpine rootfs builder
vm: add qemu launch script
bridge: add command execution protocol
```

## 验证流程

不同阶段的最低验证标准：

| 阶段 | 最低验证 |
|---|---|
| 文档阶段 | 仓库结构和路线可解释 |
| rootfs 阶段 | 能生成 rootfs tarball |
| VM 阶段 | 能启动 shell |
| 网络阶段 | 能访问 HTTPS |
| 服务阶段 | 能启动 HTTP 服务并从宿主访问 |
| 持久化阶段 | `/workspace` 重启后不丢失 |

## 产物规则

大型构建产物放入 `artifacts/`，默认不提交 git：

- rootfs tarball
- ext4 镜像
- qcow2 镜像
- kernel/initramfs 组合产物
- 临时日志

如果某个产物必须进入版本控制，需要先在文档中说明理由。

