package com.flovera.app.workspace

import com.flovera.app.storage.readUtf8Text
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class FloveraSkillManifest(
  val version: Int = 1,
  val skills: List<FloveraSkillRegistration> = FloveraSkillRegistry.defaultRegistrations,
)

@Serializable
data class FloveraSkillRegistration(
  val id: String,
  val path: String = ".flovera/skills/$id/SKILL.md",
  val enabled: Boolean = true,
  val titleEn: String = "",
  val titleZh: String = "",
  val descriptionEn: String = "",
  val descriptionZh: String = "",
)

data class FloveraSkillPromptDescriptor(
  val id: String,
  val path: String,
  val name: String,
  val descriptionEn: String,
  val descriptionZh: String,
)

data class FloveraSkillConsoleEntry(
  val id: String,
  val path: String,
  val enabled: Boolean,
  val titleEn: String,
  val titleZh: String,
  val descriptionEn: String,
  val descriptionZh: String,
)

object FloveraSkillRegistry {
  val defaultRegistrations: List<FloveraSkillRegistration> = listOf(
    defaultRegistration(
      id = "flovera-android-webview-app",
      titleEn = "Flovera Android WebView App",
      titleZh = "Flovera Android WebView 应用",
      descriptionEn = "Use when creating or fixing a Flovera workspace app, HTML preview, mobile WebView surface, game, touch UI, or flovera.app.json registration.",
      descriptionZh = "用于创建或修复 Flovera 工作区应用、HTML 预览、移动 WebView、游戏、触控界面或 flovera.app.json 注册。",
    ),
    defaultRegistration(
      id = "flovera-python-workspace-command",
      titleEn = "Flovera Python Workspace Command",
      titleZh = "Flovera Python 工作区命令",
      descriptionEn = "Use when a task needs Python execution, scripts, calculation, document generation, package checks, or file-producing automation inside Flovera.",
      descriptionZh = "用于需要在 Flovera 内运行 Python、脚本、计算、文档生成、包检查或文件产出自动化的任务。",
    ),
    defaultRegistration(
      id = "flovera-jvm-groovy",
      titleEn = "Flovera JVM Groovy Runtime",
      titleZh = "Flovera JVM/Groovy 运行时",
      descriptionEn = "Use when a task materially benefits from Groovy, JVM libraries, jars, Maven coordinates, or document libraries that CPython cannot cover well.",
      descriptionZh = "用于任务明显需要 Groovy、JVM 库、jar、Maven 坐标，或 CPython 难以覆盖的文档类库时。",
    ),
    defaultRegistration(
      id = "flovera-git-workspace-command",
      titleEn = "Flovera Git Workspace Command",
      titleZh = "Flovera Git 工作区命令",
      descriptionEn = "Use when a task needs local Git status, diff, history, staging, or commits inside the Flovera workspace through embedded JGit.",
      descriptionZh = "用于需要在 Flovera 工作区内通过内置 JGit 查看 Git 状态、diff、历史、暂存或提交时。",
    ),
    defaultRegistration(
      id = "flovera-android-command",
      titleEn = "Flovera Android Command Profile",
      titleZh = "Flovera Android 命令能力",
      descriptionEn = "Use when a task needs permission-gated Android system APIs such as camera, microphone, location, contacts, calendar, media, Bluetooth, notifications, alarms, overlay, storage, installer, network, foreground service, or system intents.",
      descriptionZh = "用于任务需要调用受权限控制的 Android 系统 API，例如相机、录音、位置、联系人、日历、媒体、蓝牙、通知、提醒、悬浮窗、存储、安装器、网络、前台服务或系统 Intent。",
    ),
    defaultRegistration(
      id = "flovera-desktop-operation",
      titleEn = "Flovera Desktop Operation",
      titleZh = "Flovera 桌面操作",
      descriptionEn = "Use when the user asks Flovera to inspect or operate another Android app, complete a cross-app workflow, or resume an interrupted desktop task.",
      descriptionZh = "用于用户要求 Flovera 查看或操作其他 Android 应用、完成跨应用流程，或恢复被中断的桌面任务。",
    ),
    defaultRegistration(
      id = "flovera-mcp-adapter",
      titleEn = "Flovera MCP Adapter Planning",
      titleZh = "Flovera MCP 适配规划",
      descriptionEn = "Use when planning or prototyping a lightweight Flovera-side MCP adapter or server rewrite workflow.",
      descriptionZh = "用于规划或原型实现轻量的 Flovera 侧 MCP 适配器或 server 重写流程。",
    ),
    defaultRegistration(
      id = "flovera-automation-script",
      titleEn = "Flovera Automation Script",
      titleZh = "Flovera 通用自动化脚本",
      descriptionEn = "Use when a repeatable workflow should be saved as a generic Flovera workspace automation script under .flovera/scripts, including Python, Groovy, Git, Android, or mixed command steps.",
      descriptionZh = "用于把可重复流程保存为 .flovera/scripts 下的通用 Flovera 工作区自动化脚本，可编排 Python、Groovy、Git、Android 或混合命令步骤。",
    ),
    defaultRegistration(
      id = "flovera-skill-creator",
      titleEn = "Flovera Skill Creator",
      titleZh = "Flovera 技能创建器",
      descriptionEn = "Use when the user asks Flovera to create, edit, register, split, toggle, or organize skills under .flovera/skills.",
      descriptionZh = "用于用户要求 Flovera 在 .flovera/skills 下创建、编辑、注册、拆分、开关或组织技能。",
    ),
  ).map { it.withDefaultChineseText() }

