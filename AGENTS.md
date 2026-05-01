# AGENTS.md

本文件规定本仓库内 AI 协作和代码处理规则。

## 第一性原则

- 不假设用户需求天然正确；需求模糊时先澄清，需求明确时直接执行。
- 主动指出误解、技术风险和更短路径。
- 遇到问题追根因，不用临时补丁掩盖问题。
- 每个技术决策都必须能回答“为什么”。
- 输出只保留影响决策的信息。

## 项目原则

- 第一阶段主线是 QEMU guest workspace runtime：Android 控制 QEMU，QEMU 启动固定 Linux guest，guest 内 agent 管理 `/workspace`。
- 第一阶段不做可发布 APK、不做 Linux GUI、不做完整可视化工作台；Android spike 只作为薄控制层和验收通道。
- 第一阶段用户侧体验是一台本地 Linux 电脑：Start Linux、Pause、Resume、Shutdown、Terminal 和基础状态；QEMU、SSH、QMP、端口、日志和网络是后台实现细节。
- Android 12+ 是目标兼容范围，但当前只承诺参考真机和模拟器验收，不承诺多机型适配。
- 主线为 Alpine minimal guest + QEMU system VM + guest agent workspace。
- `proot` 只作为快速验证和对照路线。
- AVF/pKVM 只作为长期路线预留，不作为第一阶段阻塞点。
- PyTorch 如后续需要，只允许使用 CUDA 版；当前阶段默认不引入 PyTorch。

## 复用和修改规则

- 优先复用已有文件和目录，不创建无意义重复版本。
- 修改已验证版本前，必须从 `main` 新建工作分支。
- 历史、回滚和对比统一依赖 git 分支、commit、diff 和 tag，不维护手工备份副本。
- 禁止擅自删除、重写或回滚用户已有改动。
- 不允许用 `git reset --hard`、`git checkout -- <file>` 等破坏性命令，除非用户明确要求。

## Git 规则

- `main` 只保存已验证基线。
- 日常修改使用 `work/<topic>` 分支。
- 实验性路线使用 `exp/<topic>` 分支。
- 重要可运行状态使用 tag 标记，例如 `verified/rootfs-v0.1`。
- 每次提交必须说明变更目的，不允许“misc update”。

## 命令规则

- 本环境执行 shell 命令时使用 `rtk` 前缀。
- 等待长时间命令时，不使用 `sleep 30s` 循环轮询。
- 对构建和验证命令保留可复现记录，优先写入 `scripts/`。
