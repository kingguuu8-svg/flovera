# 最小 Linux 规格

## 设计目标

目标不是做体积极限最小的 Linux，而是做 AI 可用的最小 Linux。

判断标准：

```text
足够小
+ 能联网
+ 能安装/扩展
+ 能运行脚本和小服务
+ 能被宿主控制
= 最小可用 AI Linux
```

## 必备能力

| 能力 | 说明 |
|---|---|
| Shell | AI 执行命令的入口 |
| 文件系统 | AI 创建、修改、组织项目 |
| 网络 | 下载依赖、访问 API、测试服务 |
| HTTPS 证书 | 保证包管理器、curl、git 能访问 HTTPS |
| 包管理 | 允许后续按需扩展工具 |
| 运行时 | 至少内置 Python，Node 可作为增强项 |
| 版本管理 | 用 Git 支撑 diff、快照、回滚 |
| 日志 | stdout/stderr/exit code 可被宿主侧读取 |

## 推荐基础包

| 包 | 优先级 | 作用 |
|---|---:|---|
| `busybox` / `sh` | 必须 | 基础命令和 shell |
| `ca-certificates` | 必须 | HTTPS 根证书 |
| `curl` | 必须 | 网络请求和调试 |
| `python3` | 强烈建议 | 脚本、小服务、数据处理 |
| `git` | 强烈建议 | 版本、diff、回滚 |
| `nodejs` | 建议 | Web 工具和前端生态 |
| `openssh-client` | 可后置 | 远程连接和私有仓库 |
| `dropbear` | VM 验证阶段需要 | 轻量 SSH server，用作宿主到 guest 的命令桥 |

## 不包含内容

| 内容 | 原因 |
|---|---|
| GUI | 第一阶段不面向人操作桌面 |
| X11/Wayland | 无关且重 |
| systemd | 重，且不适合最小环境 |
| 多用户管理 | 当前只需要单工作区 |
| Docker | 依赖复杂，移动端成本高 |
| 大型 IDE | 后续由 Android/前端提供展示层 |

## 最低验收命令

```sh
sh -c 'echo ready'
python3 --version
git --version
curl -I https://example.com
mkdir -p /workspace && echo ok > /workspace/test.txt
python3 -m http.server 8000
```
