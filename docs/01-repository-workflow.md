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

本仓库采用按提交计算的开发轮次流程：一个非平凡 commit 必须对应一个开发轮次。

轮次文档入口：

- `docs/rounds/README.md`
- `docs/rounds/current-round.md`
- `docs/rounds/round-timeline.md`
- `docs/rounds/development-findings.md`
- `docs/rounds/idea-backlog.md`

修改已有文件前必须执行：

```text
确认当前分支
  ↓
确认工作区状态
  ↓
确认修改范围和影响文件
  ↓
修改文件
  ↓
查看 git diff
  ↓
运行对应验证
  ↓
提交变更
```

新增文件必须放入既有目录规划中。如果目录规划不合适，先更新仓库结构文档。

仓库不维护 `.backups/` 手工备份目录。历史、回滚和对比统一通过 git 完成：

- 分支用于隔离未验证工作
- commit 用于保存可追踪状态
- diff 用于审查修改
- tag 用于标记重要可运行版本

## 开发轮次流程

一轮等于一个非平凡 commit。轮次用于把目标、范围、验收和结论绑定到提交上，避免把新想法、临时补丁和主线开发混在一起。

每轮开始前必须更新 `docs/rounds/current-round.md`，写清：

- 本轮问题
- 本轮只做什么
- 本轮不做什么
- 影响文件范围
- 验收标准
- 预期 commit message

每轮结束前必须更新：

- `docs/rounds/round-timeline.md`
- 必要时更新 `docs/rounds/development-findings.md`
- 必要时把新想法放入 `docs/rounds/idea-backlog.md`

没有轮次记录的非平凡 commit，视为流程不完整。

小型错别字、格式修正可以标记为 `trivial`，不要求完整轮次，但 commit message 必须明确说明这是 trivial 变更。

## 提交流程

每次提交应满足：

- 变更范围单一
- commit message 说明目的
- 文档和实现保持同步
- 非平凡 commit 已对应一个开发轮次
- 生成产物不提交，除非是小型、必要、可审查的样例

推荐提交信息：

```text
docs: define phase 1 linux runtime scope
build: add alpine rootfs builder
vm: add qemu launch script
guest: add agent startup plan
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