  fun mergedDefaultManifest(existing: FloveraSkillManifest? = null): FloveraSkillManifest {
    if (existing == null) return FloveraSkillManifest(skills = defaultRegistrations)
    val existingById = existing.skills.associateBy { it.id }
    val mergedDefaults = defaultRegistrations.filterNot { it.id in existingById }
    val refreshedExisting = existing.skills.map { registration ->
      val defaults = defaultRegistrations.firstOrNull { it.id == registration.id } ?: return@map registration
      registration.copy(
        path = registration.path.ifBlank { defaults.path },
        titleEn = registration.titleEn.ifBlank { defaults.titleEn },
        titleZh = registration.titleZh.takeUnless { it.isBlank() || it.looksLikeMojibake() } ?: defaults.titleZh,
        descriptionEn = registration.descriptionEn.ifBlank { defaults.descriptionEn },
        descriptionZh = registration.descriptionZh.takeUnless { it.isBlank() || it.looksLikeMojibake() }
          ?: defaults.descriptionZh,
      )
    }
    return existing.copy(skills = refreshedExisting + mergedDefaults)
  }

  fun defaultPromptDescriptors(): String {
    return defaultRegistrations.asSequence()
      .filter { it.enabled }
      .map { registration ->
        val descriptor = descriptorFromRegistration(
          registration = registration,
          body = defaultSkillBody(registration.id),
        )
        descriptor.toPromptLine()
      }.joinToString("\n")
  }

  fun promptDescriptors(workspaceRoot: File, json: Json): String {
    val manifest = loadManifest(workspaceRoot, json)
    return manifest.skills
      .asSequence()
      .filter { it.enabled }
      .mapNotNull { registration ->
        val path = normalizedSkillPath(registration)
        val file = workspaceFile(workspaceRoot, path) ?: return@mapNotNull null
        if (!file.isFile) return@mapNotNull null
        descriptorFromRegistration(
          registration = registration.copy(
            id = registration.id.ifBlank { file.parentFile?.name.orEmpty() },
            path = path,
          ),
          body = runCatching { readUtf8Text(file) }.getOrDefault(""),
        )
      }
      .take(MAX_PROMPT_SKILLS)
      .joinToString("\n") { it.toPromptLine() }
  }

