# GitHub 展示页初稿（中文）

这是公开 `README.md` 的中文初稿，用于确认叙事和信息结构。暂时不要直接替换公开 README；等截图/GIF、APK 产物和发布口径确认后再应用。

---

# Flovera

Flovera 是一个运行在 Android 本地的 workspace agent。

你可以让它创建一个小工具、小游戏、表格、报告或本地 Web demo。Flovera 会把文件写进 Android 本地的受控 workspace，检查生成结果，并用 WebView 打开 HTML 产物，让你可以直接在手机上继续修改和迭代。

> Flovera Preview：这是一个用于体验 Android 本地 workspace agent 工作流的早期公开版本。它适合作为本地 demo 工作台使用，但不是 VPS 替代品，也不承诺生产级长期后台自动化。

![Flovera demo placeholder](docs/assets/flovera-demo-placeholder.png)

## 适合做什么

- 生成面向移动端的 HTML demo，并在 Android WebView 中打开。
- 创建小工具、小游戏、仪表盘和交互原型。
- 在手机本地 workspace 中创建、查看和修改文件。
- 用受控 Python 生成表格、文档和结构化产物。
- 保留会话历史，围绕同一个产物持续迭代。

## 工作方式

1. 在 Android 上打开 Flovera。
2. 告诉 agent 你想创建什么小产物。
3. Flovera 把文件写入本地 workspace。
4. agent 使用 `artifact_diagnose` 检查生成的 Flovera 应用是否可注册、可预览。
5. 在 WebView 中打开结果，或直接预览支持的文件格式。
6. 继续对话，让 agent 修改和完善产物。

## 当前 Preview 能力

- 持久化会话和对话记录。
- workspace 文件读取、写入和搜索工具。
- workspace 快照，用于更安全地迭代和回退。
- Markdown 对话渲染，工具和状态输出会更紧凑地展示。
- HTML / WebView 预览，以及 workspace local HTTP 预览。
- 使用 `flovera.app.json` 描述可预览的 workspace app。
- 支持 workspace 自有的 Python HTTP 后端，用于本地交互 demo。
- 受控 Python runtime，用于本地生成和验证文件。
- 支持预览 HTML、Markdown、JSON、CSV、文本、代码、图片和 PDF。
- 模型 provider 配置保存在 app 设置中，不写入源码。
- 网络工具默认开启，并可在 Settings 中关闭。
- 配置 Brave Search API key 后可使用 Web Search。
- 可选的前台服务后台保持，用于较长时间的本地工作。

## 为什么是 Android 本地

很多 coding agent 像是运行在别处：云端 VM、Web IDE 或桌面 shell。Flovera 探索的是一个更小、更本地的闭环：手机自己拥有 workspace、预览界面、权限边界和会话历史。

当目标不是生产部署，而是一个 demo、本地产物、移动端可阅读的 Web 页面或可交互原型时，这种闭环会更直接。

## 边界

- Flovera 不承诺任意后台自动化。
- Android 后台行为仍受系统和厂商策略影响。
- 生成的 demo 仍可能需要多轮迭代。
- Android WebView 和桌面浏览器存在兼容差异。
- MCP、Git 和通用 shell 类工具不属于首个 Preview 版本边界。
- provider API key 和 app 权限属于 Flovera app 设置，不是 workspace 源码文件。

## 构建

要求：

- Android Studio 或 Android SDK。
- JDK 17。Windows 上推荐使用 Android Studio 自带 JBR。

在 `android/spike` 下执行：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

.\gradlew.bat :app:assembleFloveraDebug :app:assembleFloveraDebugAndroidTest
```

## 验证

建议使用受保护的 verifier 或 update-only APK 流程。不要在验证时随意卸载已有 Flovera app，因为 app 数据、权限和会话状态本身就是产品状态的一部分。

## License

Flovera 使用 MIT License。

---

## 素材 TODO

- 用真实 15-30 秒 GIF 替换占位图：
  对话 -> 文件生成 -> artifact 诊断 -> WebView 预览。
- 增加一张静态截图，作为 README 的备用展示图。
- 增加 `Flovera Preview` 标识。

