<p align="center">
  <img src="docs/assets/flovera-app-icon.png" alt="Flovera app icon" width="132">
</p>

<h1 align="center">Flovera</h1>

<p align="center">
  <a href="README.md"><img src="docs/assets/badges/lang-en.svg" alt="English README"></a>
  <a href="LICENSE"><img src="docs/assets/badges/license-mit.svg" alt="MIT License"></a>
  <img src="docs/assets/badges/status-preview.svg" alt="Flovera Preview">
  <img src="docs/assets/badges/platform-android.svg" alt="Android-local">
  <img src="docs/assets/badges/deepseek-tested.svg" alt="Tested with DeepSeek API">
</p>

<p align="center">
  运行在 Android 本地的 workspace agent，用来在手机上创建和预览小型 demo。
</p>

Flovera 是一个运行在 Android 本地的 workspace agent 应用。

它会在手机上维护一个受控工作区，让 AI agent 在其中创建、读取、修改和检查文件，并用 Android WebView 打开生成的 HTML / Web 产物。第一个公开版本定位为 **Flovera Preview**：一个轻量的本地 demo 工作台，用来在手机上创建、预览和迭代可运行的小产物。

Flovera 不是 VPS 替代品，也不是通用手机自动化框架，更不承诺稳定的长期后台运行。它当前最有价值的部分，是把对话、文件、验证和预览放在同一台 Android 设备里，形成一个很短的闭环。

## 可以用来做什么

- 生成面向移动端的 HTML demo，并直接在 Android WebView 中打开。
- 创建小游戏、仪表盘、计算器、报告和交互原型。
- 生成和预览 Markdown、JSON、CSV、文本、代码、图片、PDF 等本地工作区产物。
- 在需要脚本、计算或结构化文件生成时，使用受控 Python runtime 辅助完成。

## 工作方式

1. 在 Android 上打开 Flovera。
2. 告诉 agent 你想创建什么小产物。
3. agent 在受控 workspace 内读取、写入、搜索和编辑文件。
4. Flovera 检查生成的 app manifest 和预览入口是否可用。
5. 在应用内 WebView 或文件预览中打开结果。
6. 继续对话，让 agent 修改和完善产物。

核心循环是：

```text
对话 -> workspace 文件 -> 诊断 -> WebView 预览 -> 继续修改
```

## 当前 Preview 能力

- 持久化 session 和对话历史。
- 按时间顺序渲染对话，并用更轻量的形式展示工具和状态事件。
- workspace 文件读取、写入、编辑、列表和搜索工具。
- workspace 快照，用于更安全地迭代。
- HTML / WebView 预览，以及 workspace local HTTP 预览。
- 使用 `flovera.app.json` 描述生成的 workspace app。
- 使用 `artifact_diagnose` 检查生成的 Flovera app 是否成功注册。
- 受控 Python runtime，用于本地生成、计算和验证。
- 支持预览 HTML、Markdown、JSON、CSV、文本、代码、图片和 PDF。
- 模型 provider 配置保存在应用设置里，不写入源码。
- network 工具默认开启，并保留设置入口。
- 配置 Brave Search API key 后支持 Web Search。

## 边界

- Android 后台行为仍受系统和厂商策略影响。
- 生成的 demo 仍可能需要多轮迭代。
- Android WebView 和桌面浏览器存在兼容差异。
- provider API key 和应用权限属于 Flovera app 设置，不是 workspace 源码文件。
- Preview 阶段的大多数测试在官方 DeepSeek API 条件下进行。虽然 Flovera 提供其它 provider 配置选项，但当前不保证这些 provider 的功能一定正常。
- MCP、Git 和通用 shell 风格的 workspace 工具不属于第一个 Preview 版本边界。

## 仓库结构

```text
.
|-- android/spike/                 Android app 源码
|-- docs/                          项目和发布文档
|-- examples/                      示例材料
|-- scripts/                       仓库脚本
|-- PRODUCT_QUALITY.md             产品质量模型和内部 backlog
|-- CHANGELOG.md                   面向用户的变更记录
|-- THIRD_PARTY_NOTICES.md         依赖说明摘要
`-- LICENSE                        MIT license
```

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

## 真机验证

验证时使用受保护的 verifier，不要随意卸载已有 Flovera app。应用数据、权限、provider 设置和 session 本身都是产品状态的一部分。

在 `android/spike` 下执行：

```powershell
.\scripts\verify-flovera-android.ps1 -DeviceSerial <adb-serial> -SkipRelease
```

只做构建验证时使用 `-SkipDevice`。

应用目前有两个 Android 包名槽位：

- `com.flovera.app`，启动器名称 `Flovera`
- `com.example.ailinuxvmspike`，启动器名称 `Flovera legacy`

legacy 槽位用于让已有测试设备从旧包名更新，避免丢失 app 数据。

## 配置和密钥

不要提交 API key、签名文件、本地路径、生成的设置、APK 或 workspace 数据。

运行时 provider 配置由 Android app 保存。`.env.example` 只是本地开发模板。

## License

Flovera 使用 MIT License。详见 [LICENSE](LICENSE)。

Flovera 使用 [JetBrains Koog](https://github.com/JetBrains/koog) 作为上游 agent runtime framework。Koog 使用 Apache License 2.0。依赖说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