  fun consoleEntries(workspaceRoot: File, json: Json): List<FloveraSkillConsoleEntry> {
    val manifest = loadManifest(workspaceRoot, json)
    return manifest.skills.map { registration ->
      val path = normalizedSkillPath(registration)
      val file = workspaceFile(workspaceRoot, path)
      val body = if (file?.isFile == true) runCatching { readUtf8Text(file) }.getOrDefault("") else ""
      val descriptor = descriptorFromRegistration(registration.copy(path = path), body)
      FloveraSkillConsoleEntry(
        id = registration.id,
        path = path,
        enabled = registration.enabled,
        titleEn = registration.titleEn.ifBlank { descriptor.name },
        titleZh = registration.titleZh.ifBlank { registration.titleEn.ifBlank { descriptor.name } },
        descriptionEn = descriptor.descriptionEn,
        descriptionZh = descriptor.descriptionZh,
      )
    }
  }

  fun loadManifestFile(workspaceRoot: File, json: Json): FloveraSkillManifest = loadManifest(workspaceRoot, json)

  fun defaultSkillBody(id: String): String {
    return when (id) {
      "flovera-android-webview-app" -> """
        ---
        name: flovera-android-webview-app
        description: Use when creating or fixing a Flovera workspace app, HTML preview, mobile WebView surface, game, touch UI, or flovera.app.json registration.
        description_zh: 用于创建或修复 Flovera 工作区应用、HTML 预览、移动 WebView、游戏、触控界面或 flovera.app.json 注册。
        ---

        # Flovera Android WebView App

        Required workflow:
        - Build portable files first: `README.md`, `src/`, optional `src/server.py`, `src/web/`, `data/`, `outputs/`, and `flovera.app.json`.
        - Design for Android/mobile WebView before desktop. Use responsive layout, readable touch targets, safe bottom spacing, and stable first-screen content.
        - Prefer `local_http` with a Python stdlib `python_http` server for interactive apps. Use standard HTTP/fetch/SSE.
        - Keep `flovera.app.json` as a small adapter. Do not invent a project-specific JSON handoff protocol as the main integration.
        - After writing or changing `flovera.app.json`, call `artifact_diagnose`. Do not claim registration or usability until diagnostics confirm the manifest and preview path.
        - If unsure about the manifest shape, call `artifact_diagnose` with `includeReference=true` and compare with the hidden reference app.
        - For games and touch-heavy UI, reason through first launch, first input, restart/new-game, touch/click path, viewport fit, and safe-bottom behavior before reporting completion.
      """.trimIndent()

      "flovera-python-workspace-command" -> """
        ---
        name: flovera-python-workspace-command
        description: Use when a task needs Python execution, scripts, calculation, document generation, package checks, or file-producing automation inside Flovera.
        description_zh: 用于需要在 Flovera 内运行 Python、脚本、计算、文档生成、包检查或文件产出自动化的任务。
        ---

        # Flovera Python Workspace Command

        Required workflow:
        - Use `workspace_command_run` for Python by default, including scripts, `python -c`, calculations, document generation, file conversion, and validation.
        - Use argv form, for example `["python", "tools/check.py", "--input", "data.csv"]`. Do not use shell operators, bash, npm, git, or terminal-only instructions.
        - The user is in Android Flovera, not a desktop terminal. Do not ask the user to run Python manually.
        - Use `python_run` only if the tool is visible or the explicit fallback setting is enabled.
        - Use `python_package_install` only for packages in `.flovera/python/wheel-catalog.json`; do not claim arbitrary PyPI support.
        - After generating nontrivial Office/PDF/image/data artifacts, verify with `artifact_inspect`.
        - For reusable workspace scripts, prefer `.flovera/tools/` only when the user wants a repeatable workflow.
      """.trimIndent()

      "flovera-jvm-groovy" -> """
        ---
        name: flovera-jvm-groovy
        description: Use when a task needs JVM libraries, Groovy scripts, jars, Maven coordinates, or document-processing libraries that CPython cannot cover well.
        description_zh: 用于任务需要 JVM 库、Groovy 脚本、jar、Maven 坐标，或 CPython 难以覆盖的文档处理类库时。
        ---

        # Flovera JVM Groovy Runtime

        Required workflow:
        - Use `workspace_command_run` with argv such as `["groovy", "tools/script.groovy"]` only when JVM access is materially useful.
        - Put pure JVM jars under `libs/`, or declare Maven coordinates in `libs/maven.json` or `.flovera/jvm/maven.json`.
        - For isolated tests, prefer a temporary Maven config and pass `FLOVERA_JVM_MAVEN_CONFIG=<workspace-relative-json>` in environment.
        - Expect Android-incompatible APIs or native JVM artifacts to fail during D8/dex loading. Use `failureCategory` and `.flovera/logs/jvm-build.jsonl` to locate the failing stage.
        - Heavy first runs may spend time resolving Maven and preparing dex caches. Do not treat slow progress as failure while jvm build progress is moving.
      """.trimIndent()

      "flovera-git-workspace-command" -> """
        ---
        name: flovera-git-workspace-command
        description: Use when a task needs local Git status, diff, history, staging, or commits inside the Flovera workspace through embedded JGit.
        description_zh: 用于需要在 Flovera 工作区内通过内置 JGit 查看 Git 状态、diff、历史、暂存或提交时。
        ---

        # Flovera Git Workspace Command

        Required workflow:
        - Use `workspace_command_run` with argv form. Supported Git subcommands are `init`, `status`, `diff`, `log`, `show`, `branch`, `add`, and `commit`.
        - Run `["git", "status"]` before reporting repository state. If the workspace has no repository, run `["git", "init"]` only when Git history is useful for the task.
        - Use `["git", "diff"]` before summarizing local changes. Use `["git", "add", "."]` and `["git", "commit", "-m", "message"]` only when the user asks to commit or the task explicitly requires a checkpoint.
        - Git is embedded JGit, not system git. Do not use shell syntax, push, remote URLs, credentials, hooks, submodules, LFS, or OS git configuration.
        - Keep commits local and workspace-bound. If remote sync is needed, report it as unsupported in this build.
      """.trimIndent()

      "flovera-android-command" -> """
        ---
        name: flovera-android-command
        description: Use when a task needs permission-gated Android system APIs such as camera, microphone, location, contacts, calendar, media, Bluetooth, notifications, alarms, overlay, storage, installer, network, foreground service, or system intents.
        description_zh: 用于任务需要调用受权限控制的 Android 系统 API，例如相机、录音、位置、联系人、日历、媒体、蓝牙、通知、提醒、悬浮窗、存储、安装器、网络、前台服务或系统 Intent。
        ---

        # Flovera Android Command Profile

        Required workflow:
        - Use `workspace_command_run` with argv. Run `["android", "help"]` when exact available syntax is needed.
        - Core read APIs: app `info/list/resolve`, `["android","location","current"]`, contacts `list/search`, calendar `calendars/events`, media `list`, Bluetooth `paired/scan`, storage `list`, foreground `status`, permission `status`.
        - Location uses fresh system cache when available, otherwise requests enabled fused/network/GPS/passive providers concurrently. Read `source`, `ageMs`, `accuracyMeters`, and `enabledProviders` before describing precision or failure; do not infer that GPS or network positioning failed unless the command reports it.
        - Core action APIs: app `launch --name <app-name>` or `launch --package <package>`, notification `post/cancel`, camera `capture --output`, microphone `record --output --duration-ms`, contacts `create/delete`, calendar `create/delete`, media/storage `import --output`, overlay `show/hide`, package `install --path`, alarm `schedule/cancel`, network `get`, foreground `start/stop`, and intent `open-url/share/dial`.
        - Camera captures and microphone recordings write directly into workspace-relative output paths. Media and shared-storage imports also require an explicit workspace output path.
        - If a permission is missing, ask the user to open Flovera's Permissions panel and tap Grant all. Flovera batches runtime requests, then opens each required special-permission system page in sequence.
        - Use `["android", "permission", "open", "<permission-id>"]` only when a specific permission needs to be reopened.
        - Do not claim a permission is granted until `android permission status` reports `granted`.
        - Do not claim a native action succeeded from intent launch alone. Use the command result and verify workspace outputs when an action produces a file.
        - Android commands are app-owned adapters, not shell access. Do not use `adb`, `am`, `pm`, Android shell commands, hidden APIs, or invented native bridges.
        - Permission ids include notifications, camera, microphone, fine_location, coarse_location, contacts_read, contacts_write, calendar_read, calendar_write, media_images, media_video, media_audio, bluetooth_scan, bluetooth_connect, battery_optimization, overlay, all_files, install_unknown_apps, exact_alarm, internet, and foreground_service.
      """.trimIndent()

      "flovera-desktop-operation" -> """
        ---
        name: flovera-desktop-operation
        description: Use when the user asks Flovera to inspect or operate another Android app, complete a cross-app workflow, or resume an interrupted desktop task.
        description_zh: 用于用户要求 Flovera 查看或操作其他 Android 应用、完成跨应用流程，或恢复被中断的桌面任务。
        ---

        # Flovera Desktop Operation

        Required workflow:
        - Check `["android","ui","status"]`. If Accessibility is unavailable, use the returned diagnosis, ask the user to enable Desktop operation accessibility from Flovera Permissions, and use `["android","ui","open-settings"]` only to open the Android page.
        - Start once with `["android","ui","task","start","--goal","<user goal>"]`, or inspect the persisted task and continue it after interruption. Continue means re-identify the current screen first; it does not mean Android is still at the old foreground page.
        - Prefer semantic actions: `click --text`, `click --description`, `click --resource-id`, `set-text --text/--description/--resource-id`, and `swipe --until-text`. `swipe` accepts `--start-x/--start-y/--end-x/--end-y`; `--from-x/--from-y/--to-x/--to-y` are aliases. Use `inspect --filter-text`, `inspect --filter-description`, `inspect --filter-resource-id`, `inspect --filter-ocr-text`, `inspect --with-ocr`, or `inspect --node-id <id> --subtree` when full-tree inspection is too large.
        - Use OCR as a semantic-tree supplement for apps that hide text from Accessibility. `ocr` returns screen text blocks with bounds; `inspect --with-ocr` attaches nearby OCR text to nodes; `click --ocr-text <text>` first tries an OCR-matched clickable node and only then falls back to the OCR bounding-box center.
        - Fall back to raw node-id only after inspecting the current screen. Fall back to coordinates only for touch-heavy UI that exposes no usable semantic node.
        - Every mutating action requires a stable unique `--action-id`. Reusing an already confirmed action-id is a no-op, which prevents duplicate actions after retries or process recovery.
        - Provide `--expect-text` or `--expect-package` whenever the expected result is known. Without an explicit expectation Flovera still requires the semantic screen digest to change.
        - Supported actions include `launch --app <visible-name>`, `launch --package [--activity <activity-class>]`, `click`, `click --ocr-text`, `set-text --value`, `set-text --ocr-text --value`, `tap`, `swipe`, `swipe --until-text`, and `global --action back|home|recents|notifications|quick-settings`. Prefer `android app resolve --name <name>` or `launch --app <name>` before guessing package names. If a package reports no launchable activity but the activity class is known from inspection or app metadata, retry with `--activity`.
        - If a click appears accepted but verification fails, Flovera retries once by tapping the matched pre-action bounds and verifies again. If text input cannot directly set text, Flovera taps/focuses the target and retries; add `--dismiss-keyboard-after` when the keyboard should be closed after input.
        - Use `wait --text/--ocr-text/--package` and post-action `--expect-text`, `--expect-ocr-text`, or `--expect-package` checks. Do not infer success from a gesture being accepted.
        - Each desktop operation command updates app-owned feedback as "Flovera is operating the phone" plus the current action such as click, input, swipe, or wait. Terminal feedback dwells briefly and may use a stronger notification/vibration. Do not replace this with filler narration; add natural-language progress only when it helps the user understand a decision.
        - `screenshot --output <workspace.png>` captures the current screen for diagnostics. Failed UI actions write a diagnosis package under `.flovera/logs/ui-diagnosis/` with screenshot and semantic/OCR inspection when possible. Current provider requests are text-only, so do not claim the model interpreted screenshot pixels unless a future vision adapter explicitly reports that it did.
        - If login, CAPTCHA, biometric confirmation, a protected system dialog, lock screen, payment, or an unverified action blocks progress, run `ui task intervention --reason`. Tell the user exactly what must be done.
        - After the user resolves the blocker, run `ui task resume`, inspect the current screen, and rebuild the scene if needed before continuing from the last confirmed action-id. Never replay earlier actions blindly.
        - Complete with `ui task complete --summary`, or cancel explicitly. Do not leave a finished workflow marked active.
      """.trimIndent()

      "flovera-automation-script" -> """
        ---
        name: flovera-automation-script
        description: Use when a repeatable workflow should be saved as a generic Flovera workspace automation script under .flovera/scripts, including Python, Groovy, Git, Android, or mixed command steps.
        description_zh: 用于把可重复流程保存为 .flovera/scripts 下的通用 Flovera 工作区自动化脚本，可编排 Python、Groovy、Git、Android 或混合命令步骤。
        ---

        # Flovera Automation Script

        Required workflow:
        - Use this for reusable automation, not only Android UI operation. A script can compose Python, Groovy/JVM, local Git/JGit, Android system APIs, and Android desktop operation commands.
        - Store scripts as JSON files under `.flovera/scripts/<name>.json`.
        - Run scripts through `workspace_command_run` with argv `["flovera", "script", "run", "<name>"]`. List scripts with `["flovera", "script", "list"]`.
        - Keep each step as argv, not shell text. Do not use shell operators, bash, npm, daemons, or OS commands.
        - Use `--param key=value` for runtime values. Inside script argv or cwd, reference values as `{{key}}`.
        - Keep scripts small and inspectable. Put large reusable logic in ordinary workspace files such as `tools/*.py` or `tools/*.groovy`, then call those files from script steps.
        - For Android UI steps, include semantic selectors and expected results when possible. Flovera adds a stable action id when a script step omits one, but explicit action ids are better for hand-written workflows.
        - Verify artifacts after script runs when the workflow creates Office/PDF/image/data files.

        Script shape:

        ```json
        {
          "name": "daily-report",
          "description": "Generate and inspect a daily report.",
          "steps": [
            {
              "name": "generate",
              "argv": ["python", "tools/report.py", "--date", "{{date}}"],
              "timeoutMs": 30000
            },
            {
              "name": "status",
              "argv": ["git", "status"]
            }
          ]
        }
        ```
      """.trimIndent()

      "flovera-mcp-adapter" -> """
        ---
        name: flovera-mcp-adapter
        description: Use when planning or prototyping a lightweight Flovera-side MCP adapter or server rewrite workflow.
        description_zh: 用于规划或原型实现轻量的 Flovera 侧 MCP 适配器或 server 重写流程。
        ---

        # Flovera MCP Adapter Planning

        Required workflow:
        - Treat MCP support as proposal/scaffolding unless the requested adapter has been implemented and verified inside Flovera.
        - Prefer translating a narrow MCP server capability into workspace files and registered Flovera tools/artifacts over adding broad native tool surfaces.
        - Identify server inputs, outputs, auth needs, filesystem boundaries, network needs, and long-running process assumptions.
        - If the original MCP server depends on npm, daemons, OS shell behavior, native binaries, or hidden secrets, call out the platform gap and design a bounded replacement.
      """.trimIndent()

      "flovera-skill-creator" -> """
        ---
        name: flovera-skill-creator
        description: Use when the user asks Flovera to create, edit, register, split, toggle, or organize skills under .flovera/skills.
        description_zh: 用于用户要求 Flovera 在 .flovera/skills 下创建、编辑、注册、拆分、开关或组织技能。
        ---

        # Flovera Skill Creator

        Flovera skills use the standard structure:

        ```text
        .flovera/skills/<skill-id>/
          SKILL.md
          references/   optional
          scripts/      optional
          assets/       optional
        ```

        ## Required SKILL.md Shape

        Every skill must have YAML frontmatter followed by Markdown instructions:

        ```markdown
        ---
        name: skill-id
        description: Use when ...
        ---

        # Skill Title

        Instructions...
        ```

        `name` and `description` are the entry metadata shown in the request. The full body is read only when the agent decides the skill is relevant.

        ## Registration

        Register skills in `.flovera/skills/manifest.json`:

        ```json
        {
          "version": 1,
          "skills": [
            {
              "id": "skill-id",
              "path": ".flovera/skills/skill-id/SKILL.md",
              "enabled": true,
              "titleEn": "Skill title",
              "titleZh": "Skill 中文标题",
              "descriptionEn": "Use when ...",
              "descriptionZh": "用于..."
            }
          ]
        }
        ```

        A skill entry is visible to the agent only when `enabled=true` and its `SKILL.md` exists. Disabled skills are not injected into `Available Flovera skills`, but the user and agent may still inspect `.flovera/skills` directly with ordinary file tools.

        The manifest is the console-facing control surface. Keep `enabled`, English description, and Chinese description explicit and synchronized with the frontmatter when possible. The request-body skill entry uses the English description only; Chinese metadata is for user-facing console readability. The `SKILL.md` frontmatter should still include `name`, `description`, and optional `description_zh` because the body remains portable.

        The user and agent may edit built-in skills, add custom skills, disable skills, or replace the manifest.

        ## Design Rules

        - Keep `SKILL.md` focused on instructions that change behavior.
        - Prefer concise workflow steps over long background explanation.
        - Use `references/` for optional large details; mention when to read each reference from `SKILL.md`.
        - Use `scripts/` only for repeatable deterministic work.
        - Use `assets/` for templates or files used as outputs.
        - Do not add README, changelog, install guide, or extra docs unless the user explicitly asks.
        - Do not put secrets or user-private runtime logs in a skill.
        - After creating or changing a skill, verify the manifest path, frontmatter `name`, frontmatter `description`, optional `description_zh`, manifest `descriptionEn`, manifest `descriptionZh`, and that the descriptor appears in the next prompt descriptor list only when `enabled=true`.
      """.trimIndent()

      else -> ""
    }.withDefaultChineseFrontmatter(id)
  }

