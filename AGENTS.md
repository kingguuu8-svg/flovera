# AGENTS.md

本文件规定本仓库内 AI 协作和代码处理规则。

## 第一性原则

- 不假设用户需求天然正确；需求模糊时先澄清，需求明确时直接执行。
- 主动指出误解、技术风险和更短路径。
- 遇到问题追根因，不用临时补丁掩盖问题。
- 每个技术决策都必须能回答“为什么”。
- 输出只保留影响决策的信息。

## 当前项目方向

- 当前主线是 Flovera：Android-local workspace agent app。
- Android app 负责会话、workspace、WebView 展示、权限、设置和 agent 运行入口。
- Koog 是当前 agent runtime 上游框架。
- 旧 QEMU/VPS 路线只作为归档研究材料，位于 `docs/archive/legacy-qemu-vps/`。

## 复用和修改规则

- 优先复用已有文件和目录，不创建无意义重复版本。
- 历史、回滚和对比统一依赖 git 分支、commit、diff 和 tag，不维护手工备份副本。
- 禁止擅自删除、重写或回滚用户已有改动。
- 不允许用 `git reset --hard`、`git checkout -- <file>` 等破坏性命令，除非用户明确要求。
- PyTorch 如后续需要，只允许使用 CUDA 版。

## Git 规则

- 日常修改使用工作分支。
- 本地 commit 可以小步、频繁，用作可审查的工作单元。
- 每次 commit 必须说明改了什么以及为什么改，不允许 `misc update` 这类空泛信息。
- 每次 commit 后向用户说明变更内容和原因。
- 远端 push 必须经过用户对该次 push 的明确同意；不要擅自 push。
- push 应代表较大的、连贯的版本检查点，不要每改一点就 push 一层。
- 不经用户明确同意，不创建或更新 GitHub Release。
- 保持最新 push 和 release 状态一致；如果创建或更新 release，它必须对应最新已推送版本。

## 真机验证规则

- 真机验证中，非必要不使用视觉点击方式操作。
- 构建功能时，要考虑命令、测试、语义节点、调试入口等非视觉点击验证方式。
- 不要使用会卸载用户主 app 或重置权限的验证路径。
- Android 设备验证优先使用 `android/spike/scripts/verify-flovera-android.ps1`。

## 命令规则

- 本环境执行 shell 命令时使用 `rtk` 前缀。
- 等待长时间命令时，不使用 `sleep 30s` 循环轮询。
- 对构建和验证命令保留可复现记录，优先写入 `scripts/` 或文档。
