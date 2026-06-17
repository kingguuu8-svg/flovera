package com.flovera.app

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.koog.ArtifactInspectTool
import com.flovera.app.koog.ArtifactDiagnoseTool
import com.flovera.app.koog.FloveraPythonRuntime
import com.flovera.app.koog.PythonRunTool
import com.flovera.app.koog.PythonPackageInstallTool
import com.flovera.app.config.AppSettings
import com.flovera.app.config.WorkspaceSecret
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.koog.WorkspaceCommandRunTool
import com.flovera.app.koog.WorkspaceSearchTool
import com.flovera.app.koog.workspaceToolRegistry
import com.flovera.app.platform.MlKitOcrEngine
import com.flovera.app.web.FloveraWebBridge
import com.flovera.app.config.SettingsStore
import com.flovera.app.workspace.WorkspaceController
import com.flovera.app.workspace.FloveraSettingsView
import com.flovera.app.workspace.WorkspaceManager
import java.io.ByteArrayInputStream
import java.io.File
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

private const val TEST_HELPER_JAR_BASE64 =
  "UEsDBBQACAgIAH2mwVwAAAAAAAAAAAAAAAAJAAQATUVUQS1JTkYv/soAAAMAUEsHCAAAAAACAAAAAAAAAFBLAwQUAAgICAB9psFcAAAAAAAAAAAAAAAAFAAAAE1FVEEtSU5GL01BTklGRVNULk1G803My0xLLS7RDUstKs7Mz7NSMNQz4OVyLkpNLElN0XWqBAqY6xnoGVooaPhmJhflF+enlWjycvFyAQBQSwcIBZVIozsAAAA6AAAAUEsDBAoAAAgAAH2mwVwAAAAAAAAAAAAAAAAFAAAAZGVtby9QSwMEFAAICAgAfabBXAAAAAAAAAAAAAAAABEAAABkZW1vL0hlbHBlci5jbGFzc31RTU/CQBB9S4FCqYKIoih+i4UEG423Gg+SGA9EEkETj4VusKS0pLT+LvUAiSb+AH+UcSoQIhh3k9md2Zn33ux8fr19ADiDIiEEQURYRgRRhlRHf9JVS7fbaq3Z4S2PIXpu2qZ3wSAoxXsRMYbsNKnuuabdvvRNy+CuBBFCDAmGcEd3y4G7IGMRSQLRez1uGwxlpTpbrRXnQmNALY4lpEUsy8hgZaLO90xLrTot3eLEdFurNRjS1dknTUIWayLWZeSw8auzEQdDwnPuSJZb0fuEVFDmMeaVaUFXeRlb2GaIec4EK6P8kStil1gM3nXUa24RE+mtOAaRJaumzW/8bpO7Db0ZNBLpPzq+N5Xx/wdpDFLd8d0WvzKD6sQI/zjIwwl2aKrBCtGmuYJhj7w8nYzOSGkI9kIXhn2y0Z+ggDgOcDhOPSU/iObeIT4MEU9LA8ilV6QGWKXbAJvPM/URsoUfyqNvUEsHCBvf9YJkAQAAXgIAAFBLAQIUABQACAgIAH2mwVwAAAAAAgAAAAAAAAAJAAQAAAAAAAAAAAAAAAAAAABNRVRBLUlORi/+ygAAUEsBAhQAFAAICAgAfabBXAWVSKM7AAAAOgAAABQAAAAAAAAAAAAAAAAAPQAAAE1FVEEtSU5GL01BTklGRVNULk1GUEsBAgoACgAACAAAfabBXAAAAAAAAAAAAAAAAAUAAAAAAAAAAAAAAAAAugAAAGRlbW8vUEsBAhQAFAAICAgAfabBXBvf9YJkAQAAXgIAABEAAAAAAAAAAAAAAAAA3QAAAGRlbW8vSGVscGVyLmNsYXNzUEsFBgAAAAAEAAQA7wAAAIACAAAAAA=="

private fun writeTestHelperJarWithResource(target: File, resourcePath: String, resourceText: String) {
  target.outputStream().buffered().use { fileOutput ->
    JarOutputStream(fileOutput).use { output ->
      JarInputStream(ByteArrayInputStream(Base64.getDecoder().decode(TEST_HELPER_JAR_BASE64))).use { input ->
        generateSequence { input.nextJarEntry }.forEach { entry ->
          if (!entry.isDirectory) {
            output.putNextEntry(JarEntry(entry.name))
            input.copyTo(output)
            output.closeEntry()
          }
          input.closeEntry()
        }
      }
      output.putNextEntry(JarEntry(resourcePath))
      output.write(resourceText.toByteArray(StandardCharsets.UTF_8))
      output.closeEntry()
    }
  }
}

class WorkspaceFileTreeInstrumentedTest {
  @Test
  fun workspaceTreeIncludesNestedFilesAndSupportsRename() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "tree-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    workspace.writeFile("nested/page.html", "<!doctype html><title>Nested</title>")
    workspace.writeFile("nested/data.json", "{}")

    val nested = workspace.fileTree().children.firstOrNull { it.path == "nested" }
    assertNotNull(nested)
    assertTrue(nested!!.isDirectory)
    assertTrue(nested.children.any { it.path == "nested/page.html" })
    assertEquals("text/html", workspace.mimeType("nested/page.html"))

    val uri = FileProvider.getUriForFile(
      context,
      "${context.packageName}.workspacefiles",
      workspace.exportableFile("nested/page.html")!!,
    )
    assertEquals("content", uri.scheme)

    val result = workspace.rename("nested/page.html", "home.html")
    assertTrue(result.startsWith("Renamed"))
    assertTrue(workspace.listHtmlFiles().contains("nested/home.html"))