  private fun loadManifest(workspaceRoot: File, json: Json): FloveraSkillManifest {
    val manifest = File(workspaceRoot, SKILL_MANIFEST_PATH)
    if (!manifest.isFile) return mergedDefaultManifest()
    return runCatching { json.decodeFromString<FloveraSkillManifest>(readUtf8Text(manifest)) }
      .map { mergedDefaultManifest(it) }
      .getOrDefault(mergedDefaultManifest())
  }

  private fun descriptorFromRegistration(registration: FloveraSkillRegistration, body: String): FloveraSkillPromptDescriptor {
    val metadata = frontmatter(body)
    return FloveraSkillPromptDescriptor(
      id = registration.id,
      path = registration.path,
      name = metadata["name"].orEmpty().ifBlank { registration.id },
      descriptionEn = registration.descriptionEn
        .ifBlank { metadata["description"].orEmpty() }
        .ifBlank { firstHeading(body).ifBlank { "No description provided." } },
      descriptionZh = registration.descriptionZh
        .ifBlank { metadata["description_zh"].orEmpty() }
        .ifBlank { registration.descriptionEn },
    )
  }

  private fun frontmatter(body: String): Map<String, String> {
    val lines = body.lineSequence().toList()
    if (lines.firstOrNull()?.trim() != "---") return emptyMap()
    val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
    if (end < 0) return emptyMap()
    return lines.drop(1).take(end).mapNotNull { line ->
      val index = line.indexOf(':')
      if (index <= 0) return@mapNotNull null
      line.take(index).trim() to line.drop(index + 1).trim()
    }.toMap()
  }

