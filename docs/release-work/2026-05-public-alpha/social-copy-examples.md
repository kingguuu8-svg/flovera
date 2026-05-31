# Social Copy Examples

## Core One-Liner Options

1. Flovera is an Android-local workspace agent for building small demos and
   artifacts directly on your phone.
2. Flovera turns an Android phone into a local AI workbench: chat, files,
   Python, and WebView preview in one loop.
3. Flovera is not a VPS replacement. It is a phone-native demo workbench for AI
   generated local artifacts.

## X / Threads: Maker Story

I am building Flovera: an AI workspace agent that runs inside an Android app.

The loop is intentionally small:

- ask for a demo, game, spreadsheet, or local web app
- the agent writes files into a scoped phone workspace
- Flovera validates the artifact
- you open it in WebView and keep iterating

It is not a cloud IDE or VPS replacement. It is a local Android workbench.

Alpha repo: <github-url>

## X / Threads: Technical Angle

Most coding agents assume a desktop shell or cloud VM.

Flovera tries a different boundary: Android owns the workspace, sessions,
permissions, WebView preview, and bounded Python runtime.

Current alpha:
- workspace files/search
- WebView/local HTTP artifacts
- `flovera.app.json` manifests
- artifact diagnostics
- bounded Python
- optional foreground keep-alive

I am looking for feedback on the local-phone workflow.

Repo: <github-url>

## X / Threads: Demo Angle

I asked Flovera to create a small mobile-first web demo.

It wrote the files into an Android-local workspace, validated the Flovera app
manifest, and opened the result in WebView.

The interesting part is not "AI wrote HTML."

The interesting part is that the whole loop stayed on the phone.

Repo: <github-url>

## Hacker News Draft

Title:

Show HN: Flovera, an Android-local workspace agent for small demos

Opening comment:

I built Flovera to explore a narrower version of agentic coding on Android.

Instead of treating the phone as just a chat client for a cloud machine, Flovera
keeps a scoped workspace inside the Android app. The agent can write files,
search the workspace, run bounded Python, generate small HTML/local HTTP
artifacts, validate `flovera.app.json` manifests, and open results in WebView.

It is an alpha. It is not trying to be a VPS replacement or a production
deployment platform. The current useful boundary is: small demos, local tools,
spreadsheets/reports, mobile WebView prototypes, and iterative artifacts.

I would especially like feedback on:

- whether the Android-local workspace model feels useful;
- which artifact types should be first-class;
- where the permission/background boundaries should be drawn;
- whether the README explains the limitations clearly enough.

Repo: <github-url>

## Product Hunt Draft

Name:

Flovera

Tagline:

An Android-local AI workbench for demos and artifacts.

Description:

Flovera lets an AI agent create files, small web apps, games, spreadsheets, and
local demos inside a scoped Android workspace. It previews generated HTML in
WebView, runs bounded Python for local artifact generation, and keeps the
conversation, files, and preview loop on the phone.

Maker comment:

I built Flovera because most agent workflows feel like they live somewhere else:
a cloud VM, a desktop shell, or a web IDE. Flovera explores a smaller Android
native loop where the phone owns the workspace, preview surface, permissions,
and session history.

This alpha is deliberately scoped. It is a demo workbench, not a VPS
replacement. Feedback on the local workflow and artifact model would be very
useful.

## Reddit / SideProject

I am working on Flovera, an Android-local workspace agent.

The idea came from a small irritation: most agent products feel disconnected
from the device I am holding. They run somewhere else, and the phone is only a
chat window.

Flovera keeps a scoped workspace inside the Android app. The agent can create
files, generate small HTML apps, run bounded Python, validate generated app
manifests, and open results in WebView.

It is still alpha. It is best for small demos and local artifacts, not
production deployment. The thing I am trying to validate is whether a
phone-native creation loop feels useful enough to keep pushing.

Repo: <github-url>

## Chinese Post: Product Intuition

我在做 Flovera，一个跑在 Android 本地的 workspace agent。

它不是 VPS 替代品，也不是万能后台助手。更准确地说，它是一个手机里的 demo 工作台：

- agent 在 Android app 里运行
- 文件写进本地 workspace
- 可以生成 HTML、小工具、小游戏、Excel 等产物
- WebView 直接打开生成结果
- Python 用于本地文件生成和验证
- 会话和产物都留在本机

我最想验证的是这种“连接感”：不是把任务丢到远端机器，而是在手机里直接生成、预览、修改。

当前还是 alpha，适合小 demo 和本地产物，不适合承诺生产级部署或长期后台自动化。

Repo: <github-url>

## Chinese Post: Technical Angle

Flovera 的边界刻意做得比较窄：

Android app 负责 workspace、会话、权限、WebView、provider 设置和后台状态；agent 负责在这个边界内读写文件、运行受控 Python、生成可预览 artifact。

这个方向不追求“手机上复刻一台 VPS”，而是追求一个更轻的闭环：

对话 -> 文件 -> 诊断 -> WebView 预览 -> 继续修改。

如果你对 Android 本地 agent、移动端 demo 工作台、或者 AI 生成 artifact 的交互有兴趣，欢迎看看。

Repo: <github-url>