    val deleteResult = workspace.deletePath("nested/home.html")
    assertEquals("Deleted nested/home.html", deleteResult)
    assertEquals("File does not exist: nested/home.html", workspace.readFile("nested/home.html"))
  }

  @Test
  fun workspaceControllerSnapshotNormalizesHtmlSelection() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val controller = WorkspaceController(context, "workspace-controller-${System.currentTimeMillis()}")
      .also { it.ensureSeedFiles() }

    val initial = controller.snapshot("index.html")
    assertEquals("index.html", initial.selectedHtmlPath)
    assertNotNull(initial.selectedHtmlUrl)
    assertTrue(initial.htmlFiles.contains("index.html"))

    val missing = controller.snapshot("missing.html")
    assertEquals("", missing.selectedHtmlPath)
    assertEquals(null, missing.selectedHtmlUrl)
  }

  @Test
  fun workspaceWritesTextEditsAndBytesAtomically() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "atomic-workspace-${System.currentTimeMillis()}")
    val textFile = File(workspace.root, "notes/today.md")
    val bytesFile = File(workspace.root, "assets/icon.bin")

    workspace.writeFile("notes/today.md", "alpha")
    workspace.editFile("notes/today.md", "alpha", "beta")
    workspace.writeBytes("assets/icon.bin", byteArrayOf(1, 2, 3))

    assertEquals("beta", workspace.readFile("notes/today.md"))
    assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), bytesFile.readBytes().toList())
    listOf(textFile, bytesFile).forEach { file ->
      assertTrue(file.isFile)
      assertFalse(File(file.absolutePath + ".new").exists())
      assertFalse(File(file.absolutePath + ".bak").exists())
    }
  }

  @Test
  fun workspaceReadPreviewTruncatesLargeText() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "preview-workspace-${System.currentTimeMillis()}")
    workspace.writeFile("large.txt", "a".repeat(128))

    val preview = workspace.readFilePreview("large.txt", maxChars = 16)

    assertTrue(preview.startsWith("a".repeat(16)))
    assertTrue(preview.contains("[truncated: showing first 16 chars"))
  }

  @Test
  fun seedWorkspaceIncludesPortableAgentDemoArtifact() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "seed-artifact-demo-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }

    val artifact = workspace.listWorkspaceArtifacts().single { it.manifestPath == "agent-demo/flovera.app.json" }

    assertTrue(artifact.valid)
    assertEquals("Flovera Workspace Chat Demo", artifact.name)
    assertEquals("local_http", artifact.preview?.kind)
    assertEquals("agent-demo/src/web/index.html", artifact.preview?.path)
    assertTrue(artifact.preview?.command.orEmpty().contains("python src/server.py"))
    assertEquals("agent-demo", artifact.preview?.cwd)
    assertEquals("/", artifact.preview?.urlPath)
    assertTrue(artifact.actions.isEmpty())
    assertTrue(workspace.readFile("agent-demo/README.md").contains("standard fetch/SSE"))
    assertTrue(workspace.readFile("agent-demo/src/web/index.html").contains("id=\"apiKey\""))
    assertTrue(workspace.readFile("agent-demo/src/web/index.html").contains("id=\"baseUrl\""))
    assertTrue(workspace.readFile("agent-demo/src/web/app.js").contains("/api/chat/stream"))
    assertTrue(workspace.readFile("agent-demo/src/web/app.js").contains("apiKey: apiKeyInput.value.trim()"))
    assertFalse(workspace.readFile("agent-demo/src/web/app.js").contains("/__flovera__/api/deepseek/stream"))
    assertFalse(workspace.readFile("agent-demo/src/web/app.js").contains("window.Flovera.runAction"))

    val outsideResult = FloveraPythonRuntime(workspace, networkEnabled = false).runScript(
      scriptPath = "src/server.py",
      argv = listOf("--self-test"),
      cwd = "agent-demo",
      timeoutMs = 30_000,
      sessionId = "portable-demo-outside",
    )

    assertEquals(0, outsideResult.exitCode)
    assertTrue(outsideResult.stdout.contains("portable-python-http ok"))
  }

  @Test
  fun seedWorkspaceIncludesEditableRegisteredFloveraSkills() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "seed-skills-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }

    val manifest = workspace.readFile(".flovera/skills/manifest.json")
    val skill = workspace.readFile(".flovera/skills/flovera-android-webview-app/SKILL.md")
    val pythonSkill = workspace.readFile(".flovera/skills/flovera-python-workspace-command/SKILL.md")
    val gitSkill = workspace.readFile(".flovera/skills/flovera-git-workspace-command/SKILL.md")
    val androidSkill = workspace.readFile(".flovera/skills/flovera-android-command/SKILL.md")
    val desktopSkill = workspace.readFile(".flovera/skills/flovera-desktop-operation/SKILL.md")
    val automationScriptSkill = workspace.readFile(".flovera/skills/flovera-automation-script/SKILL.md")
    val officeSkill = workspace.readFile(".flovera/skills/flovera-office-suite/SKILL.md")
    val skillCreator = workspace.readFile(".flovera/skills/flovera-skill-creator/SKILL.md")

    assertTrue(manifest.contains("\"id\": \"flovera-android-webview-app\""))
    assertTrue(manifest.contains("\"id\": \"flovera-python-workspace-command\""))
    assertTrue(manifest.contains("\"id\": \"flovera-git-workspace-command\""))
    assertTrue(manifest.contains("\"id\": \"flovera-android-command\""))
    assertTrue(manifest.contains("\"id\": \"flovera-desktop-operation\""))
    assertTrue(manifest.contains("\"id\": \"flovera-automation-script\""))
    assertTrue(manifest.contains("\"id\": \"flovera-office-suite\""))
    assertFalse(manifest.contains("\"id\": \"flovera-office-ooxml\""))
    assertTrue(manifest.contains("\"id\": \"flovera-skill-creator\""))
    assertFalse(manifest.contains("\"id\": \"flovera-context-handoff\""))
    assertTrue(manifest.contains("\"enabled\": true"))
    assertTrue(manifest.contains("\"descriptionEn\""))
    assertTrue(manifest.contains("\"descriptionZh\""))
    assertTrue(manifest.contains(".flovera/skills/flovera-android-webview-app/SKILL.md"))
    assertTrue(manifest.contains(".flovera/skills/flovera-python-workspace-command/SKILL.md"))
    assertTrue(manifest.contains(".flovera/skills/flovera-automation-script/SKILL.md"))
    assertTrue(manifest.contains(".flovera/skills/flovera-office-suite/SKILL.md"))
    assertTrue(manifest.contains(".flovera/skills/flovera-skill-creator/SKILL.md"))
    assertTrue(skill.contains("# Flovera Android WebView App"))
    assertTrue(skill.startsWith("---"))
    assertTrue(skill.contains("artifact_diagnose"))
    assertTrue(skill.contains("height: 100%"))
    assertTrue(skill.contains("min-height: var(--flovera-viewport-height, 100vh)"))
    assertTrue(skill.contains("must accept the same arguments"))
    assertTrue(pythonSkill.contains("name: flovera-python-workspace-command"))
    assertTrue(pythonSkill.contains("description_zh:"))
    assertTrue(pythonSkill.contains("workspace_command_run"))
    assertTrue(pythonSkill.contains("python_package_install"))
    assertTrue(pythonSkill.contains("artifact_inspect"))
    assertTrue(gitSkill.contains("embedded JGit"))
    assertTrue(gitSkill.contains("[\"git\", \"status\"]"))
    assertTrue(androidSkill.contains("[\"android\", \"help\"]"))
    assertTrue(androidSkill.contains("app `info/list/resolve`"))
    assertTrue(androidSkill.contains("camera"))
    assertTrue(androidSkill.contains("microphone"))
    assertTrue(androidSkill.contains("enabledProviders"))
    assertTrue(androidSkill.contains("do not infer that GPS or network positioning failed"))
    assertTrue(androidSkill.contains("Permissions panel"))
    assertTrue(desktopSkill.contains("name: flovera-desktop-operation"))
    assertTrue(desktopSkill.contains("click --text"))
    assertTrue(desktopSkill.contains("click --ocr-text"))
    assertTrue(desktopSkill.contains("Flovera cannot directly launch arbitrary third-party apps"))
    assertTrue(desktopSkill.contains("global --action back"))
    assertTrue(desktopSkill.contains("--dismiss-keyboard-after"))
    assertTrue(desktopSkill.contains(".flovera/logs/ui-diagnosis"))
    assertTrue(desktopSkill.contains("inspect --with-ocr"))
    assertTrue(desktopSkill.contains("--expect-ocr-text"))
    assertTrue(desktopSkill.contains("swipe --until-text"))
    assertTrue(desktopSkill.contains("--from-x/--from-y/--to-x/--to-y"))
    assertTrue(desktopSkill.contains("ask the user whether they want a reusable Flovera automation script/macro"))
    assertTrue(desktopSkill.contains("stronger notification/vibration"))
    assertTrue(desktopSkill.contains("inspect --filter-text"))
    assertTrue(desktopSkill.contains("--action-id"))
    assertTrue(desktopSkill.contains("Never replay earlier actions blindly"))
    assertTrue(desktopSkill.contains("provider requests are text-only"))
    assertTrue(automationScriptSkill.contains("name: flovera-automation-script"))
    assertTrue(automationScriptSkill.contains(".flovera/scripts/<name>.json"))
    assertTrue(automationScriptSkill.contains("[\"flovera\", \"script\", \"run\", \"<name>\"]"))
    assertTrue(automationScriptSkill.contains("not only Android UI operation"))
    assertTrue(automationScriptSkill.contains("Python, Groovy/JVM, local Git/JGit, Android system APIs"))
    assertTrue(officeSkill.contains("name: flovera-office-suite"))
    assertTrue(officeSkill.contains("openpyxl"))
    assertTrue(officeSkill.contains("python-docx"))
    assertTrue(officeSkill.contains("python-pptx"))
    assertTrue(officeSkill.contains("fpdf2"))
    assertTrue(officeSkill.contains("[\"flovera\", \"office\", \"inspect\", \"<path>\"]"))
    assertTrue(officeSkill.contains("structural inspection and validation layer"))
    assertTrue(skillCreator.contains("name: flovera-skill-creator"))
    assertTrue(skillCreator.contains("description: Use when the user asks Flovera to create"))
    assertTrue(skillCreator.contains("description_zh:"))
    assertTrue(skillCreator.contains(".flovera/skills/<skill-id>/"))
    assertTrue(skillCreator.contains(".flovera/skills/manifest.json"))
    assertTrue(skillCreator.contains("\"enabled\": true"))
    assertTrue(skillCreator.contains("\"descriptionEn\""))
    assertTrue(skillCreator.contains("\"descriptionZh\""))

    assertTrue(workspace.editFile(".flovera/skills/flovera-android-webview-app/SKILL.md", "artifact_diagnose", "artifact_diagnose immediately").contains("Edited"))
    assertTrue(workspace.readFile(".flovera/skills/flovera-android-webview-app/SKILL.md").contains("artifact_diagnose immediately"))

    val enabledDescriptors = workspace.readFloveraSkillPromptDescriptors()
    assertTrue(enabledDescriptors.contains("flovera-python-workspace-command"))
    assertTrue(enabledDescriptors.contains("flovera-git-workspace-command"))
    assertTrue(enabledDescriptors.contains("flovera-android-command"))
    assertTrue(enabledDescriptors.contains("flovera-automation-script"))
    assertTrue(enabledDescriptors.contains("flovera-office-suite"))
    assertTrue(enabledDescriptors.contains("Use when a task needs Python execution"))
    assertFalse(enabledDescriptors.contains("ZH:"))
    assertFalse(enabledDescriptors.contains("用于需要在 Flovera 内运行 Python"))
    val consoleEntries = workspace.listFloveraSkills().associateBy { it.id }
    assertTrue(consoleEntries["flovera-python-workspace-command"]?.titleZh.orEmpty().contains("工作区命令"))
    assertTrue(consoleEntries["flovera-python-workspace-command"]?.descriptionZh.orEmpty().contains("用于需要在 Flovera 内运行 Python"))
    assertTrue(consoleEntries["flovera-skill-creator"]?.titleZh.orEmpty().contains("技能创建器"))
    assertFalse(consoleEntries["flovera-skill-creator"]?.descriptionZh.orEmpty().contains("组织 skills"))
    assertTrue(workspace.setFloveraSkillEnabled("flovera-python-workspace-command", false))
    val disabledDescriptors = workspace.readFloveraSkillPromptDescriptors()
    assertFalse(disabledDescriptors.contains("flovera-python-workspace-command"))
    assertTrue(workspace.readFile(".flovera/skills/flovera-python-workspace-command/SKILL.md").contains("workspace_command_run"))

    workspace.writeFile(
      ".flovera/skills/custom-demo/SKILL.md",
      """
        ---
        name: custom-demo
        description: Use when the user wants a custom registered skill.
        ---

        # Custom Demo

        Follow the user's local preference.
      """.trimIndent(),
    )
    workspace.writeFile(
      ".flovera/skills/manifest.json",
      """
        {
          "version": 1,
          "skills": [
            {
              "id": "custom-demo",
              "path": ".flovera/skills/custom-demo/SKILL.md",
              "enabled": true,
              "titleEn": "Custom Demo",
              "titleZh": "自定义示例",
              "descriptionEn": "Use when the user wants a custom registered skill.",
              "descriptionZh": "用于用户需要自定义注册技能时。"
            }
          ]
        }
      """.trimIndent(),
    )

    val descriptors = workspace.readFloveraSkillPromptDescriptors()
    assertTrue(descriptors.contains("custom-demo"))
    assertTrue(descriptors.contains("Use when the user wants a custom registered skill."))
    assertFalse(descriptors.contains("用于用户需要自定义注册技能时。"))
    assertTrue(descriptors.contains("flovera-android-webview-app"))
    assertTrue(descriptors.contains("flovera-git-workspace-command"))
  }

  @Test
  fun seedWorkspaceMigratesLegacyAgentRulesToAgentsMd() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "legacy-agent-rules-${System.currentTimeMillis()}")
    workspace.writeFile(
      path = "AGENT.md",
      content = "legacy rule body",
      overwrite = true,
      createAutoSnapshot = false,
    )

    workspace.ensureSeedFiles()

    assertEquals("legacy rule body", workspace.readAgentRules())
    assertEquals("legacy rule body", workspace.readFile("AGENTS.md"))
  }

  @Test
  fun artifactDiagnoseToolReportsRegistrationState() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "artifact-diagnose-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val tool = ArtifactDiagnoseTool(workspace, ToolEventRecorder())

    val registered = tool.execute(
      ArtifactDiagnoseTool.Args(manifestPath = "agent-demo/flovera.app.json"),
    )
    assertTrue(registered, registered.contains("status=registered"))
    assertTrue(registered, registered.contains("manifestPath=agent-demo/flovera.app.json"))
    assertTrue(registered, registered.contains("preview=agent-demo/src/web/index.html"))
    assertTrue(registered, registered.contains("serverCommand=python src/server.py"))
    assertTrue(registered, registered.contains("diagnostics=(none)"))

    val missing = tool.execute(
      ArtifactDiagnoseTool.Args(manifestPath = "missing/flovera.app.json"),
    )
    assertTrue(missing, missing.contains("status=missing"))
    assertTrue(missing, missing.contains("Discovered manifests:"))

    val reference = tool.execute(
      ArtifactDiagnoseTool.Args(includeReference = true),
    )
    assertTrue(reference, reference.contains("Hidden reference app demo"))
    assertTrue(reference, reference.contains("Reference Mobile Chat Demo"))
    assertTrue(reference, reference.contains("python src/server.py --host 127.0.0.1 --port"))
    assertTrue(reference, reference.contains("POST /api/chat/stream"))
  }

  @Test
  fun controllerStartsSeedArtifactPythonHttpPreview() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "controller-local-http-${System.currentTimeMillis()}"
    val settingsStore = SettingsStore(context, File(context.filesDir, "$workspaceId-settings.json")).also {
      it.save(AppSettings(activeWorkspaceId = workspaceId, provider = "deepseek", model = "deepseek-v4-pro"))
    }
    val controller = AgentController(context, settingsStore = settingsStore).also {
      it.refreshWorkspaceFiles()
      it.selectHtmlFile("agent-demo/src/web/index.html")
    }

    val selectedUrl = awaitSelectedHtmlUrl(controller)
    assertTrue(selectedUrl.startsWith("http://127.0.0.1:"))
    assertFalse(selectedUrl.contains("/__flovera__/workspace/"))

    val html = URL(selectedUrl).readText()
    assertTrue(html.contains("Flovera Workspace Chat"))

    val origin = selectedUrl.trimEnd('/')
    val health = JSONObject(URL("$origin/api/health").readText())
    assertEquals("portable-python-http", health.getString("runtime"))
    assertFalse(health.getBoolean("hasServerApiKey"))
    assertTrue(health.getBoolean("acceptsRequestApiKey"))

    val connection = (URL("$origin/api/chat/stream").openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
    }
    connection.outputStream.use { stream ->
      stream.write("""{"messages":[{"role":"user","content":"hello"}]}""".toByteArray(StandardCharsets.UTF_8))
    }
    val sse = connection.inputStream.bufferedReader().use { it.readText() }
    assertTrue(sse.contains("Provide an API key in the workspace app"))
    assertTrue(sse.contains("[DONE]"))
    controller.stopWorkspaceArtifactServer("agent-demo/flovera.app.json")
  }

  @Test
  fun controllerDoesNotSilentlyFallbackWhenPythonHttpPreviewFails() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "controller-local-http-fail-${System.currentTimeMillis()}"
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    workspace.writeFile("broken/src/web/index.html", "<!doctype html><title>Broken HTTP app</title>", createAutoSnapshot = false)
    workspace.writeFile(
      "broken/flovera.app.json",
      """
        {
          "schemaVersion": 1,
          "name": "Broken HTTP app",
          "kind": "interactive",
          "entrypoints": {
            "preview": { "kind": "local_http", "path": "src/web/index.html" },
            "server": { "kind": "python_http", "command": "python src/missing.py --host 127.0.0.1 --port 8765" }
          }
        }
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    val settingsFile = File(context.filesDir, "$workspaceId-settings.json")
    val settingsStore = SettingsStore(context, settingsFile).also {
      it.save(AppSettings(activeWorkspaceId = workspaceId, provider = "", model = ""))
    }
    val controller = AgentController(context, settingsStore = settingsStore).also {
      it.refreshWorkspaceFiles()
      it.selectHtmlFile("broken/src/web/index.html")
    }

    val selectedError = awaitSelectedHtmlError(controller)
    assertNull(controller.state.value.selectedHtmlUrl)
    assertTrue(selectedError.contains("Artifact backend failed to start"))
    val status = controller.state.value.workspaceArtifactServerStatuses
      .single { it.manifestPath == "broken/flovera.app.json" }
    assertEquals("error", status.state)
    assertTrue(status.detail.contains("python_http script does not exist"))
    assertFalse(status.detail.contains("/__flovera__/workspace/"))

    settingsFile.delete()
    File(File(context.filesDir, "workspaces"), workspaceId).deleteRecursively()
  }

  @Test
  fun controllerContainsAutoStartedPythonHttpFailureWithoutCrashingApp() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "controller-local-http-contained-${System.currentTimeMillis()}"
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    workspace.writeFile(
      "broken/src/web/index.html",
      "<!doctype html><title>Contained backend failure</title>",
      createAutoSnapshot = false,
    )
    workspace.writeFile(
      "broken/src/server.py",
      """
        import sys

        host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
        port = int(sys.argv[2]) if len(sys.argv) > 2 else 8090
        raise RuntimeError(f"unexpectedly reached {host}:{port}")
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    workspace.writeFile(
      "broken/flovera.app.json",
      """
        {
          "schemaVersion": 1,
          "name": "Contained backend failure",
          "kind": "interactive",
          "entrypoints": {
            "preview": { "kind": "local_http", "path": "src/web/index.html" },
            "server": {
              "kind": "python_http",
              "command": "python src/server.py --host 127.0.0.1 --port ${'$'}{PORT}",
              "cwd": "."
            }
          }
        }
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    val settingsFile = File(context.filesDir, "$workspaceId-settings.json")
    val settingsStore = SettingsStore(context, settingsFile).also {
      it.save(
        AppSettings(
          activeWorkspaceId = workspaceId,
          provider = "",
          model = "",
          selectedHtmlPath = "broken/src/web/index.html",
        ),
      )
    }

    val controller = AgentController(context, settingsStore = settingsStore)
    val selectedError = awaitSelectedHtmlError(controller)

    assertNull(controller.state.value.selectedHtmlUrl)
    assertTrue(selectedError.contains("Artifact backend failed to start"))
    assertTrue(selectedError.contains("invalid literal for int()"))
    val status = controller.state.value.workspaceArtifactServerStatuses
      .single { it.manifestPath == "broken/flovera.app.json" }
    assertEquals("error", status.state)
    assertTrue(status.detail.contains("invalid literal for int()"))

    settingsFile.delete()
    File(File(context.filesDir, "workspaces"), workspaceId).deleteRecursively()
  }

  @Test
  fun controllerReusesStopsAndRestartsSeedArtifactPythonHttpPreview() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "controller-local-http-lifecycle-${System.currentTimeMillis()}"
    val settingsFile = File(context.filesDir, "$workspaceId-settings.json")
    var controller: AgentController? = null
    try {
      val settingsStore = SettingsStore(context, settingsFile).also {
        it.save(AppSettings(activeWorkspaceId = workspaceId, provider = "", model = ""))
      }
      controller = AgentController(context, settingsStore = settingsStore).also {
        it.refreshWorkspaceFiles()
        it.selectHtmlFile("agent-demo/src/web/index.html")
      }

      val firstUrl = awaitSelectedHtmlUrl(controller)
      assertTrue(firstUrl.startsWith("http://127.0.0.1:"))
      val runningStatus = controller.state.value.workspaceArtifactServerStatuses
        .single { it.manifestPath == "agent-demo/flovera.app.json" }
      assertEquals("running", runningStatus.state)
      assertEquals(firstUrl, runningStatus.url)
      assertNotNull(runningStatus.port)
      assertTrue(runningStatus.command.contains("python src/server.py"))
      assertEquals("agent-demo", runningStatus.cwd)

      controller.refreshWorkspaceFiles()
      assertEquals(firstUrl, controller.state.value.selectedHtmlUrl)

      controller.stopWorkspaceArtifactServer("agent-demo/flovera.app.json")
      val stoppedStatus = controller.state.value.workspaceArtifactServerStatuses
        .single { it.manifestPath == "agent-demo/flovera.app.json" }
      assertEquals("stopped", stoppedStatus.state)
      assertEquals("Artifact server stopped", controller.state.value.status)

      controller.selectHtmlFile("agent-demo/src/web/index.html")
      val restartedUrl = awaitSelectedHtmlUrl(controller)
      assertTrue(restartedUrl.startsWith("http://127.0.0.1:"))
      val restartedStatus = controller.state.value.workspaceArtifactServerStatuses
        .single { it.manifestPath == "agent-demo/flovera.app.json" }
      assertEquals("running", restartedStatus.state)
      assertEquals(restartedUrl, restartedStatus.url)
    } finally {
      controller?.stopWorkspaceArtifactServer("agent-demo/flovera.app.json")
      settingsFile.delete()
      File(File(context.filesDir, "workspaces"), workspaceId).deleteRecursively()
    }
  }

  @Test
  fun seedArtifactRelaysOpenAiCompatibleSseUsingRequestApiKey() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "controller-local-http-upstream-${System.currentTimeMillis()}"
    val settingsStore = SettingsStore(context, File(context.filesDir, "$workspaceId-settings.json")).also {
      it.save(AppSettings(activeWorkspaceId = workspaceId, provider = "", model = ""))
    }
    val controller = AgentController(context, settingsStore = settingsStore).also {
      it.refreshWorkspaceFiles()
      it.selectHtmlFile("agent-demo/src/web/index.html")
    }
    val selectedUrl = awaitSelectedHtmlUrl(controller)
    assertTrue(selectedUrl.startsWith("http://127.0.0.1:"))
    val origin = selectedUrl.trimEnd('/')

    FakeOpenAiCompatibleSseServer().use { upstream ->
      val connection = (URL("$origin/api/chat/stream").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
      }
      val requestBody = """
        {
          "apiKey": "user-owned-apikey",
          "baseUrl": "${upstream.baseUrl}/v1",
          "model": "fake-model",
          "messages": [{"role":"user","content":"hello"}]
        }
      """.trimIndent()
      connection.outputStream.use { stream ->
        stream.write(requestBody.toByteArray(StandardCharsets.UTF_8))
      }

      val sse = connection.inputStream.bufferedReader().use { it.readText() }

      assertTrue(sse.contains("stub-ok"))
      assertTrue(sse.contains("[DONE]"))
      assertEquals("Bearer user-owned-apikey", upstream.authorization())
      assertTrue(upstream.requestBody().contains("\"model\": \"fake-model\""))
    }
    controller.stopWorkspaceArtifactServer("agent-demo/flovera.app.json")
  }

  @Test
  fun workspaceOwnedPythonHttpDoesNotRequireFloveraProviderSettings() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "workspace-owned-http-${System.currentTimeMillis()}"
    val settingsStore = SettingsStore(context, File(context.filesDir, "$workspaceId-settings.json")).also {
      it.save(AppSettings(activeWorkspaceId = workspaceId, provider = "", model = ""))
    }
    val workspace = WorkspaceManager(context, workspaceId)
    workspace.writeFile("own-api/src/web/index.html", "<!doctype html><title>Own API</title><main>Own API Chat</main>", createAutoSnapshot = false)
    workspace.writeFile(
      "own-api/src/server.py",
      """
        import argparse
        import json
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


        class Handler(BaseHTTPRequestHandler):
            def log_message(self, _format, *args):
                return

            def send_body(self, status, content_type, body):
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                if self.path == "/":
                    self.send_body(200, "text/html; charset=utf-8", b"Own API Chat")
                    return
                if self.path == "/api/health":
                    self.send_body(200, "application/json; charset=utf-8", b'{"ok":true,"runtime":"workspace-python-http"}')
                    return
                self.send_body(404, "text/plain; charset=utf-8", b"not found")

            def do_POST(self):
                if self.path != "/api/chat/stream":
                    self.send_body(404, "text/plain; charset=utf-8", b"not found")
                    return
                length = int(self.headers.get("Content-Length") or "0")
                payload = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
                api_key = payload.get("apiKey", "")
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(("data: " + json.dumps({"content": "key=" + api_key[-4:]}) + "\n\n").encode("utf-8"))
                self.wfile.write(b"data: [DONE]\n\n")


        parser = argparse.ArgumentParser()
        parser.add_argument("--host", default="127.0.0.1")
        parser.add_argument("--port", type=int, required=True)
        args = parser.parse_args()
        ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    workspace.writeFile(
      "own-api/flovera.app.json",
      """
      {
        "schemaVersion": 1,
        "name": "own-api",
        "kind": "interactive",
        "entrypoints": {
          "preview": {
            "kind": "local_http",
            "path": "src/web/index.html",
            "urlPath": "/"
          },
          "server": {
            "kind": "python_http",
            "command": "python src/server.py --host 127.0.0.1 --port 8765"
          }
        }
      }
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    val controller = AgentController(context, settingsStore = settingsStore).also {
      it.refreshWorkspaceFiles()
      it.selectHtmlFile("own-api/src/web/index.html")
    }

    val selectedUrl = awaitSelectedHtmlUrl(controller)
    assertTrue(selectedUrl.startsWith("http://127.0.0.1:"))
    assertFalse(selectedUrl.contains("/__flovera__/api/"))
    assertTrue(URL(selectedUrl).readText().contains("Own API Chat"))

    val origin = selectedUrl.trimEnd('/')
    val health = JSONObject(URL("$origin/api/health").readText())
    assertEquals("workspace-python-http", health.getString("runtime"))

    val connection = (URL("$origin/api/chat/stream").openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
    }
    connection.outputStream.use { stream ->
      stream.write("""{"apiKey":"user-owned-apikey","messages":[{"role":"user","content":"hello"}]}""".toByteArray(StandardCharsets.UTF_8))
    }
    val sse = connection.inputStream.bufferedReader().use { it.readText() }
    assertTrue(sse.contains("key=ikey"))
    assertTrue(sse.contains("[DONE]"))
    controller.stopWorkspaceArtifactServer("own-api/flovera.app.json")
  }

  @Test
  fun artifactPythonJobInjectsDeclaredProviderEnvironment() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "controller-artifact-env-${System.currentTimeMillis()}"
    val settingsStore = SettingsStore(context, File(context.filesDir, "$workspaceId-settings.json")).also {
      it.save(AppSettings(activeWorkspaceId = workspaceId, provider = "deepseek", model = "deepseek-v4-pro", apiKey = "test-deepseek-key"))
    }
    val workspace = WorkspaceManager(context, workspaceId)
    workspace.writeFile("env-demo/src/web/index.html", "<!doctype html><title>Env Demo</title>", createAutoSnapshot = false)
    workspace.writeFile(
      "env-demo/src/env_check.py",
      """
        import os
        print(os.environ.get("DEEPSEEK_API_KEY", "missing"))
        print(os.environ.get("DEEPSEEK_MODEL", "missing"))
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    workspace.writeFile(
      "env-demo/flovera.app.json",
      """
      {
        "schema": "https://flovera.dev/schemas/app.v1.json",
        "name": "env-demo",
        "kind": "interactive",
        "entrypoints": {
          "preview": {
            "kind": "webview",
            "path": "src/web/index.html"
          }
        },
        "actions": [
          {
            "id": "check-env",
            "kind": "python_job",
            "command": "python src/env_check.py",
            "environment": {
              "DEEPSEEK_API_KEY": "provider:deepseek.apiKey",
              "DEEPSEEK_MODEL": "provider:deepseek.model"
            }
          }
        ]
      }
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    val controller = AgentController(context, settingsStore = settingsStore).also {
      it.refreshWorkspaceFiles()
      it.selectHtmlFile("env-demo/src/web/index.html")
    }
    val queued = JSONObject(controller.runWorkspaceArtifactAction("check-env", "{}"))
    val jobId = queued.getString("id")

    val finished = withTimeout(10_000) {
      var current = queued
      while (current.optString("status") in setOf("queued", "running")) {
        delay(100)
        current = JSONObject(controller.getWorkspaceArtifactJob(jobId))
      }
      current
    }

    assertEquals("succeeded", finished.getString("status"))
    assertTrue(finished.getString("stdout").contains("test-deepseek-key"))
    assertTrue(finished.getString("stdout").contains("deepseek-v4-pro"))
  }

  @Test
  fun workspaceDiscoversValidArtifactManifest() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "artifact-valid-${System.currentTimeMillis()}")
    workspace.writeFile("demo/src/web/index.html", "<!doctype html><title>Agent Demo</title>", createAutoSnapshot = false)
    workspace.writeFile("demo/src/agent.py", "print('ok')", createAutoSnapshot = false)
    workspace.writeFile(
      "demo/flovera.app.json",
      """
      {
        "schema": "https://flovera.dev/schemas/app.v1.json",
        "name": "agent-demo",
        "kind": "interactive",
        "entrypoints": {
          "preview": {
            "kind": "webview",
            "path": "src/web/index.html"
          }
        },
        "actions": [
          {
            "id": "run-agent",
            "label": "Run Agent",
            "kind": "python_job",
            "command": "python src/agent.py --input data/input.json --output outputs/result.json",
            "timeoutMs": 120000,
            "outputs": ["outputs/result.json"]
          }
        ],
        "outputs": ["outputs/result.json"]
      }
      """.trimIndent(),
      createAutoSnapshot = false,
    )

    val artifact = workspace.listWorkspaceArtifacts().single()

    assertTrue(artifact.valid)
    assertEquals("demo/flovera.app.json", artifact.manifestPath)
    assertEquals("demo", artifact.rootPath)
    assertEquals("agent-demo", artifact.name)
    assertEquals("demo/src/web/index.html", artifact.preview?.path)
    assertEquals("run-agent", artifact.actions.single().id)
    assertEquals("python_job", artifact.actions.single().kind)
    assertEquals("demo", artifact.actions.single().cwd)
    assertEquals(listOf("demo/outputs/result.json"), artifact.actions.single().outputs)
  }

  @Test
  fun workspaceReportsInvalidArtifactManifestDiagnostics() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "artifact-invalid-${System.currentTimeMillis()}")
    workspace.writeFile(
      "bad/flovera.app.json",
      """
      {
        "name": "",
        "entrypoints": {
          "preview": {
            "kind": "webview",
            "path": "../escape.html"
          }
        },
        "actions": [
          {
            "id": "run",
            "kind": "shell",
            "command": "bash run.sh"
          }
        ]
      }
      """.trimIndent(),
      createAutoSnapshot = false,
    )

    val artifact = workspace.listWorkspaceArtifacts().single()

    assertFalse(artifact.valid)
    assertTrue(artifact.diagnostics.any { it.message.contains("non-empty name") })
    assertTrue(artifact.diagnostics.any { it.message.contains("Unsupported action kind") })
    assertTrue(artifact.diagnostics.any { it.message.contains("Path does not exist") || it.message.contains("escapes workspace") })
  }

  @Test
  fun workspaceArtifactJobsPersistAndRestartInterruptsRunningJobs() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "artifact-job-${System.currentTimeMillis()}"
    val workspace = WorkspaceManager(context, workspaceId)
    workspace.writeFile("demo/src/web/index.html", "<!doctype html>", createAutoSnapshot = false)
    workspace.writeFile("demo/src/agent.py", "print('ok')", createAutoSnapshot = false)
    workspace.writeFile(
      "demo/flovera.app.json",
      """
      {
        "name": "agent-demo",
        "entrypoints": {
          "preview": { "kind": "webview", "path": "src/web/index.html" }
        },
        "actions": [
          {
            "id": "run-agent",
            "kind": "python_job",
            "command": "python src/agent.py --input data/input.json",
            "outputs": ["outputs/result.json"]
          }
        ]
      }
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    val target = workspace.resolveWorkspaceArtifactAction("demo/src/web/index.html", "run-agent")!!
    val job = workspace.createWorkspaceArtifactJob(target)
    val running = workspace.writeWorkspaceArtifactJob(job.copy(status = "running"))

    assertEquals("running", workspace.readWorkspaceArtifactJob(running.id)?.status)
    assertTrue(workspace.workspaceArtifactJobJson(running.id).contains("\"actionId\":\"run-agent\""))

    val restartedWorkspace = WorkspaceManager(context, workspaceId)
    restartedWorkspace.ensureFloveraMetadata()

    val interrupted = restartedWorkspace.readWorkspaceArtifactJob(running.id)
    assertNotNull(interrupted)
    assertEquals("interrupted", interrupted!!.status)
  }

  @Test
  fun workspaceArtifactPythonJobRunsScriptAndWritesOutputs() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "artifact-python-job-${System.currentTimeMillis()}")
    workspace.writeFile("demo/data/input.json", """{"message":"hello"}""", createAutoSnapshot = false)
    workspace.writeFile(
      "demo/src/agent.py",
      """
      import argparse
      import json
      import os

      parser = argparse.ArgumentParser()
      parser.add_argument("--input", required=True)
      parser.add_argument("--output", required=True)
      args = parser.parse_args()

      with open(args.input, "r", encoding="utf-8") as handle:
          payload = json.load(handle)
      os.makedirs(os.path.dirname(args.output), exist_ok=True)
      with open(args.output, "w", encoding="utf-8") as handle:
          json.dump({"reply": payload["message"] + " world"}, handle)
      print("wrote", args.output)
      """.trimIndent(),
      createAutoSnapshot = false,
    )

    val result = FloveraPythonRuntime(workspace, networkEnabled = false).runScript(
      scriptPath = "src/agent.py",
      argv = listOf("--input", "data/input.json", "--output", "outputs/result.json"),
      cwd = "demo",
      timeoutMs = 30_000,
      sessionId = "artifact-test",
    )

    assertEquals(0, result.exitCode)
    assertTrue(result.stdout.contains("wrote outputs/result.json"))
    assertTrue(workspace.readFile("demo/outputs/result.json").contains("hello world"))
  }

  @Test
  fun floveraWebBridgeRoutesWorkspaceArtifactCalls() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val bridge = FloveraWebBridge(
      context,
      object : FloveraWebBridge.ArtifactActions {
        override fun runAction(actionId: String, inputJson: String): String = "run:$actionId:$inputJson"
        override fun getJob(jobId: String): String = "get:$jobId"
        override fun cancelJob(jobId: String): String = "cancel:$jobId"
      },
    )

    assertEquals("run:run-agent:{\"x\":1}", bridge.runAction("run-agent", """{"x":1}"""))
    assertEquals("get:job-1", bridge.getJob("job-1"))
    assertEquals("cancel:job-1", bridge.cancelJob("job-1"))
  }

  @Test
  fun workspaceSearchFindsTextAndRecordsToolEvent() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "search-workspace-${System.currentTimeMillis()}")
    val recorder = ToolEventRecorder()
    workspace.writeFile("src/ProviderRoutes.kt", "fun routeCopilot() = \"Codex Responses transport\"")
    workspace.writeFile("notes.md", "Provider routing notes")

    val result = WorkspaceSearchTool(workspace, recorder).execute(
      WorkspaceSearchTool.Args(query = "copilot responses", topK = 5),
    )

    assertTrue(result.contains("src/ProviderRoutes.kt:1"))
    assertTrue(result.contains("Codex Responses transport"))
    assertTrue(recorder.snapshot().any { it.name == "workspace_search" })
  }

  @Test
  fun workspaceSearchScopesFloveraMetadataByPermission() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "search-scope-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val tool = WorkspaceSearchTool(workspace, ToolEventRecorder())
    workspace.writeFile(
      ".flovera/proposals/search-tool.json",
      """{"type":"tool","name":"workspace_search","description":"metadataonlymarker"}""",
      createAutoSnapshot = false,
    )
    workspace.writeFile(".flovera/internal/debug.txt", "internalonlymarker", createAutoSnapshot = false)
    workspace.writeFile(".flovera/retrieval/index.json", """{"text":"retrievalonlymarker"}""", createAutoSnapshot = false)

    val publicResult = tool.execute(WorkspaceSearchTool.Args(query = "metadataonlymarker"))
    val metadataResult = tool.execute(
      WorkspaceSearchTool.Args(query = "metadataonlymarker", scope = "workspace_app_metadata"),
    )
    val internalMetadataResult = tool.execute(
      WorkspaceSearchTool.Args(query = "internalonlymarker", scope = "workspace_app_metadata"),
    )
    val skillMetadataResult = tool.execute(
      WorkspaceSearchTool.Args(query = "artifact_diagnose", scope = "workspace_app_metadata"),
    )
    val internalResult = tool.execute(
      WorkspaceSearchTool.Args(query = "internalonlymarker", scope = "workspace_internal"),
    )
    val retrievalResult = tool.execute(
      WorkspaceSearchTool.Args(query = "retrievalonlymarker", scope = "workspace_internal"),
    )

    assertTrue(publicResult.contains("No matches"))
    assertTrue(metadataResult.contains(".flovera/proposals/search-tool.json:1"))
    assertTrue(internalMetadataResult.contains("No matches"))
    assertTrue(skillMetadataResult.contains(".flovera/skills/flovera-android-webview-app/SKILL.md"))
    assertTrue(internalResult.contains(".flovera/internal/debug.txt:1"))
    assertTrue(retrievalResult.contains("No matches"))
  }

  @Test
  fun workspaceSearchSkipsBinaryAndLargeFiles() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "search-filter-${System.currentTimeMillis()}")
    val tool = WorkspaceSearchTool(workspace, ToolEventRecorder())
    workspace.writeBytes("assets/blob.bin", byteArrayOf(0, 1, 2, 3, 4), createAutoSnapshot = false)
    workspace.writeFile("large.txt", "needle\n" + "x".repeat(600 * 1024), createAutoSnapshot = false)
    workspace.writeFile(".secret.txt", "needle", createAutoSnapshot = false)
    workspace.writeFile("small.txt", "needle is here", createAutoSnapshot = false)

    val result = tool.execute(WorkspaceSearchTool.Args(query = "needle", topK = 10))

    assertTrue(result.contains("small.txt:1"))
    assertFalse(result.contains("large.txt"))
    assertFalse(result.contains("blob.bin"))
    assertFalse(result.contains(".secret.txt"))
  }

  @Test
  fun workspaceSearchL2OptionsNarrowAndShapeResults() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "search-l2-${System.currentTimeMillis()}")
    val tool = WorkspaceSearchTool(workspace, ToolEventRecorder())
    workspace.writeFile(
      "src/main/Routes.kt",
      """
      package demo
      fun routeProvider() {
        val transport = "Codex Responses"
      }
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    workspace.writeFile("src/test/RoutesTest.kt", "val transport = \"test\"")
    workspace.writeFile("docs/routes.md", "transport docs")

    val scoped = tool.execute(
      WorkspaceSearchTool.Args(query = "transport", path = "src/main", topK = 10),
    )
    val includeGlob = tool.execute(
      WorkspaceSearchTool.Args(query = "transport", includeGlob = "src/**/*.kt", excludeGlob = "src/test/**", topK = 10),
    )
    val fileTypeGlob = tool.execute(
      WorkspaceSearchTool.Args(query = "transport", includeGlob = "*.kt", topK = 10),
    )
    val contextResult = tool.execute(
      WorkspaceSearchTool.Args(query = "transport", path = "src/main/Routes.kt", contextLines = 1),
    )

    assertTrue(scoped.contains("src/main/Routes.kt:3"))
    assertFalse(scoped.contains("src/test/RoutesTest.kt"))
    assertFalse(scoped.contains("docs/routes.md"))
    assertTrue(includeGlob.contains("src/main/Routes.kt:3"))
    assertFalse(includeGlob.contains("src/test/RoutesTest.kt"))
    assertFalse(includeGlob.contains("docs/routes.md"))
    assertTrue(fileTypeGlob.contains("src/main/Routes.kt:3"))
    assertTrue(fileTypeGlob.contains("src/test/RoutesTest.kt:1"))
    assertFalse(fileTypeGlob.contains("docs/routes.md"))
    assertTrue(contextResult.contains(" 2: fun routeProvider()"))
    assertTrue(contextResult.contains(">3: val transport"))
    assertTrue(contextResult.contains(" 4: }"))
  }

  @Test
  fun workspaceSearchSupportsRegexAndCaseSensitivity() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "search-regex-${System.currentTimeMillis()}")
    val tool = WorkspaceSearchTool(workspace, ToolEventRecorder())
    workspace.writeFile("code.kt", "val ProviderTransport = \"ok\"\nval providertransport = \"lower\"", createAutoSnapshot = false)

    val regex = tool.execute(
      WorkspaceSearchTool.Args(query = "Provider[A-Za-z]+", mode = "regex"),
    )
    val caseSensitive = tool.execute(
      WorkspaceSearchTool.Args(query = "ProviderTransport", caseSensitive = true, topK = 10),
    )
    val invalidRegex = tool.execute(
      WorkspaceSearchTool.Args(query = "[", mode = "regex"),
    )

    assertTrue(regex.contains("code.kt:1"))
    assertTrue(caseSensitive.contains("code.kt:1"))
    assertFalse(caseSensitive.contains("code.kt:2"))
    assertTrue(invalidRegex.contains("Invalid regex"))
  }

  @Test
  fun workspaceSearchL3RespectsIgnoreFilesAndCanDisableThem() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "search-l3-ignore-${System.currentTimeMillis()}")
    val tool = WorkspaceSearchTool(workspace, ToolEventRecorder())
    workspace.writeFile(
      ".gitignore",
      """
      build/
      *.log
      !important.log
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    workspace.writeFile("src/App.kt", "needle in source", createAutoSnapshot = false)
    workspace.writeFile("build/generated.txt", "needle in build", createAutoSnapshot = false)
    workspace.writeFile("debug.log", "needle in log", createAutoSnapshot = false)
    workspace.writeFile("important.log", "needle in important log", createAutoSnapshot = false)

    val respected = tool.execute(WorkspaceSearchTool.Args(query = "needle", topK = 10))
    val disabled = tool.execute(WorkspaceSearchTool.Args(query = "needle", respectIgnoreFiles = false, topK = 10))

    assertTrue(respected.contains("src/App.kt:1"))
    assertTrue(respected.contains("important.log:1"))
    assertFalse(respected.contains("build/generated.txt"))
    assertFalse(respected.contains("debug.log"))
    assertTrue(disabled.contains("build/generated.txt:1"))
    assertTrue(disabled.contains("debug.log:1"))
  }

  @Test
  fun workspaceSearchL3SupportsFilesCountAndScanBudget() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "search-l3-output-${System.currentTimeMillis()}")
    val tool = WorkspaceSearchTool(workspace, ToolEventRecorder())
    workspace.writeFile("src/A.kt", "needle one\nneedle two", createAutoSnapshot = false)
    workspace.writeFile("src/B.kt", "needle one", createAutoSnapshot = false)
    workspace.writeFile("src/C.kt", "needle one", createAutoSnapshot = false)

    val files = tool.execute(WorkspaceSearchTool.Args(query = "needle", path = "src", output = "files", topK = 10))
    val count = tool.execute(WorkspaceSearchTool.Args(query = "needle", path = "src", output = "count", topK = 10))
    val budget = tool.execute(WorkspaceSearchTool.Args(query = "needle", path = "src", maxFiles = 1, topK = 10))

    assertTrue(files.contains("Found 3 files"))
    assertTrue(files.contains("src/A.kt"))
    assertFalse(files.contains("score="))
    assertTrue(count.contains("Found 4 matches in 3 files"))
    assertTrue(count.contains("src/A.kt count=2"))
    assertTrue(budget.contains("stoppedAfterMaxFiles=1"))
    assertFalse(budget.contains("src/B.kt"))
    assertFalse(budget.contains("src/C.kt"))
  }

  @Test
  fun workspaceSearchL35GroupsContextAndKeepsDebugOptIn() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "search-l35-output-${System.currentTimeMillis()}")
    val tool = WorkspaceSearchTool(workspace, ToolEventRecorder())
    workspace.writeFile(
      "src/Merged.kt",
      """
      fun before() = Unit
      val firstNeedle = "alpha"
      val secondNeedle = "beta"
      fun after() = Unit
      """.trimIndent(),
      createAutoSnapshot = false,
    )

    val compact = tool.execute(
      WorkspaceSearchTool.Args(query = "Needle", path = "src/Merged.kt", contextLines = 1, topK = 10),
    )
    val debug = tool.execute(
      WorkspaceSearchTool.Args(query = "Needle", path = "src/Merged.kt", contextLines = 1, topK = 10, debug = true),
    )

    assertTrue(compact.contains("src/Merged.kt:2,3"))
    assertEquals(compact.indexOf(">2: val firstNeedle"), compact.lastIndexOf(">2: val firstNeedle"))
    assertEquals(compact.indexOf(">3: val secondNeedle"), compact.lastIndexOf(">3: val secondNeedle"))
    assertTrue(compact.contains(" 1: fun before()"))
    assertTrue(compact.contains(" 4: fun after()"))
    assertFalse(compact.contains("score="))
    assertFalse(compact.contains("scannedFiles="))
    assertTrue(debug.contains("maxScore="))
    assertTrue(debug.contains("scannedFiles="))
    assertTrue(debug.contains("elapsedMs="))
  }

  @Test
  fun pythonRunCalculatesAndWritesWorkspaceFiles() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "python-run-${System.currentTimeMillis()}")
    val tool = PythonRunTool(workspace, ToolEventRecorder())

    val result = tool.execute(
      PythonRunTool.Args(
        code = """
        import json
        import math
        import os

        os.makedirs("out", exist_ok=True)
        with open("out/result.json", "w", encoding="utf-8") as handle:
            json.dump({"hypot": math.hypot(3, 4)}, handle)
        print("computed", math.factorial(5))
        """.trimIndent(),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Python status=ok exitCode=0"))
    assertTrue(result, result.contains("computed 120"))
    assertTrue(result, workspace.readFile("out/result.json").contains("\"hypot\": 5.0"))
  }

  @Test
  fun workspaceCommandRunExecutesPythonScriptsWithArgv() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-${System.currentTimeMillis()}")
    workspace.writeFile(
      "tools/echo_args.py",
      """
      import json
      import os
      import sys

      os.makedirs("out", exist_ok=True)
      with open("out/argv.json", "w", encoding="utf-8") as handle:
          json.dump({"argv": sys.argv[1:]}, handle)
      print("args=" + ",".join(sys.argv[1:]))
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder())

    val result = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("python", "tools/echo_args.py", "alpha", "beta"),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Workspace command status=ok exitCode=0"))
    assertTrue(result, result.contains("authorization=allowed authority=safe risk=python.workspace_script"))
    assertTrue(result, result.contains("permissions=workspace.read|workspace.write|workspace_script"))
    assertTrue(result, result.contains("command=python tools/echo_args.py alpha beta"))
    assertTrue(result, result.contains("args=alpha,beta"))
    assertTrue(workspace.readFile("out/argv.json").contains("\"alpha\""))
    val audit = workspace.readFile(".flovera/logs/workspace-command.jsonl")
    assertTrue(audit, audit.contains("\"riskCategory\":\"python.workspace_script\""))
    assertTrue(audit, audit.contains("\"allowed\":true"))
  }

  @Test
  fun workspaceCommandRunExecutesGenericFloveraAutomationScript() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-flovera-script-${System.currentTimeMillis()}")
    workspace.writeFile(
      "tools/write_note.py",
      """
      import os
      import sys

      os.makedirs("out", exist_ok=True)
      with open("out/script-note.txt", "w", encoding="utf-8") as handle:
          handle.write(sys.argv[1])
      print("note=" + sys.argv[1])
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    workspace.writeFile(
      ".flovera/scripts/write-note.json",
      """
      {
        "name": "write-note",
        "description": "Write a note through a generic Flovera automation script.",
        "steps": [
          {
            "name": "write",
            "argv": ["python", "tools/write_note.py", "{{message}}"],
            "timeoutMs": 30000
          }
        ]
      }
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder())

    val list = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("flovera", "script", "list"),
        snapshotBeforeRun = false,
      ),
    )
    val run = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("flovera", "script", "run", "write-note", "--param", "message=hello-script"),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(list, list.contains("\"name\": \"write-note\""))
    assertTrue(run, run.contains("Workspace command status=ok exitCode=0"))
    assertTrue(run, run.contains("\"status\": \"completed\""))
    assertTrue(run, run.contains("note=hello-script"))
    assertTrue(workspace.readFile("out/script-note.txt").contains("hello-script"))
    val audit = workspace.readFile(".flovera/logs/workspace-command.jsonl")
    assertTrue(audit, audit.contains("\"riskCategory\":\"flovera.script\""))
  }

  @Test
  fun workspaceCommandRunInspectsAndEditsOfficeOoxmlPackages() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-office-${System.currentTimeMillis()}")
    writeMinimalOfficePackage(workspace, "docs/sample.docx", "docx", "Hello DOCX")
    writeMinimalOfficePackage(workspace, "docs/sample.xlsx", "xlsx", "Hello XLSX")
    writeMinimalOfficePackage(workspace, "docs/sample.pptx", "pptx", "Hello PPTX")
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder())

    val docxInspect = tool.execute(
      WorkspaceCommandRunTool.Args(argv = listOf("flovera", "office", "inspect", "docs/sample.docx"), snapshotBeforeRun = false),
    )
    val xlsxText = tool.execute(
      WorkspaceCommandRunTool.Args(argv = listOf("flovera", "office", "text", "docs/sample.xlsx"), snapshotBeforeRun = false),
    )
    val docxPoiText = tool.execute(
      WorkspaceCommandRunTool.Args(argv = listOf("flovera", "office", "text", "docs/sample.docx", "--backend", "poi"), snapshotBeforeRun = false),
    )
    val pptxValidate = tool.execute(
      WorkspaceCommandRunTool.Args(argv = listOf("flovera", "office", "validate", "docs/sample.pptx"), snapshotBeforeRun = false),
    )
    val replaced = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf(
          "flovera", "office", "replace", "docs/sample.docx",
          "--find", "Hello DOCX",
          "--replace", "Updated DOCX",
          "--output", "out/updated.docx",
          "--backend", "poi",
        ),
        snapshotBeforeRun = false,
      ),
    )
    val updatedText = tool.execute(
      WorkspaceCommandRunTool.Args(argv = listOf("flovera", "office", "text", "out/updated.docx"), snapshotBeforeRun = false),
    )

    assertTrue(docxInspect, docxInspect.contains("Workspace command status=ok exitCode=0"))
    assertTrue(docxInspect, docxInspect.contains("\"type\": \"docx\""))
    assertTrue(docxInspect, docxInspect.contains("\"docx4j\": false"))
    assertTrue(docxInspect, docxInspect.contains("\"docx4jUnavailableReason\": \"missing_android_java_awt_image\""))
    assertTrue(docxInspect, docxInspect.contains("\"poi\": true"))
    assertTrue(docxInspect, docxInspect.contains("Hello DOCX"))
    assertTrue(xlsxText, xlsxText.contains("\"type\": \"xlsx\""))
    assertTrue(xlsxText, xlsxText.contains("Hello XLSX"))
    assertTrue(docxPoiText, docxPoiText.contains("\"backend\": \"poi\""))
    assertTrue(docxPoiText, docxPoiText.contains("Hello DOCX"))
    assertTrue(pptxValidate, pptxValidate.contains("\"type\": \"pptx\""))
    assertTrue(pptxValidate, pptxValidate.contains("\"valid\": true"))
    assertTrue(pptxValidate, pptxValidate.contains("\"poi\": false"))
    assertTrue(pptxValidate, pptxValidate.contains("\"poiUnavailableReason\": \"missing_android_java_awt_geom_rectangle2d\""))
    assertTrue(pptxValidate, pptxValidate.contains("\"supportedBackends\""))
    assertTrue(replaced, replaced.contains("\"replacements\": 1"))
    assertTrue(replaced, replaced.contains("\"backend\": \"poi\""))
    assertTrue(replaced, replaced.contains("updated.docx"))
    assertTrue(updatedText, updatedText.contains("Updated DOCX"))
    val audit = workspace.readFile(".flovera/logs/workspace-command.jsonl")
    assertTrue(audit, audit.contains("\"riskCategory\":\"flovera.office\""))
  }

  private fun writeMinimalOfficePackage(workspace: WorkspaceManager, path: String, type: String, text: String) {
    val file = File(workspace.root, path)
    file.parentFile?.mkdirs()
    ZipOutputStream(file.outputStream()).use { output ->
      fun entry(name: String, content: String) {
        output.putNextEntry(ZipEntry(name))
        output.write(content.toByteArray(StandardCharsets.UTF_8))
        output.closeEntry()
      }
      entry(
        "[Content_Types].xml",
        when (type) {
          "docx" -> """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
          "xlsx" -> """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/></Types>"""
          else -> """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/></Types>"""
        },
      )
      entry(
        "_rels/.rels",
        when (type) {
          "docx" -> """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
          "xlsx" -> """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
          else -> """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/></Relationships>"""
        },
      )
      when (type) {
        "docx" -> {
          entry(
            "word/_rels/document.xml.rels",
            """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>""",
          )
          entry(
            "word/document.xml",
            """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p><w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>""",
          )
        }
        "xlsx" -> {
          entry(
            "xl/workbook.xml",
            """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>""",
          )
          entry(
            "xl/_rels/workbook.xml.rels",
            """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/></Relationships>""",
          )
          entry(
            "xl/sharedStrings.xml",
            """<?xml version="1.0" encoding="UTF-8"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="1" uniqueCount="1"><si><t>$text</t></si></sst>""",
          )
          entry(
            "xl/worksheets/sheet1.xml",
            """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c></row></sheetData></worksheet>""",
          )
        }
        "pptx" -> {
          entry(
            "ppt/presentation.xml",
            """<?xml version="1.0" encoding="UTF-8"?><p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst></p:presentation>""",
          )
          entry(
            "ppt/_rels/presentation.xml.rels",
            """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/></Relationships>""",
          )
          entry(
            "ppt/slides/slide1.xml",
            """<?xml version="1.0" encoding="UTF-8"?><p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/><p:sp><p:nvSpPr><p:cNvPr id="2" name="Text"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>""",
          )
        }
      }
    }
  }

  @Test
  fun workspaceCapabilitiesExposeAutomationScriptAndDesktopHardening() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-capabilities-script-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }

    val capabilities = workspace.readFile(".flovera/capabilities.json")

    assertTrue(capabilities.contains("\"flovera\""))
    assertTrue(capabilities.contains("\"flovera-script\""))
    assertTrue(capabilities.contains("\"workspaceAutomationScripts\": true"))
    assertTrue(capabilities.contains("\"workspaceAutomationScriptPath\": \".flovera/scripts\""))
    assertTrue(capabilities.contains("\"workspaceAutomationScriptRunner\": \"flovera script run\""))
    assertTrue(capabilities.contains("\"officeSuiteSkillId\": \"flovera-office-suite\""))
    assertTrue(capabilities.contains("\"officeDocumentCreationMode\": \"format_routed_android_compatible_engines\""))
    assertTrue(capabilities.contains("\"xlsx\": \"openpyxl\""))
    assertTrue(capabilities.contains("\"docx\": \"python-docx\""))
    assertTrue(capabilities.contains("\"pptx\": \"python-pptx-with-android-pil-compatibility\""))
    assertTrue(capabilities.contains("\"pdf\": \"fpdf2-with-android-cjk-font\""))
    assertTrue(capabilities.contains("\"officeOoxmlRuntime\": true"))
    assertTrue(capabilities.contains("\"officeOoxmlRuntimeMode\": \"lightweight_zip_xml_with_runtime_only_poi_docx_xlsx_backend\""))
    assertTrue(capabilities.contains("\"officeOoxmlSupportedFormats\""))
    assertTrue(capabilities.contains("\"docx\""))
    assertTrue(capabilities.contains("\"xlsx\""))
    assertTrue(capabilities.contains("\"pptx\""))
    assertTrue(capabilities.contains("\"officeOoxmlPoiBackendFormats\""))
    assertTrue(capabilities.contains("\"officeOoxmlLightBackendFormats\""))
    assertTrue(capabilities.contains("\"flovera office inspect <path>\""))
    assertTrue(capabilities.contains("\"officeOoxmlHeavyBackends\""))
    assertTrue(capabilities.contains("\"apache-poi\""))
    assertTrue(capabilities.contains("\"officeOoxmlUnavailableBackends\""))
    assertTrue(capabilities.contains("\"officeOoxmlHeavyBackendLoadMode\": \"runtime_only_reflection\""))
    assertTrue(capabilities.contains("POI XSLF depends on Java SE/AWT geometry APIs"))
    assertTrue(capabilities.contains("ordinary Maven docx4j depends on Java SE/AWT/JAXB APIs"))
    assertTrue(capabilities.contains("\"apache-poi\""))
    assertTrue(capabilities.contains("\"androidAppIndex\": true"))
    assertTrue(capabilities.contains("\"androidDirectAppLaunch\": false"))
    assertTrue(capabilities.contains("\"androidDesktopClickVerificationFallback\": true"))
    assertTrue(capabilities.contains("\"androidDesktopSetTextFocusRetry\": true"))
    assertTrue(capabilities.contains("\"androidDesktopFailureDiagnosisPath\": \".flovera/logs/ui-diagnosis\""))
  }

  @Test
  fun workspaceCommandRunExecutesMultilinePythonCommandCode() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-python-c-${System.currentTimeMillis()}")
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder(), authorityMode = "full")

    val result = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf(
          "python",
          "-c",
          """
          import os
          os.makedirs("out", exist_ok=True)
          with open("out/from-python-c.txt", "w", encoding="utf-8") as handle:
              handle.write("ok")
          print("python-c-ok")
          """.trimIndent(),
        ),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Workspace command status=ok exitCode=0"))
    assertTrue(result, result.contains("python-c-ok"))
    assertTrue(workspace.readFile("out/from-python-c.txt").contains("ok"))
  }

  @Test
  fun workspaceCommandRunInjectsAllowedSecretsIntoPythonEnvironment() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-secret-env-${System.currentTimeMillis()}")
    val tool = WorkspaceCommandRunTool(
      workspace = workspace,
      recorder = ToolEventRecorder(),
      authorityMode = "full",
      secretEnvironment = mapOf("AMAP_API_KEY" to "secret-env-value"),
    )

    val result = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf(
          "python",
          "-c",
          "import os; print('secret=' + os.environ.get('AMAP_API_KEY', 'missing'))",
        ),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Workspace command status=ok exitCode=0"))
    assertTrue(result, result.contains("secret=secret-env-value"))
  }

  @Test
  fun workspaceCommandRunExecutesLocalJGitCommands() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-git-${System.currentTimeMillis()}")
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder(), authorityMode = "full")

    val init = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("git", "init"),
        snapshotBeforeRun = false,
      ),
    )
    workspace.writeFile("notes.md", "alpha\n", createAutoSnapshot = false)
    val status = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("git", "status"),
        snapshotBeforeRun = false,
      ),
    )
    val add = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("git", "add", "."),
        snapshotBeforeRun = false,
      ),
    )
    val commit = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("git", "commit", "-m", "Add notes"),
        snapshotBeforeRun = false,
      ),
    )
    workspace.writeFile("notes.md", "alpha\nbeta\n", createAutoSnapshot = false)
    val diff = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("git", "diff"),
        snapshotBeforeRun = false,
      ),
    )
    val log = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("git", "log", "-n1"),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(init, init.contains("risk=git.write"))
    assertTrue(init, init.contains("initialized=true"))
    assertTrue(status, status.contains("untracked=notes.md"))
    assertTrue(add, add.contains("added=notes.md"))
    assertTrue(commit, commit.contains("message=Add notes"))
    assertTrue(diff, diff.contains("+beta"))
    assertTrue(log, log.contains("Add notes"))
  }

  @Test
  fun workspaceCommandRunReportsAndroidPermissionStatus() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val packageName = context.packageName
    val workspace = WorkspaceManager(context, "workspace-command-android-${System.currentTimeMillis()}")
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder(), networkEnabled = true)

    val app = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "app", "info"),
        snapshotBeforeRun = false,
      ),
    )
    val permissions = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "permission", "status"),
        snapshotBeforeRun = false,
      ),
    )
    val appList = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "app", "list", "--query", packageName, "--limit", "5"),
        snapshotBeforeRun = false,
      ),
    )
    val appResolve = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "app", "resolve", "--name", packageName),
        snapshotBeforeRun = false,
      ),
    )
    val help = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "help"),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(app, app.contains("\"permissionsPanel\": true"))
    assertTrue(app, app.contains("\"profiles\""))
    assertTrue(appList, appList.contains("\"apps\""))
    assertTrue(appResolve, appResolve.contains("\"matched\""))
    assertTrue(appResolve, appResolve.contains(packageName))
    assertTrue(permissions, permissions.contains("\"permissions\""))
    assertTrue(permissions, permissions.contains("\"id\": \"notifications\""))
    assertTrue(permissions, permissions.contains("\"id\": \"battery_optimization\""))
    listOf(
      "notification",
      "camera",
      "microphone",
      "location",
      "contacts",
      "calendar",
      "media",
      "bluetooth",
      "overlay",
      "storage",
      "package",
      "alarm",
      "network",
      "foreground",
      "intent",
    ).forEach { profile ->
      assertTrue("missing profile=$profile\n$help", help.contains("\"$profile\""))
    }
    assertTrue(help, help.contains("contacts list|search|create|delete"))
    assertTrue(help, help.contains("app info|list|resolve"))
    assertTrue(help, help.contains("calendar calendars|events|create|delete"))
    assertTrue(help, help.contains("camera capture"))
    assertTrue(help, help.contains("microphone record"))
  }

  @Test
  fun workspaceCommandRunCallsReadOnlyAndroidSystemApis() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-android-read-${System.currentTimeMillis()}")
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder(), networkEnabled = true)

    val contacts = tool.execute(
      WorkspaceCommandRunTool.Args(argv = listOf("android", "contacts", "list", "--limit", "2"), snapshotBeforeRun = false),
    )
    val calendars = tool.execute(
      WorkspaceCommandRunTool.Args(argv = listOf("android", "calendar", "calendars", "--limit", "2"), snapshotBeforeRun = false),
    )
    val media = tool.execute(
      WorkspaceCommandRunTool.Args(argv = listOf("android", "media", "list", "--type", "images", "--limit", "2"), snapshotBeforeRun = false),
    )
    val bluetooth = tool.execute(
      WorkspaceCommandRunTool.Args(argv = listOf("android", "bluetooth", "paired"), snapshotBeforeRun = false),
    )
    val storage = tool.execute(
      WorkspaceCommandRunTool.Args(argv = listOf("android", "storage", "list", "--path", ".", "--limit", "2"), snapshotBeforeRun = false),
    )
    val foreground = tool.execute(
      WorkspaceCommandRunTool.Args(argv = listOf("android", "foreground", "status"), snapshotBeforeRun = false),
    )

    assertTrue(contacts, contacts.contains("\"contacts\""))
    assertTrue(calendars, calendars.contains("\"calendars\""))
    assertTrue(media, media.contains("\"items\""))
    assertTrue(bluetooth, bluetooth.contains("\"devices\""))
    assertTrue(storage, storage.contains("\"items\""))
    assertTrue(foreground, foreground.contains("\"running\""))
  }

  @Test
  fun workspaceCommandRunPostsAndCancelsAndroidNotificationAndAlarm() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-android-alert-${System.currentTimeMillis()}")
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder())
    val notificationId = 7821
    val alarmId = 7822
    val atMs = System.currentTimeMillis() + 10 * 60 * 1000L

    val posted = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "notification", "post", "--title", "Flovera API test", "--body", "notification path", "--id", notificationId.toString()),
        snapshotBeforeRun = false,
      ),
    )
    val cancelled = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "notification", "cancel", "--id", notificationId.toString()),
        snapshotBeforeRun = false,
      ),
    )
    val scheduled = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf(
          "android", "alarm", "schedule",
          "--id", alarmId.toString(),
          "--at-ms", atMs.toString(),
          "--title", "Flovera API test",
          "--body", "alarm path",
        ),
        snapshotBeforeRun = false,
      ),
    )
    val alarmCancelled = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "alarm", "cancel", "--id", alarmId.toString()),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(posted, posted.contains("\"posted\":true"))
    assertTrue(cancelled, cancelled.contains("\"cancelled\":true"))
    assertTrue(scheduled, scheduled.contains("\"scheduled\": true"))
    assertTrue(alarmCancelled, alarmCancelled.contains("\"cancelled\":true"))
  }

  @Test
  fun workspaceCommandRunCapturesCameraAndMicrophoneIntoWorkspace() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-android-capture-${System.currentTimeMillis()}")
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder())

    val camera = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "camera", "capture", "--output", "captures/test.jpg", "--lens", "back"),
        timeoutMs = 30_000,
        snapshotBeforeRun = false,
      ),
    )
    val microphone = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "microphone", "record", "--output", "recordings/test.m4a", "--duration-ms", "500"),
        timeoutMs = 10_000,
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(camera, camera.contains("captures\\/test.jpg"))
    assertTrue(File(workspace.root, "captures/test.jpg").length() > 0)
    assertTrue(microphone, microphone.contains("recordings\\/test.m4a"))
    assertTrue(File(workspace.root, "recordings/test.m4a").length() > 0)
  }

  @Test
  fun workspaceCommandRunCallsLocationOverlayForegroundAndNetworkApis() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-android-native-${System.currentTimeMillis()}")
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder(), networkEnabled = true)
    val server = ServerSocket(0)
    val serverThread = thread(start = true) {
      server.use { socket ->
        socket.accept().use { client ->
          client.getInputStream().bufferedReader().readLine()
          while (client.getInputStream().available() > 0) client.getInputStream().read()
          val body = """{"native":"ok"}"""
          client.getOutputStream().bufferedWriter().use { writer ->
            writer.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body")
          }
        }
      }
    }

    val location = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "location", "current", "--timeout-ms", "3000"),
        timeoutMs = 5_000,
        snapshotBeforeRun = false,
      ),
    )
    val overlay = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "overlay", "show", "--text", "Flovera API test", "--duration-ms", "500"),
        snapshotBeforeRun = false,
      ),
    )
    val overlayHidden = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "overlay", "hide"),
        snapshotBeforeRun = false,
      ),
    )
    val foregroundStarted = tool.execute(
      WorkspaceCommandRunTool.Args(argv = listOf("android", "foreground", "start"), snapshotBeforeRun = false),
    )
    delay(200)
    val foregroundStatus = tool.execute(
      WorkspaceCommandRunTool.Args(argv = listOf("android", "foreground", "status"), snapshotBeforeRun = false),
    )
    val foregroundStopped = tool.execute(
      WorkspaceCommandRunTool.Args(argv = listOf("android", "foreground", "stop"), snapshotBeforeRun = false),
    )
    val network = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "network", "get", "--url", "http://127.0.0.1:${server.localPort}/health"),
        snapshotBeforeRun = false,
      ),
    )
    serverThread.join(3_000)

    assertTrue(location, location.contains("status=ok"))
    assertTrue(location, location.contains("\"latitude\""))
    assertTrue(location, location.contains("\"source\""))
    assertTrue(location, location.contains("\"ageMs\""))
    assertTrue(location, location.contains("\"enabledProviders\""))
    assertTrue(overlay, overlay.contains("\"shown\":true"))
    assertTrue(overlayHidden, overlayHidden.contains("\"hidden\":true"))
    assertTrue(foregroundStarted, foregroundStarted.contains("\"started\":true"))
    assertTrue(foregroundStatus, foregroundStatus.contains("\"running\": true") || foregroundStatus.contains("\"running\":true"))
    assertTrue(foregroundStopped, foregroundStopped.contains("\"stopped\":true"))
    assertTrue(network, network.contains("\"statusCode\": 200"))
    assertTrue(network, network.contains("\\\"native\\\":\\\"ok\\\""))
  }

  @Test
  fun workspaceCommandRunPersistsDesktopTaskInterventionAndResume() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-desktop-task-${System.currentTimeMillis()}")
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder())

    val started = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "ui", "task", "start", "--goal", "Open Android settings"),
        snapshotBeforeRun = false,
      ),
    )
    val intervention = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "ui", "task", "intervention", "--reason", "User confirmation required"),
        snapshotBeforeRun = false,
      ),
    )
    val blockedWhileIntervening = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "ui", "global", "--action", "home", "--action-id", "blocked-during-intervention"),
        snapshotBeforeRun = false,
      ),
    )
    val resumed = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "ui", "task", "resume"),
        snapshotBeforeRun = false,
      ),
    )
    val completed = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "ui", "task", "complete", "--summary", "Verified"),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(started, started.contains("\"status\": \"active\""))
    assertTrue(intervention, intervention.contains("\"status\": \"intervention\""))
    assertTrue(intervention, intervention.contains("User confirmation required"))
    assertTrue(blockedWhileIntervening, blockedWhileIntervening.contains("start or resume a desktop task"))
    assertTrue(resumed, resumed.contains("\"status\": \"active\""))
    assertTrue(completed, completed.contains("\"status\": \"completed\""))
  }

  @Test
  fun workspaceCommandRunReportsDesktopAccessibilityDiagnosis() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-desktop-diagnosis-${System.currentTimeMillis()}")
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder())

    val status = tool.execute(
      WorkspaceCommandRunTool.Args(argv = listOf("android", "ui", "status"), snapshotBeforeRun = false),
    )

    assertTrue(status, status.contains("\"diagnosis\""))
    assertTrue(status, status.contains("\"accessibilityPermission\""))
    assertTrue(status, status.contains("\"recommendation\""))
  }

  @Ignore("am instrument stops or suppresses the target app AccessibilityService; use DesktopAutomationDebugReceiver for real-device verification.")
  @Test
  fun workspaceCommandRunInputsAndClicksAcrossAppBoundary() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.startActivity(
      Intent().apply {
        component = ComponentName("com.flovera.app.test", "com.flovera.app.DesktopAutomationFixtureActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      },
    )
    Thread.sleep(1_000)
    val workspace = WorkspaceManager(context, "workspace-command-desktop-input-${System.currentTimeMillis()}")
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder())
    tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "ui", "task", "start", "--goal", "Fill and submit another app"),
        snapshotBeforeRun = false,
      ),
    )
    val available = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf(
          "android", "ui", "wait",
          "--package", "com.flovera.app.test",
          "--timeout-ms", "10000",
        ),
        snapshotBeforeRun = false,
      ),
    )
    assertTrue(available, available.contains("\"matched\": true"))

    val filteredInspection = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "ui", "inspect", "--filter-description", "Desktop input", "--max-nodes", "50"),
        snapshotBeforeRun = false,
      ),
    )
    val input = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf(
          "android", "ui", "set-text",
          "--description", "Desktop input",
          "--value", "hello from Flovera",
          "--action-id", "fixture-input",
          "--expect-text", "hello from Flovera",
        ),
        snapshotBeforeRun = false,
      ),
    )
    assertTrue(filteredInspection, filteredInspection.contains("\"filterDescription\": \"Desktop input\""))
    assertTrue(filteredInspection, filteredInspection.contains("\"editable\": true"))
    assertTrue(input, input.contains("\"matched\": true"))
    assertTrue(input, input.contains("\"feedback\""))

    val ocrInspection = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf(
          "android", "ui", "inspect",
          "--with-ocr",
          "--filter-ocr-text", "OCR TARGET",
          "--max-nodes", "80",
        ),
        snapshotBeforeRun = false,
        timeoutMs = 20_000,
      ),
    )
    val ocrClicked = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf(
          "android", "ui", "click",
          "--ocr-text", "OCR TARGET",
          "--action-id", "fixture-ocr-target",
          "--expect-text", "OCR target clicked",
          "--verify-timeout-ms", "15000",
        ),
        snapshotBeforeRun = false,
        timeoutMs = 30_000,
      ),
    )
    assertTrue(ocrInspection, ocrInspection.contains("\"withOcr\": true"))
    assertTrue(ocrInspection, ocrInspection.contains("\"ocrTextMatched\": true"))
    assertTrue(ocrClicked, ocrClicked.contains("\"matched\": true"))
    assertTrue(ocrClicked, ocrClicked.contains("\"strategy\": \"ocr_"))

    val submitted = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf(
          "android", "ui", "click",
          "--text", "Submit",
          "--action-id", "fixture-submit",
          "--expect-text", "Submitted: hello from Flovera",
        ),
        snapshotBeforeRun = false,
      ),
    )
    val lowerTarget = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf(
          "android", "ui", "swipe",
          "--from-x", "540",
          "--from-y", "1900",
          "--to-x", "540",
          "--to-y", "650",
          "--until-text", "Lower target",
          "--max-swipes", "6",
          "--action-id", "fixture-scroll-lower-target",
        ),
        snapshotBeforeRun = false,
      ),
    )
    val completed = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("android", "ui", "task", "complete", "--summary", "Cross-app form submitted"),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(submitted, submitted.contains("\"matched\": true"))
    assertTrue(submitted, submitted.contains("\"feedback\""))
    assertTrue(lowerTarget, lowerTarget.contains("\"matched\": true"))
    assertTrue(lowerTarget, lowerTarget.contains("\"swipes\""))
    assertTrue(lowerTarget, lowerTarget.contains("\"feedback\""))
    assertTrue(completed, completed.contains("\"status\": \"completed\""))
    assertTrue(completed, completed.contains("\"durationMs\": 8000"))
  }

  @Test
  fun mlKitOcrEngineRecognizesGeneratedBitmap() {
    val bitmap = Bitmap.createBitmap(900, 240, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      textSize = 72f
      isFakeBoldText = true
    }
    canvas.drawText("OCR TARGET", 36f, 140f, paint)

    val result = try {
      MlKitOcrEngine.recognize(InstrumentationRegistry.getInstrumentation().targetContext, bitmap, 15_000L)
    } finally {
      bitmap.recycle()
    }

    assertTrue(result.toString(), result.optString("engine").contains("mlkit"))
    assertTrue(result.toString(), result.optJSONArray("blocks").toString().contains("OCR"))
  }

  @Test
  fun workspaceCommandRunRejectsUnsupportedCommands() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-unsupported-${System.currentTimeMillis()}")
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder())

    val result = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("sh", "-c", "echo unsafe"),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Workspace command status=unsupported exitCode=127"))
    assertTrue(result, result.contains("authorization=denied authority=safe risk=unsupported"))
    assertTrue(result, result.contains("Unsupported workspace command: sh"))
    assertFalse(result, result.contains("stdout:"))
  }

  @Test
  fun workspaceCommandRunIsDefaultPythonExecutionTool() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-tools-${System.currentTimeMillis()}")

    val defaultToolNames = workspaceToolRegistry(workspace, ToolEventRecorder())
      .tools
      .map { it.descriptor.name }
    val fallbackToolNames = workspaceToolRegistry(
      workspace = workspace,
      recorder = ToolEventRecorder(),
      pythonRunToolFallbackEnabled = true,
    ).tools.map { it.descriptor.name }

    assertTrue(defaultToolNames.contains("workspace_command_run"))
    assertFalse(defaultToolNames.contains("python_run"))
    assertTrue(fallbackToolNames.contains("workspace_command_run"))
    assertTrue(fallbackToolNames.contains("python_run"))
  }

  @Test
  fun workspaceCommandRunDeniesGroovyOutsideFullAuthority() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-groovy-denied-${System.currentTimeMillis()}")
    workspace.writeFile("tools/hello.groovy", "out.println('should-not-run')", createAutoSnapshot = false)
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder())

    val result = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("groovy", "tools/hello.groovy"),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("authorization=denied authority=safe risk=groovy.workspace_script"))
    assertTrue(result, result.contains("jvm.dynamic requires Full Authority"))
    assertFalse(result, result.contains("should-not-run"))
  }

  @Test
  fun workspaceCommandRunExecutesGroovySpikeInFullAuthority() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-groovy-${System.currentTimeMillis()}")
    workspace.writeFile(
      "tools/hello.groovy",
      """
      out.println("groovy-args=" + argv.join(","))
      ctx.writeText("out/groovy.txt", "groovy ok " + argv[0])
      return "groovy-return"
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder(), authorityMode = "full")

    val result = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("groovy", "tools/hello.groovy", "alpha"),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("authorization=allowed authority=full risk=groovy.workspace_script"))
    assertTrue(result, result.contains("[jvm-worker] process=com.flovera.app:jvmworker"))
    assertTrue(result, result.contains("[jvm-build] jvm.queue.acquired"))
    assertTrue(result, result.contains("groovy-args=alpha"))
    assertTrue(result, result.contains("groovy-return"))
    assertTrue(workspace.readFile("out/groovy.txt").contains("groovy ok alpha"))
    assertTrue(File(workspace.root, ".flovera/logs/jvm-build.jsonl").readText().contains("groovy.compile"))
    assertTrue(File(workspace.root, ".flovera/runtime/jvm-artifacts/build-state.json").readText().contains("\"status\":\"done\""))
  }

  @Test
  fun workspaceCommandRunGroovyBuildCanBeCancelledByFlag() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-groovy-cancel-${System.currentTimeMillis()}")
    workspace.writeFile(
      "tools/cancelled.groovy",
      "out.println('should-not-run')",
      createAutoSnapshot = false,
    )
    File(workspace.root, ".flovera/runtime/jvm-artifacts/cancel.flag").apply {
      parentFile?.mkdirs()
      writeText("cancel")
    }
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder(), authorityMode = "full")

    val result = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("groovy", "tools/cancelled.groovy"),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Workspace command status=error exitCode=1"))
    assertTrue(result, result.contains("failureCategory=jvm_build_cancelled"))
    assertFalse(result, result.contains("should-not-run"))
    assertFalse(File(workspace.root, ".flovera/runtime/jvm-artifacts/cancel.flag").exists())

    val retryResult = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("groovy", "tools/cancelled.groovy"),
        snapshotBeforeRun = false,
      ),
    )
    assertTrue(retryResult, retryResult.contains("Workspace command status=ok exitCode=0"))
    assertTrue(retryResult, retryResult.contains("should-not-run"))
  }

  @Test
  fun workspaceCommandRunGroovyRelativeFileIoUsesWorkspaceRoot() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-groovy-file-${System.currentTimeMillis()}")
    workspace.writeFile(
      "tools/write_relative.groovy",
      """
      new File("out").mkdirs()
      new File("out/groovy-relative.txt").withWriter("UTF-8") { writer ->
          writer.write("relative-ok")
      }
      out.println(new File("out/groovy-relative.txt").text)
      return "file-io-ok"
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder(), authorityMode = "full")

    val result = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("groovy", "tools/write_relative.groovy"),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Workspace command status=ok exitCode=0"))
    assertTrue(result, result.contains("relative-ok"))
    assertTrue(result, result.contains("file-io-ok"))
    assertTrue(workspace.readFile("out/groovy-relative.txt").contains("relative-ok"))
  }

  @Test
  fun workspaceCommandRunLoadsWorkspaceJarFromGroovy() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-groovy-jar-${System.currentTimeMillis()}")
    val libsDir = File(workspace.root, "libs")
    libsDir.mkdirs()
    File(libsDir, "helper.jar").writeBytes(Base64.getDecoder().decode(TEST_HELPER_JAR_BASE64))
    workspace.writeFile(
      "tools/use_helper.groovy",
      """
      import demo.Helper

      out.println(Helper.shout(argv[0]))
      ctx.writeText("out/jar.txt", Helper.shout("file"))
      return "jar-runtime-ok"
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder(), authorityMode = "full")

    val result = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("groovy", "tools/use_helper.groovy", "alpha"),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Workspace command status=ok exitCode=0"))
    assertTrue(result, result.contains("jar-ALPHA"))
    assertTrue(result, result.contains("jar-runtime-ok"))
    assertTrue(workspace.readFile("out/jar.txt").contains("jar-FILE"))
    assertTrue(File(workspace.root, ".flovera/runtime/jvm-artifacts/libs").walkTopDown().any { it.name == "artifact.dex.jar" })
    assertTrue(File(workspace.root, ".flovera/logs/jvm-build.jsonl").readText().contains("d8.library.jar"))

    val cachedResult = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("groovy", "tools/use_helper.groovy", "beta"),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(cachedResult, cachedResult.contains("Workspace command status=ok exitCode=0"))
    assertTrue(cachedResult, cachedResult.contains("jar-BETA"))
    assertTrue(cachedResult, cachedResult.contains("d8.library.jar.cache_hit"))
    assertTrue(cachedResult, cachedResult.contains("groovy.script.cache_hit"))
  }

  @Test
  fun workspaceCommandRunPreservesJarResourcesForGroovy() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-groovy-jar-resource-${System.currentTimeMillis()}")
    val libsDir = File(workspace.root, "libs")
    libsDir.mkdirs()
    writeTestHelperJarWithResource(
      target = File(libsDir, "helper-resource.jar"),
      resourcePath = "demo/template.txt",
      resourceText = "resource-ok",
    )
    workspace.writeFile(
      "tools/use_helper_resource.groovy",
      """
      import demo.Helper

      def stream = Helper.class.classLoader.getResourceAsStream("demo/template.txt")
      if (stream == null) {
        throw new IllegalStateException("resource missing")
      }
      def text = stream.getText("UTF-8")
      out.println(Helper.shout(text))
      return text
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder(), authorityMode = "full")

    val result = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("groovy", "tools/use_helper_resource.groovy"),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Workspace command status=ok exitCode=0"))
    assertTrue(result, result.contains("jar-RESOURCE-OK"))
    val dexJar = File(workspace.root, ".flovera/runtime/jvm-artifacts/libs").walkTopDown()
      .firstOrNull { it.name == "artifact.dex.jar" }
    assertNotNull(dexJar)
    JarFile(dexJar).use { jar ->
      assertNotNull(jar.getEntry("classes.dex"))
      assertNotNull(jar.getEntry("demo/template.txt"))
    }
  }

  @Test
  fun workspaceCommandRunResolvesMavenJarForGroovy() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-groovy-maven-${System.currentTimeMillis()}")
    val repoDir = File(workspace.root, "test-maven-repo")
    val artifactDir = File(repoDir, "demo/helper/1.0")
    artifactDir.mkdirs()
    File(artifactDir, "helper-1.0.jar").writeBytes(Base64.getDecoder().decode(TEST_HELPER_JAR_BASE64))
    File(artifactDir, "helper-1.0.pom").writeText(
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>demo</groupId>
        <artifactId>helper</artifactId>
        <version>1.0</version>
      </project>
      """.trimIndent(),
      StandardCharsets.UTF_8,
    )
    workspace.writeFile(
      "libs/maven.json",
      """
      {
        "repositories": ["${repoDir.toURI().toString().trimEnd('/')}"],
        "dependencies": ["demo:helper:1.0"]
      }
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    workspace.writeFile(
      "tools/use_maven_helper.groovy",
      """
      import demo.Helper

      out.println(Helper.shout(argv[0]))
      return "maven-runtime-ok"
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder(), authorityMode = "full")

    val result = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("groovy", "tools/use_maven_helper.groovy", "alpha"),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Workspace command status=ok exitCode=0"))
    assertTrue(result, result.contains("jar-ALPHA"))
    assertTrue(result, result.contains("maven-runtime-ok"))
    assertTrue(File(workspace.root, ".flovera/runtime/jvm-artifacts/maven/repository/demo/helper/1.0/helper-1.0.jar").isFile)
  }

  @Test
  fun workspaceCommandRunCanOverrideMavenConfigForOneGroovyRun() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "workspace-command-groovy-maven-override-${System.currentTimeMillis()}")
    val repoDir = File(workspace.root, "test-maven-repo")
    val artifactDir = File(repoDir, "demo/helper/1.0")
    artifactDir.mkdirs()
    File(artifactDir, "helper-1.0.jar").writeBytes(Base64.getDecoder().decode(TEST_HELPER_JAR_BASE64))
    File(artifactDir, "helper-1.0.pom").writeText(
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>demo</groupId>
        <artifactId>helper</artifactId>
        <version>1.0</version>
      </project>
      """.trimIndent(),
      StandardCharsets.UTF_8,
    )
    workspace.writeFile(
      "libs/maven.json",
      """
      {
        "dependencies": ["demo:missing:1.0"]
      }
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    workspace.writeFile(
      ".flovera/jvm/test-maven.json",
      """
      {
        "repositories": ["${repoDir.toURI().toString().trimEnd('/')}"],
        "dependencies": ["demo:helper:1.0"]
      }
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    workspace.writeFile(
      "tools/use_maven_override.groovy",
      """
      import demo.Helper

      out.println(Helper.shout("override"))
      return "maven-override-ok"
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder(), authorityMode = "full")

    val result = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("groovy", "tools/use_maven_override.groovy"),
        snapshotBeforeRun = false,
        environment = mapOf("FLOVERA_JVM_MAVEN_CONFIG" to ".flovera/jvm/test-maven.json"),
      ),
    )

    assertTrue(result, result.contains("Workspace command status=ok exitCode=0"))
    assertTrue(result, result.contains("jar-OVERRIDE"))
    assertTrue(result, result.contains("maven-override-ok"))
    assertFalse(result, result.contains("demo:missing:1.0"))
  }

  @Test
  fun pythonRunSupportsXmlAndDocxDocumentProcessing() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "python-docs-${System.currentTimeMillis()}")
    val tool = PythonRunTool(workspace, ToolEventRecorder())

    val result = tool.execute(
      PythonRunTool.Args(
        code = """
        import os
        from docx import Document
        from lxml import etree

        os.makedirs("docs", exist_ok=True)

        root = etree.Element("report")
        etree.SubElement(root, "title").text = "Flovera Python Document"
        etree.SubElement(root, "status").text = "ready"
        with open("docs/report.xml", "w", encoding="utf-8") as handle:
            handle.write(etree.tostring(root, encoding="unicode", pretty_print=True))

        document = Document()
        document.add_heading("Flovera Python Document", level=1)
        document.add_paragraph("lxml and python-docx are available.")
        table = document.add_table(rows=2, cols=2)
        table.cell(0, 0).text = "Capability"
        table.cell(0, 1).text = "Status"
        table.cell(1, 0).text = "document-processing"
        table.cell(1, 1).text = "ready"
        document.save("docs/report.docx")

        loaded = Document("docs/report.docx")
        parsed = etree.parse("docs/report.xml")
        print(loaded.paragraphs[0].text)
        print(parsed.getroot().findtext("status"))
        """.trimIndent(),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Python status=ok exitCode=0"))
    assertTrue(result, result.contains("Flovera Python Document"))
    assertTrue(result, result.contains("ready"))
    assertTrue(workspace.readFile("docs/report.xml").contains("<status>ready</status>"))
    assertTrue(workspace.exportableFile("docs/report.docx")!!.length() > 0)
  }

  @Test
  fun pythonRunSupportsProductionPackagesForOfficePdfAndHtml() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "python-production-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val tool = PythonRunTool(workspace, ToolEventRecorder())

    val result = tool.execute(
      PythonRunTool.Args(
        code = """
        import json
        import markdown
        import openpyxl
        import xlsxwriter
        from jinja2 import Template
        from pypdf import PdfWriter

        workbook = openpyxl.Workbook()
        sheet = workbook.active
        sheet.title = "Data"
        sheet["A1"] = "value"
        sheet["A2"] = 42
        workbook.save("production-openpyxl.xlsx")

        xlsx = xlsxwriter.Workbook("production-xlsxwriter.xlsx")
        worksheet = xlsx.add_worksheet("Report")
        worksheet.write("A1", "metric")
        worksheet.write("B1", "score")
        xlsx.close()

        html = Template("<h1>{{ title }}</h1>{{ body }}").render(
            title="Generated",
            body=markdown.markdown("**ready**"),
        )
        open("production.html", "w", encoding="utf-8").write(html)
        open("production.json", "w", encoding="utf-8").write(json.dumps({"ready": True}))

        writer = PdfWriter()
        writer.add_blank_page(width=72, height=72)
        with open("production.pdf", "wb") as handle:
            writer.write(handle)

        print("production packages ready")
        """.trimIndent(),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Python status=ok exitCode=0"))
    assertTrue(result, result.contains("production packages ready"))
    assertTrue(workspace.exportableFile("production-openpyxl.xlsx")!!.length() > 0)
    assertTrue(workspace.exportableFile("production-xlsxwriter.xlsx")!!.length() > 0)
    assertTrue(workspace.readFile("production.html").contains("<strong>ready</strong>"))
    assertTrue(workspace.exportableFile("production.pdf")!!.length() > 0)
  }

  @Test
  fun pythonRunAllowsChaquopyStdlibReadsForNetworkAndAsyncImports() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "python-stdlib-${System.currentTimeMillis()}")
    val tool = PythonRunTool(workspace, ToolEventRecorder())

    val result = tool.execute(
      PythonRunTool.Args(
        code = """
        import asyncio
        import ssl
        import urllib.request

        print("stdlib imports ready")
        print(asyncio.__name__, ssl.__name__, urllib.request.__name__)
        """.trimIndent(),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Python status=ok exitCode=0"))
    assertTrue(result, result.contains("stdlib imports ready"))
  }

  @Test
  fun artifactInspectValidatesCommonWorkspaceArtifacts() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "artifact-inspect-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val python = PythonRunTool(workspace, ToolEventRecorder())
    val inspector = ArtifactInspectTool(workspace, ToolEventRecorder())

    val createResult = python.execute(
      PythonRunTool.Args(
        code = """
        import json
        import openpyxl
        from docx import Document
        from pypdf import PdfWriter

        open("artifact.html", "w", encoding="utf-8").write("<!doctype html><title>Artifact</title><h1>Ready</h1>")
        open("artifact.json", "w", encoding="utf-8").write(json.dumps({"status": "ready", "items": [1, 2]}))

        doc = Document()
        doc.add_heading("Artifact Document", level=1)
        doc.add_paragraph("Generated for inspection.")
        doc.save("artifact.docx")

        workbook = openpyxl.Workbook()
        sheet = workbook.active
        sheet.title = "Summary"
        sheet["A1"] = "status"
        sheet["B1"] = "=1+1"
        workbook.save("artifact.xlsx")

        writer = PdfWriter()
        writer.add_blank_page(width=72, height=72)
        with open("artifact.pdf", "wb") as handle:
            writer.write(handle)
        """.trimIndent(),
        snapshotBeforeRun = false,
      ),
    )
    assertTrue(createResult, createResult.contains("Python status=ok exitCode=0"))

    val json = inspector.execute(ArtifactInspectTool.Args(path = "artifact.json"))
    val html = inspector.execute(ArtifactInspectTool.Args(path = "artifact.html"))
    val docx = inspector.execute(ArtifactInspectTool.Args(path = "artifact.docx"))
    val xlsx = inspector.execute(ArtifactInspectTool.Args(path = "artifact.xlsx"))
    val pdf = inspector.execute(ArtifactInspectTool.Args(path = "artifact.pdf"))

    assertTrue(json, json.contains("\"format\":\"json\""))
    assertTrue(json, json.contains("\"status\":\"ok\""))
    assertTrue(html, html.contains("\"format\":\"html\""))
    assertTrue(html, html.contains("Artifact"))
    assertTrue(docx, docx.contains("\"format\":\"docx\""))
    assertTrue(docx, docx.contains("Artifact Document"))
    assertTrue(xlsx, xlsx.contains("\"format\":\"xlsx\""))
    assertTrue(xlsx, xlsx.contains("\"formulaCount\":1"))
    assertTrue(pdf, pdf.contains("\"format\":\"pdf\""))
    assertTrue(pdf, pdf.contains("\"pageCount\":1"))
  }

  @Test
  fun pythonPackageCatalogAndToolManifestAreExposed() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "python-catalog-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val installer = PythonPackageInstallTool(workspace, ToolEventRecorder())

    val installResult = installer.execute(PythonPackageInstallTool.Args(packageName = "openpyxl"))

    assertTrue(installResult, installResult.contains("\"status\":\"ok\""))
    assertTrue(installResult, installResult.contains("Package already available"))
    assertTrue(workspace.readFile(".flovera/python/wheel-catalog.json").contains("\"name\": \"openpyxl\""))
    assertTrue(workspace.readFile(".flovera/tools/manifest.json").contains("\"tools\""))
  }

  @Test
  fun pythonRunKeepsConversationSessionState() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "python-session-${System.currentTimeMillis()}")
    val tool = PythonRunTool(workspace, ToolEventRecorder())

    tool.execute(
      PythonRunTool.Args(code = "value = 41", sessionId = "conversation-1", snapshotBeforeRun = false),
    )
    val result = tool.execute(
      PythonRunTool.Args(code = "print(value + 1)", sessionId = "conversation-1", snapshotBeforeRun = false),
    )

    assertTrue(result, result.contains("session=conversation-1"))
    assertTrue(result, result.contains("42"))
  }

  @Test
  fun pythonRunRejectsEscapesAndBackgroundEntrypoints() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "python-boundary-${System.currentTimeMillis()}")
    val tool = PythonRunTool(workspace, ToolEventRecorder())

    val escape = tool.execute(
      PythonRunTool.Args(
        code = """open("/data/data/com.flovera.app/files/not-workspace.txt", "w").write("bad")""",
        snapshotBeforeRun = false,
      ),
    )
    val thread = tool.execute(
      PythonRunTool.Args(
        code = """
        import threading
        threading.Thread(target=lambda: None).start()
        """.trimIndent(),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(escape, escape.contains("Path escapes workspace"))
    assertTrue(thread, thread.contains("threading.Thread.start is disabled"))
  }

  @Test
  fun pythonRunTimesOutBlockingSleep() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "python-timeout-${System.currentTimeMillis()}")
    val tool = PythonRunTool(workspace, ToolEventRecorder())

    val result = tool.execute(
      PythonRunTool.Args(
        code = """
        import time
        time.sleep(5)
        print("unreachable")
        """.trimIndent(),
        timeoutMs = 1000,
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Python status=timeout exitCode=124"))
    assertFalse(result, result.contains("unreachable"))
  }

  @Test
  fun workspaceImportsSharedFilesToRootWithUniqueNames() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "shared-import-${System.currentTimeMillis()}")
    val shared = File(context.cacheDir, "shared-note.txt").apply { writeText("from another app") }

    val first = workspace.importUriToRoot(Uri.fromFile(shared))
    val second = workspace.importUriToRoot(Uri.fromFile(shared))

    assertEquals("Imported shared-note.txt", first)
    assertEquals("Imported shared-note (1).txt", second)
    assertEquals("from another app", workspace.readFile("shared-note.txt"))
    assertEquals("from another app", workspace.readFile("shared-note (1).txt"))
  }

  @Test
  fun workspaceSnapshotsRestoreFilesAndMetadata() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "snapshot-restore-${System.currentTimeMillis()}").also {
      it.ensureSeedFiles()
      it.ensureFloveraMetadata(FloveraSettingsView(provider = "deepseek", model = "deepseek-v4-pro", apiKeyRef = "deepseek.default"))
    }

    workspace.writeFile("notes/today.md", "alpha")
    val snapshot = workspace.createManualSnapshot("baseline", selectedHtmlPath = "index.html")
    workspace.writeFile("notes/today.md", "beta")
    workspace.writeFile(".flovera/settings-view.json", """{"provider":"changed"}""")

    val restored = workspace.restoreSnapshot(snapshot.id)

    assertEquals(snapshot.id, restored?.id)
    assertEquals("alpha", workspace.readFile("notes/today.md"))
    assertTrue(workspace.readFile(".flovera/settings-view.json").contains("deepseek-v4-pro"))
    assertTrue(workspace.deleteSnapshot(snapshot.id))
  }

  @Test
  fun manualSnapshotAfterRestoreCountsCurrentWorkspaceFiles() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "snapshot-count-${System.currentTimeMillis()}").also {
      it.ensureSeedFiles()
      it.writeFile("only-in-baseline.txt", "baseline")
    }
    val baseline = workspace.createManualSnapshot("baseline", selectedHtmlPath = "index.html")
    workspace.writeFile("new-a.txt", "a")
    workspace.writeFile("new-b.txt", "b")

    workspace.restoreSnapshot(baseline.id)
    val expectedFileCount = workspace.root.walkTopDown().count { it.isFile }
    val afterRestore = workspace.createManualSnapshot("after restore", selectedHtmlPath = "index.html")

    assertEquals(expectedFileCount, afterRestore.fileCount)
    assertEquals(baseline.fileCount, afterRestore.fileCount)
    assertEquals("File does not exist: new-a.txt", workspace.readFile("new-a.txt"))
    assertEquals("baseline", workspace.readFile("only-in-baseline.txt"))
  }

  private class FakeOpenAiCompatibleSseServer : Closeable {
    private val server = ServerSocket(0)
    private val requestLatch = CountDownLatch(1)
    private val auth = AtomicReference("")
    private val body = AtomicReference("")
    private val worker = thread(start = true, isDaemon = true, name = "FakeOpenAiCompatibleSseServer") {
      runCatching {
        server.accept().use { socket ->
          val input = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
          input.readLine()
          var contentLength = 0
          while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
              val name = line.substring(0, separator).trim()
              val value = line.substring(separator + 1).trim()
              if (name.equals("Authorization", ignoreCase = true)) auth.set(value)
              if (name.equals("Content-Length", ignoreCase = true)) contentLength = value.toIntOrNull() ?: 0
            }
          }
          if (contentLength > 0) {
            val chars = CharArray(contentLength)
            input.read(chars)
            body.set(String(chars))
          }
          val responseBody = (
            "data: {\"choices\":[{\"delta\":{\"content\":\"stub-ok\"}}]}\r\n\r\n" +
              "data: [DONE]\r\n\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
          val headers = (
            "HTTP/1.1 200 OK\r\n" +
              "Content-Type: text/event-stream; charset=utf-8\r\n" +
              "Content-Length: ${responseBody.size}\r\n" +
              "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
          socket.getOutputStream().use { output ->
            output.write(headers)
            output.write(responseBody)
            output.flush()
          }
          requestLatch.countDown()
        }
      }
    }

    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    fun authorization(): String {
      assertTrue("Fake upstream was not called", requestLatch.await(10, TimeUnit.SECONDS))
      return auth.get()
    }

    fun requestBody(): String {
      assertTrue("Fake upstream was not called", requestLatch.await(10, TimeUnit.SECONDS))
      return body.get()
    }

    override fun close() {
      server.close()
      worker.join(500)
    }
  }

  @Test
  fun workspaceFloveraMetadataExposesCapabilitiesAndSettingsProposals() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "metadata-${System.currentTimeMillis()}"
    val controller = WorkspaceController(context, workspaceId).also {
      it.ensureSeedFiles()
      it.syncFloveraSettings(
        AppSettings(
          activeWorkspaceId = workspaceId,
          networkEnabled = true,
          webSearchEnabled = true,
          backgroundKeepAliveEnabled = true,
          agentAuthorityMode = "assisted",
          workspaceSecrets = listOf(
            WorkspaceSecret(
              name = "FLOVERA_SECRET_1",
              label = "Amap",
              description = "legacy description should not be exposed",
              value = "secret-metadata-value-1234",
              agentAllowed = true,
            ),
          ),
        ),
      )
    }
    val workspace = controller.runtimeWorkspace()

    workspace.writeFile(
      ".flovera/proposals/theme.json",
      """
      {
        "type": "settings",
        "title": "Use softer theme",
        "reason": "Match current workspace page",
        "changes": {
          "themeColor": "#C989B8",
          "selectedHtmlPath": "index.html"
        }
      }
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    workspace.writeFile(
      ".flovera/proposals/search-tool.json",
      """
      {
        "type": "tool",
        "title": "Add workspace search",
        "reason": "Find files without scanning manually",
        "name": "workspace_search",
        "description": "Search workspace file contents",
        "requestedCapabilities": ["filesystem"],
        "permissions": ["read workspace"]
      }
      """.trimIndent(),
      createAutoSnapshot = false,
    )

    val capabilities = workspace.readFile(".flovera/capabilities.json")
    val settingsView = workspace.readFile(".flovera/settings-view.json")
    val proposals = workspace.listSettingsProposals()
    val toolProposals = workspace.listControlledToolProposals()

    assertTrue(capabilities.contains("\"networkTools\": true"))
    assertTrue(capabilities.contains("\"pythonRuntime\": true"))
    assertTrue(capabilities.contains("\"pythonRunTool\": false"))
    assertTrue(capabilities.contains("\"pythonRunToolFallbackEnabled\": false"))
    assertTrue(settingsView.contains("\"pythonRunToolFallbackEnabled\": false"))
    assertTrue(capabilities.contains("\"workspaceCommandRuntime\": true"))
    assertTrue(capabilities.contains("\"workspaceCommandRuntimeKind\": \"argv\""))
    assertTrue(capabilities.contains("\"workspaceCommandSupportedCommands\""))
    assertTrue(capabilities.contains("\"python3\""))
    assertTrue(capabilities.contains("\"groovy\""))
    assertTrue(capabilities.contains("\"git\""))
    assertTrue(capabilities.contains("\"android\""))
    assertTrue(capabilities.contains("\"flovera\""))
    assertTrue(capabilities.contains("\"workspaceCommandProfiles\""))
    assertTrue(capabilities.contains("\"flovera-script\""))
    assertTrue(capabilities.contains("\"workspaceAutomationScripts\": true"))
    assertTrue(capabilities.contains("\"workspaceAutomationScriptPath\": \".flovera/scripts\""))
    assertTrue(capabilities.contains("\"workspaceAutomationScriptRunner\": \"flovera script run\""))
    assertTrue(capabilities.contains("\"flovera script list\""))
    assertTrue(capabilities.contains("\"officeOoxmlRuntime\": true"))
    assertTrue(capabilities.contains("\"officeOoxmlSupportedCommands\""))
    assertTrue(capabilities.contains("\"flovera office replace <path> --find <text> --replace <text> [--output <path>] [--backend auto|light|poi]\""))
    assertTrue(capabilities.contains("\"officeOoxmlComplexEditingStatus\": \"available_as_structural_backend_not_full_office_renderer\""))
    assertTrue(capabilities.contains("\"gitCommandRuntime\": true"))
    assertTrue(capabilities.contains("\"gitCommandRuntimeMode\": \"embedded_jgit_local_workspace\""))
    assertTrue(capabilities.contains("\"gitCommandSupportedSubcommands\""))
    assertTrue(capabilities.contains("\"commit\""))
    assertTrue(capabilities.contains("\"gitCommandRemoteOperations\": false"))
    assertTrue(capabilities.contains("\"androidCommandRuntime\": true"))
    assertTrue(capabilities.contains("\"androidCommandRuntimeMode\": \"app_owned_permission_gated_system_apis\""))
    assertTrue(capabilities.contains("\"android app info|list|resolve\""))
    assertTrue(capabilities.contains("\"androidSystemApiProfiles\""))
    assertTrue(capabilities.contains("\"notification\""))
    assertTrue(capabilities.contains("\"camera\""))
    assertTrue(capabilities.contains("\"microphone\""))
    assertTrue(capabilities.contains("\"contacts\""))
    assertTrue(capabilities.contains("\"calendar\""))
    assertTrue(capabilities.contains("\"media\""))
    assertTrue(capabilities.contains("\"bluetooth\""))
    assertTrue(capabilities.contains("\"storage\""))
    assertTrue(capabilities.contains("\"package\""))
    assertTrue(capabilities.contains("\"alarm\""))
    assertTrue(capabilities.contains("\"foreground\""))
    assertTrue(capabilities.contains("\"androidPermissionChecksBeforeAction\": true"))
    assertTrue(capabilities.contains("\"androidBinaryOutputsToWorkspace\": true"))
    assertTrue(capabilities.contains("\"androidPermissionConsole\": true"))
    assertTrue(capabilities.contains("\"androidPermissionGrantEntry\": \"main_menu_permissions_panel_grant_all\""))
    assertTrue(capabilities.contains("\"androidRuntimePermissionBatchRequest\": true"))
    assertTrue(capabilities.contains("\"androidSpecialPermissionSequentialFlow\": true"))
    assertTrue(capabilities.contains("\"androidDesktopOperation\": true"))
    assertTrue(capabilities.contains("\"androidDesktopOperationMode\": \"accessibility_semantic_first\""))
    assertTrue(capabilities.contains("\"androidDesktopTaskPersistence\": true"))
    assertTrue(capabilities.contains("\"androidDesktopActionIdempotency\": true"))
    assertTrue(capabilities.contains("\"androidDesktopPostActionVerification\": true"))
    assertTrue(capabilities.contains("\"androidDesktopRuntimeFeedback\": true"))
    assertTrue(capabilities.contains("\"androidDesktopCompletionStrongFeedback\": true"))
    assertTrue(capabilities.contains("\"androidDesktopOcrObservation\": true"))
    assertTrue(capabilities.contains("\"androidDesktopOcrEngine\": \"mlkit_text_recognition_chinese\""))
    assertTrue(capabilities.contains("\"androidDesktopOcrSemanticFusion\": true"))
    assertTrue(capabilities.contains("\"androidDesktopOcrTextSelector\": true"))
    assertTrue(capabilities.contains("\"androidDesktopOcrPostActionVerification\": true"))
    assertTrue(capabilities.contains("\"androidDesktopSemanticClick\": true"))
    assertTrue(capabilities.contains("\"androidDesktopSwipeUntilText\": true"))
    assertTrue(capabilities.contains("\"androidDesktopSwipeCoordinateAliases\": true"))
    assertTrue(capabilities.contains("\"androidAppIndex\": true"))
    assertTrue(capabilities.contains("\"androidDirectAppLaunch\": false"))
    assertTrue(capabilities.contains("\"androidDesktopClickVerificationFallback\": true"))
    assertTrue(capabilities.contains("\"androidDesktopSetTextFocusRetry\": true"))
    assertTrue(capabilities.contains("\"androidDesktopSetTextDismissKeyboardAfter\": true"))
    assertTrue(capabilities.contains("\"androidDesktopFailureDiagnosisPath\": \".flovera/logs/ui-diagnosis\""))
    assertTrue(capabilities.contains("\"androidDesktopInspectFilters\": true"))
    assertTrue(capabilities.contains("\"androidDesktopAccessibilityDiagnosis\": true"))
    assertTrue(capabilities.contains("\"androidDesktopScreenshotVisionInput\": false"))
    assertTrue(capabilities.contains("\"androidPermissionIds\""))
    assertTrue(capabilities.contains("\"overlay\""))
    assertTrue(capabilities.contains("\"accessibility\""))
    assertTrue(capabilities.contains("\"groovyCommandRuntime\": true"))
    assertTrue(capabilities.contains("\"groovyCommandRuntimeStatus\": \"experimental_full_authority\""))
    assertTrue(capabilities.contains("\"groovyWorkspaceJarClasspath\": true"))
    assertTrue(capabilities.contains("\"jvmWorkspaceLibraries\": true"))
    assertTrue(capabilities.contains("\"jvmWorkspaceLibraryPath\": \"libs\""))
    assertTrue(capabilities.contains("\"jvmArtifactDexCachePath\": \".flovera/runtime/jvm-artifacts\""))
    assertTrue(capabilities.contains("\"jvmArtifactSourceModes\""))
    assertTrue(capabilities.contains("\"workspace_jar\""))
    assertTrue(capabilities.contains("\"maven_coordinate\""))
    assertTrue(capabilities.contains("\"jvmMavenCoordinateResolution\": true"))
    assertTrue(capabilities.contains("\"jvmMavenConfigPaths\""))
    assertTrue(capabilities.contains("\"libs/maven.json\""))
    assertTrue(capabilities.contains("\".flovera/jvm/maven.json\""))
    assertTrue(capabilities.contains("\"jvmMavenDefaultRepositories\""))
    assertTrue(capabilities.contains("\"https://repo1.maven.org/maven2\""))
    assertTrue(capabilities.contains("\"jvmMavenTransitiveDependencies\": \"basic_compile_runtime_scope\""))
    assertTrue(capabilities.contains("\"jvmBuildScheduler\": true"))
    assertTrue(capabilities.contains("\"jvmBuildSchedulerMode\": \"serialized_throttled_checkpointed_cache\""))
    assertTrue(capabilities.contains("\"jvmBuildProgressLogPath\": \".flovera/logs/jvm-build.jsonl\""))
    assertTrue(capabilities.contains("\"jvmBuildStatePath\": \".flovera/runtime/jvm-artifacts/build-state.json\""))
    assertTrue(capabilities.contains("\"jvmBuildCancelFlagPath\": \".flovera/runtime/jvm-artifacts/cancel.flag\""))
    assertTrue(capabilities.contains("\"jvmBuildCancelFlagMode\": \"one_shot_consumed\""))
    assertTrue(capabilities.contains("\"jvmBuildErrorClassification\": true"))
    assertTrue(capabilities.contains("\"jvmLibraryDexMode\": \"per_jar_resource_preserving_dex_jar\""))
    assertTrue(capabilities.contains("\"jvmLibraryResourcesPreserved\": true"))
    assertTrue(capabilities.contains("\"jvmMavenConfigOverrideEnv\": \"FLOVERA_JVM_MAVEN_CONFIG\""))
    assertTrue(capabilities.contains("\"jvmWorkerProcess\": true"))
    assertTrue(capabilities.contains("\"jvmWorkerProcessName\": \":jvmworker\""))
    assertTrue(capabilities.contains("\"appCrashLogPath\": \".flovera/logs/app-crash.jsonl\""))
    assertTrue(capabilities.contains("\"androidHistoricalExitLogging\": true"))
    assertTrue(capabilities.contains("\"workspaceCommandShellAccess\": false"))
    assertTrue(capabilities.contains("\"pythonPackageInstall\": true"))
    assertTrue(capabilities.contains("\"pythonPackageCatalogPath\""))
    assertTrue(capabilities.contains("\"pythonBuiltInPackages\""))
    assertTrue(capabilities.contains("\"openpyxl\""))
    assertTrue(capabilities.contains("\"skillSystem\": true"))
    assertTrue(capabilities.contains("\"skillManifestPath\": \".flovera/skills/manifest.json\""))
    assertTrue(capabilities.contains("\"skillBodyPathGlob\": \".flovera/skills/<skill-id>/SKILL.md\""))
    assertTrue(capabilities.contains("\"skillActivationViaReadFile\": true"))
    assertTrue(capabilities.contains("\"skillDescriptorsInPrompt\": true"))
    assertTrue(capabilities.contains("\"skillFilesEditable\": true"))
    assertTrue(capabilities.contains("\"skillRegistrationEditable\": true"))
    assertTrue(capabilities.contains("\"skillConsoleManagement\": true"))
    assertTrue(capabilities.contains("\"skillEnableSwitch\": true"))
    assertTrue(capabilities.contains("\"skillBilingualDescriptions\": true"))
    assertTrue(capabilities.contains("\"skillDisabledStillReadable\": true"))
    assertTrue(capabilities.contains("\"secretManager\": true"))
    assertTrue(capabilities.contains("\"secretRefsInPrompt\": true"))
    assertTrue(capabilities.contains("\"secretValuesInPrompt\": false"))
    assertTrue(capabilities.contains("\"secretWorkspaceCommandEnvironmentInjection\": true"))
    assertTrue(capabilities.contains("\"secretDirectPastePolicy\": \"notify_only_no_masking_no_blocking\""))
    assertTrue(capabilities.contains("\"artifactInspect\": true"))
    assertTrue(capabilities.contains("\"artifactInspectFormats\""))
    assertTrue(capabilities.contains("\"workspaceArtifacts\": true"))
    assertTrue(capabilities.contains("\"workspaceArtifactManifestName\": \"flovera.app.json\""))
    assertTrue(capabilities.contains("\"workspaceArtifactActionKinds\""))
    assertTrue(capabilities.contains("\"python_job\""))
    assertTrue(capabilities.contains("\"workspaceArtifactPythonHttp\": true"))
    assertTrue(capabilities.contains("\"workspaceArtifactWorkspaceOwnedHttp\": true"))
    assertTrue(capabilities.contains("\"workspaceArtifactPythonHttpLifecycle\": true"))
    assertTrue(capabilities.contains("\"workspaceArtifactPythonHttpDiagnostics\": true"))
    assertTrue(capabilities.contains("\"workspaceArtifactBackendFailureIsolation\": true"))
    assertTrue(capabilities.contains("\"workspaceArtifactViewportHelper\": true"))
    assertTrue(capabilities.contains("\"workspaceArtifactViewportCssVars\""))
    assertTrue(capabilities.contains("\"--flovera-viewport-height\""))
    assertTrue(capabilities.contains("\"workspaceArtifactVisibleContentCheck\": true"))
    assertTrue(capabilities.contains("\"workspaceArtifactPythonJobNetwork\": true"))
    assertTrue(capabilities.contains("\"workspaceArtifactEnvironmentRefs\""))
    assertTrue(capabilities.contains("\"workspaceArtifactBridgeCalls\""))
    assertTrue(capabilities.contains("\"runAction\""))
    assertTrue(capabilities.contains("\"workspaceArtifactJobUi\": true"))
    assertTrue(capabilities.contains("\"seededPortableArtifactDemoPath\": \"agent-demo/flovera.app.json\""))
    assertTrue(capabilities.contains("\"toolProgressNarration\": true"))
    assertTrue(capabilities.contains("\"agentRunTimeline\": true"))
    assertTrue(capabilities.contains("\"agentRunEventBus\": true"))
    assertTrue(capabilities.contains("\"finalAssistantResponseStreaming\": true"))
    assertTrue(capabilities.contains("\"modelTextDeltaStreaming\": true"))
    assertTrue(capabilities.contains("\"modelTextDeltaPolicy\": \"optional_model_output_not_required\""))
    assertTrue(capabilities.contains("\"modelTextToolBoundaryFlush\": true"))
    assertTrue(capabilities.contains("\"koog_stream_frame_event_handler\""))
    assertTrue(capabilities.contains("\"finalizedMarkdownSegmentedRendering\": true"))
    assertTrue(capabilities.contains("\"mainSurfaceHtmlQuickPicker\": true"))
    assertTrue(capabilities.contains("\"conversationComposerAttachments\": true"))
    assertTrue(capabilities.contains("\"conversationPhotoLibraryImport\": true"))
    assertTrue(capabilities.contains("\"conversationVoiceInput\": true"))
    assertTrue(capabilities.contains("\"conversationPathLinks\": true"))
    assertTrue(capabilities.contains("\"workspaceSearch\": true"))
    assertTrue(capabilities.contains("\"workspaceSearchScopes\""))
    assertTrue(capabilities.contains("\"workspace_app_metadata\""))
    assertTrue(capabilities.contains("\"webSearch\": true"))
    assertTrue(capabilities.contains("\"foregroundAgentRunService\": true"))
    assertTrue(capabilities.contains("\"backgroundKeepAlive\": true"))
    assertTrue(capabilities.contains("\"backgroundKeepAliveEnabled\": true"))
    assertTrue(capabilities.contains("\"previewFormats\""))
    assertTrue(capabilities.contains("\"json\""))
    assertTrue(capabilities.contains("\"csv\""))
    assertTrue(capabilities.contains("\"code\""))
    assertTrue(capabilities.contains("\"pdf\""))
    assertTrue(capabilities.contains("\"modelContextOverrides\": true"))
    assertTrue(capabilities.contains("\"reasoningEffort\": true"))
    assertTrue(capabilities.contains("\"snapshots\": false"))
    assertTrue(capabilities.contains("\"directSettingsWrite\": false"))
    assertTrue(capabilities.contains("\"supportedAuthorityModes\""))
    assertTrue(capabilities.contains("\"full\""))
    assertTrue(capabilities.contains("\"pendingAuthorityModes\": []"))
    assertTrue(capabilities.contains("\"customOpenAICompatibleProvider\": false"))
    assertTrue(capabilities.contains("\"openRouterRouting\": false"))
    assertTrue(capabilities.contains("\"customUrlRouting\": false"))
    assertTrue(capabilities.contains("\"providerProfiles\": true"))
    assertTrue(capabilities.contains("\"providerProfileCatalog\""))
    assertTrue(capabilities.contains("\"id\": \"deepseek\""))
    assertFalse(capabilities.contains("\"id\": \"alibaba\""))
    assertFalse(capabilities.contains("\"id\": \"openai\""))
    assertFalse(capabilities.contains("\"id\": \"anthropic\""))
    assertFalse(capabilities.contains("\"id\": \"custom-openai\""))
    assertTrue(capabilities.contains("\"aliases\""))
    assertTrue(capabilities.contains("\"suggestedModels\""))
    assertTrue(capabilities.contains("\"modelContexts\""))
    assertTrue(capabilities.contains("\"contextWindowTokens\": 1000000"))
    assertTrue(capabilities.contains("\"source\": \"deepseek_catalog\""))
    assertTrue(capabilities.contains("\"baseUrl\": \"https://api.deepseek.com\""))
    assertTrue(capabilities.contains("\"defaultHeaderNames\""))
    assertTrue(capabilities.contains("\"requestCompatibilityModes\""))
    assertTrue(capabilities.contains("\"requestHooks\""))
    assertTrue(capabilities.contains("\"transport\""))
    assertTrue(capabilities.contains("\"flovera_deepseek_chat_completions\""))
    assertTrue(capabilities.contains("\"omittedRequestFields\""))
    assertTrue(capabilities.contains("\"addedRequestFields\""))
    assertTrue(capabilities.contains("\"responsesPath\""))
    assertTrue(capabilities.contains("\"authType\": \"api_key\""))
    assertTrue(capabilities.contains("\"providerRequestHooks\": false"))
    assertTrue(capabilities.contains("\"customRequestBody\": false"))
    assertTrue(capabilities.contains("\"controlledToolProposals\": true"))
    assertTrue(capabilities.contains("\"controlledMcpProposals\": true"))
    assertTrue(capabilities.contains("\"directToolInstall\": false"))
    assertTrue(capabilities.contains("\"directMcpInstall\": false"))
    assertTrue(settingsView.contains("\"modelContextSource\""))
    assertTrue(settingsView.contains("\"providerApiMode\""))
    assertTrue(settingsView.contains("\"providerTransport\""))
    assertTrue(settingsView.contains("\"flovera_deepseek_chat_completions\""))
    assertTrue(settingsView.contains("\"providerBaseUrl\""))
    assertTrue(settingsView.contains("\"providerModelsUrl\""))
    assertTrue(settingsView.contains("\"providerMessagesPath\""))
    assertTrue(settingsView.contains("\"providerResponsesPath\""))
    assertTrue(settingsView.contains("\"providerModelsPath\""))
    assertTrue(settingsView.contains("\"providerAuthType\""))
    assertTrue(settingsView.contains("\"providerDefaultHeaderNames\""))
    assertTrue(settingsView.contains("\"providerSupportsHealthCheck\""))
    assertTrue(settingsView.contains("\"customOpenAICompatibilityMode\""))
    assertTrue(settingsView.contains("\"reasoningEffort\""))
    assertTrue(settingsView.contains("\"backgroundKeepAliveEnabled\": true"))
    assertTrue(settingsView.contains("\"openRouterProviderPreferences\""))
    assertTrue(settingsView.contains("\"openRouterMinCodingScore\""))
    assertTrue(settingsView.contains("\"secretRefs\""))
    assertTrue(settingsView.contains("\"FLOVERA_SECRET_1\""))
    assertTrue(settingsView.contains("\"Amap\""))
    assertFalse(settingsView.contains("legacy description should not be exposed"))
    assertTrue(settingsView.contains("\"****1234\""))
    assertFalse(settingsView.contains("secret-metadata-value-1234"))
    assertTrue(settingsView.contains("\"providerInjectsOllamaNumCtx\""))
    assertTrue(settingsView.contains("\"providerInjectsOpenRouterRouting\""))
    assertTrue(settingsView.contains("\"providerRequestHookIds\""))
    assertTrue(settingsView.contains("\"providerRequestOmittedFields\""))
    assertTrue(settingsView.contains("\"providerRequestAddedFields\""))
    assertTrue(settingsView.contains("\"modelSupportsReasoning\""))
    assertTrue(settingsView.contains("\"compressionThresholdPercent\""))
    assertTrue(settingsView.contains("\"networkUserConfigured\""))
    assertTrue(settingsView.contains("\"webSearchUserConfigured\""))
    assertTrue(settingsView.contains("\"customOpenAIBaseUrl\""))
    assertTrue(settingsView.contains("\"customOpenAIChatCompletionsPath\""))
    assertTrue(settingsView.contains("\"recentHtmlPaths\""))
    assertEquals(1, proposals.size)
    assertEquals("Use softer theme", proposals.first().title)
    assertEquals("#C989B8", proposals.first().changes.themeColor)
    assertEquals(1, toolProposals.size)
    assertEquals("tool", toolProposals.first().type)
    assertEquals("workspace_search", toolProposals.first().name)
    assertFalse(workspace.deleteSettingsProposal(".flovera/proposals/search-tool.json"))
    assertTrue(workspace.deleteControlledToolProposal(".flovera/proposals/search-tool.json"))
    assertTrue(workspace.listControlledToolProposals().isEmpty())
  }

  @Test
  fun workspaceSettingsProposalsAcceptWrappedAndRawChangeShapes() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "test-settings-proposal-shapes-${System.currentTimeMillis()}")
    workspace.ensureSeedFiles()
    workspace.writeFile(
      ".flovera/proposals/raw-network.json",
      """{"networkEnabled":true}""",
      createAutoSnapshot = false,
    )
    workspace.writeFile(
      ".flovera/proposals/wrapped-background.json",
      """
      {
        "type": "settings",
        "title": "Keep alive",
        "changes": {
          "backgroundKeepAliveEnabled": true
        }
      }
      """.trimIndent(),
      createAutoSnapshot = false,
    )

    val proposals = workspace.listSettingsProposals().associateBy { it.path }

    assertEquals(true, proposals[".flovera/proposals/raw-network.json"]?.changes?.networkEnabled)
    assertEquals(true, proposals[".flovera/proposals/wrapped-background.json"]?.changes?.backgroundKeepAliveEnabled)
    assertFalse(workspace.listControlledToolProposals().any { it.path == ".flovera/proposals/raw-network.json" })
    assertTrue(workspace.deleteSettingsProposal(".flovera/proposals/raw-network.json"))
    assertTrue(workspace.deleteSettingsProposal(".flovera/proposals/wrapped-background.json"))
  }

  @Test
  fun fullAuthorityCapabilitiesExposeDirectSettingsWrite() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "test-full-authority-capabilities-${System.currentTimeMillis()}")
    workspace.ensureFloveraMetadata(FloveraSettingsView(authorityMode = "full"))

    val capabilities = workspace.readFile(".flovera/capabilities.json")

    assertTrue(capabilities.contains("\"authorityMode\": \"full\""))
    assertTrue(capabilities.contains("\"directSettingsWrite\": true"))
    assertTrue(capabilities.contains("\"supportedAuthorityModes\""))
    assertTrue(capabilities.contains("\"full\""))
    assertTrue(capabilities.contains("\"pendingAuthorityModes\": []"))
  }

  @Test
  fun workspaceAutomaticSnapshotsKeepLatestThreeBeforeFileChanges() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "snapshot-auto-${System.currentTimeMillis()}")

    workspace.writeFile("counter.txt", "one")
    workspace.writeFile("counter.txt", "two")
    workspace.writeFile("counter.txt", "three")
    workspace.writeFile("counter.txt", "four")

    val automatic = workspace.listSnapshots().filter { it.kind == "auto" }

    assertEquals(3, automatic.size)
    assertEquals("three", workspace.restoreSnapshot(automatic.first().id)?.let { workspace.readFile("counter.txt") })
  }

  private fun awaitSelectedHtmlUrl(controller: AgentController): String = runBlocking {
    withTimeout(20_000) {
      controller.state
        .map { it.selectedHtmlUrl.orEmpty() }
        .first { it.startsWith("http://127.0.0.1:") }
    }
  }

  private fun awaitSelectedHtmlError(controller: AgentController): String = runBlocking {
    withTimeout(20_000) {
      controller.state
        .map { it.selectedHtmlError }
        .first { it.isNotBlank() }
    }
  }

}