  private fun firstHeading(body: String): String {
    return body.lineSequence()
      .firstOrNull { it.trimStart().startsWith("# ") }
      ?.trimStart()
      ?.removePrefix("# ")
      ?.trim()
      .orEmpty()
  }

  private fun normalizedSkillPath(registration: FloveraSkillRegistration): String {
    val path = registration.path.ifBlank { ".flovera/skills/${registration.id}/SKILL.md" }
    return path.replace('\\', '/').trimStart('/')
  }

  private fun workspaceFile(workspaceRoot: File, path: String): File? {
    val file = File(workspaceRoot, path).canonicalFile
    val root = workspaceRoot.canonicalFile
    if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) return null
    return file
  }

  private fun FloveraSkillPromptDescriptor.toPromptLine(): String = "- $id (`$name`): $descriptionEn. Path: $path."

  const val SKILL_MANIFEST_PATH = ".flovera/skills/manifest.json"
  private const val MAX_PROMPT_SKILLS = 12

  private fun defaultRegistration(
    id: String,
    titleEn: String,
    titleZh: String,
    descriptionEn: String,
    descriptionZh: String,
  ): FloveraSkillRegistration = FloveraSkillRegistration(
    id = id,
    titleEn = titleEn,
    titleZh = titleZh,
    descriptionEn = descriptionEn,
    descriptionZh = descriptionZh,
  )

  private fun FloveraSkillRegistration.withDefaultChineseText(): FloveraSkillRegistration {
    return when (id) {
      "flovera-android-webview-app" -> copy(
        titleZh = "Flovera Android WebView 应用",
        descriptionZh = "用于创建或修复 Flovera 工作区应用、HTML 预览、移动 WebView、游戏、触控界面或 flovera.app.json 注册。",
      )
      "flovera-python-workspace-command" -> copy(
        titleZh = "Flovera Python 工作区命令",
        descriptionZh = "用于需要在 Flovera 内运行 Python、脚本、计算、文档生成、包检查或文件产出自动化的任务。",
      )
      "flovera-jvm-groovy" -> copy(
        titleZh = "Flovera JVM/Groovy 运行时",
        descriptionZh = "用于任务明显需要 Groovy、JVM 库、jar、Maven 坐标，或 CPython 难以覆盖的文档类库时。",
      )
      "flovera-git-workspace-command" -> copy(
        titleZh = "Flovera Git 工作区命令",
        descriptionZh = "用于需要在 Flovera 工作区内通过内置 JGit 查看 Git 状态、diff、历史、暂存或提交时。",
      )
      "flovera-android-command" -> copy(
        titleZh = "Flovera Android 命令能力",
        descriptionZh = "用于任务需要调用受权限控制的 Android 系统 API，例如相机、录音、位置、联系人、日历、媒体、蓝牙、通知、提醒、悬浮窗、存储、安装器、网络、前台服务或系统 Intent。",
      )
      "flovera-mcp-adapter" -> copy(
        titleZh = "Flovera MCP 适配规划",
        descriptionZh = "用于规划或原型实现轻量的 Flovera 侧 MCP 适配器或 server 重写流程。",
      )
      "flovera-automation-script" -> copy(
        titleZh = "Flovera 通用自动化脚本",
        descriptionZh = "用于把可重复流程保存为 .flovera/scripts 下的通用 Flovera 工作区自动化脚本，可编排 Python、Groovy、Git、Android 或混合命令步骤。",
      )
      "flovera-skill-creator" -> copy(
        titleZh = "Flovera 技能创建器",
        descriptionZh = "用于用户要求 Flovera 在 .flovera/skills 下创建、编辑、注册、拆分、开关或组织技能。",
      )
      else -> this
    }
  }

  private fun String.withDefaultChineseFrontmatter(id: String): String {
    val descriptionZh = defaultRegistrations.firstOrNull { it.id == id }?.descriptionZh ?: return this
    val fixed = replace(Regex("""(?m)^description_zh:.*$"""), "description_zh: $descriptionZh")
    if (id != "flovera-skill-creator") return fixed
    return fixed
      .replace(Regex(""""titleZh"\s*:\s*"[^"]*""""), "\"titleZh\": \"技能中文标题\"")
      .replace(Regex(""""descriptionZh"\s*:\s*"[^"]*""""), "\"descriptionZh\": \"用于...\"")
  }

  private fun String.looksLikeMojibake(): Boolean {
    return contains("鐢") || contains("搴") || contains("鍒") || contains("杩") || contains("閫") || contains("涓")
  }
}
