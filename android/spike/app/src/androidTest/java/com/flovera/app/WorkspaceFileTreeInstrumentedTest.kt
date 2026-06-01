package com.flovera.app

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.koog.ArtifactInspectTool
import com.flovera.app.koog.ArtifactDiagnoseTool
import com.flovera.app.koog.FloveraPythonRuntime
import com.flovera.app.koog.PythonRunTool
import com.flovera.app.koog.PythonPackageInstallTool
import com.flovera.app.config.AppSettings
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.koog.WorkspaceCommandRunTool
import com.flovera.app.koog.WorkspaceSearchTool
import com.flovera.app.koog.workspaceToolRegistry
import com.flovera.app.web.FloveraWebBridge
import com.flovera.app.config.SettingsStore
import com.flovera.app.workspace.WorkspaceController
import com.flovera.app.workspace.FloveraSettingsView
import com.flovera.app.workspace.WorkspaceManager
import java.io.File
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
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
import org.junit.Test

private const val TEST_HELPER_JAR_BASE64 =
  "UEsDBBQACAgIAH2mwVwAAAAAAAAAAAAAAAAJAAQATUVUQS1JTkYv/soAAAMAUEsHCAAAAAACAAAAAAAAAFBLAwQUAAgICAB9psFcAAAAAAAAAAAAAAAAFAAAAE1FVEEtSU5GL01BTklGRVNULk1G803My0xLLS7RDUstKs7Mz7NSMNQz4OVyLkpNLElN0XWqBAqY6xnoGVooaPhmJhflF+enlWjycvFyAQBQSwcIBZVIozsAAAA6AAAAUEsDBAoAAAgAAH2mwVwAAAAAAAAAAAAAAAAFAAAAZGVtby9QSwMEFAAICAgAfabBXAAAAAAAAAAAAAAAABEAAABkZW1vL0hlbHBlci5jbGFzc31RTU/CQBB9S4FCqYKIoih+i4UEG423Gg+SGA9EEkETj4VusKS0pLT+LvUAiSb+AH+UcSoQIhh3k9md2Zn33ux8fr19ADiDIiEEQURYRgRRhlRHf9JVS7fbaq3Z4S2PIXpu2qZ3wSAoxXsRMYbsNKnuuabdvvRNy+CuBBFCDAmGcEd3y4G7IGMRSQLRez1uGwxlpTpbrRXnQmNALY4lpEUsy8hgZaLO90xLrTot3eLEdFurNRjS1dknTUIWayLWZeSw8auzEQdDwnPuSJZb0fuEVFDmMeaVaUFXeRlb2GaIec4EK6P8kStil1gM3nXUa24RE+mtOAaRJaumzW/8bpO7Db0ZNBLpPzq+N5Xx/wdpDFLd8d0WvzKD6sQI/zjIwwl2aKrBCtGmuYJhj7w8nYzOSGkI9kIXhn2y0Z+ggDgOcDhOPSU/iObeIT4MEU9LA8ilV6QGWKXbAJvPM/URsoUfyqNvUEsHCBvf9YJkAQAAXgIAAFBLAQIUABQACAgIAH2mwVwAAAAAAgAAAAAAAAAJAAQAAAAAAAAAAAAAAAAAAABNRVRBLUlORi/+ygAAUEsBAhQAFAAICAgAfabBXAWVSKM7AAAAOgAAABQAAAAAAAAAAAAAAAAAPQAAAE1FVEEtSU5GL01BTklGRVNULk1GUEsBAgoACgAACAAAfabBXAAAAAAAAAAAAAAAAAUAAAAAAAAAAAAAAAAAugAAAGRlbW8vUEsBAhQAFAAICAgAfabBXBvf9YJkAQAAXgIAABEAAAAAAAAAAAAAAAAA3QAAAGRlbW8vSGVscGVyLmNsYXNzUEsFBgAAAAAEAAQA7wAAAIACAAAAAA=="

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
    val internalResult = tool.execute(
      WorkspaceSearchTool.Args(query = "internalonlymarker", scope = "workspace_internal"),
    )
    val retrievalResult = tool.execute(
      WorkspaceSearchTool.Args(query = "retrievalonlymarker", scope = "workspace_internal"),
    )

    assertTrue(publicResult.contains("No matches"))
    assertTrue(metadataResult.contains(".flovera/proposals/search-tool.json:1"))
    assertTrue(internalMetadataResult.contains("No matches"))
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
    assertTrue(result, result.contains("groovy-args=alpha"))
    assertTrue(result, result.contains("groovy-return"))
    assertTrue(workspace.readFile("out/groovy.txt").contains("groovy ok alpha"))
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
    assertTrue(File(workspace.root, ".flovera/runtime/jvm-artifacts/libs").walkTopDown().any { it.name == "classes.dex" })

    val cachedResult = tool.execute(
      WorkspaceCommandRunTool.Args(
        argv = listOf("groovy", "tools/use_helper.groovy", "beta"),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(cachedResult, cachedResult.contains("Workspace command status=ok exitCode=0"))
    assertTrue(cachedResult, cachedResult.contains("jar-BETA"))
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
    assertTrue(capabilities.contains("\"workspaceCommandShellAccess\": false"))
    assertTrue(capabilities.contains("\"pythonPackageInstall\": true"))
    assertTrue(capabilities.contains("\"pythonPackageCatalogPath\""))
    assertTrue(capabilities.contains("\"pythonBuiltInPackages\""))
    assertTrue(capabilities.contains("\"openpyxl\""))
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
    assertTrue(capabilities.contains("\"koog_stream_frame_event_handler\""))
    assertTrue(capabilities.contains("\"mainSurfaceHtmlQuickPicker\": true"))
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
    assertTrue(capabilities.contains("\"directSettingsWrite\": false"))
    assertTrue(capabilities.contains("\"supportedAuthorityModes\""))
    assertTrue(capabilities.contains("\"full\""))
    assertTrue(capabilities.contains("\"pendingAuthorityModes\": []"))
    assertTrue(capabilities.contains("\"customOpenAICompatibleProvider\": true"))
    assertTrue(capabilities.contains("\"openRouterRouting\": true"))
    assertTrue(capabilities.contains("\"customUrlRouting\": true"))
    assertTrue(capabilities.contains("\"providerProfiles\": true"))
    assertTrue(capabilities.contains("\"providerProfileCatalog\""))
    assertTrue(capabilities.contains("\"id\": \"alibaba\""))
    assertTrue(capabilities.contains("\"id\": \"moonshot\""))
    assertTrue(capabilities.contains("\"id\": \"zai\""))
    assertTrue(capabilities.contains("\"id\": \"huggingface\""))
    assertTrue(capabilities.contains("\"id\": \"ollama-cloud\""))
    assertTrue(capabilities.contains("\"id\": \"ai-gateway\""))
    assertTrue(capabilities.contains("\"id\": \"opencode-go\""))
    assertTrue(capabilities.contains("\"id\": \"xiaomi\""))
    assertTrue(capabilities.contains("\"id\": \"lmstudio\""))
    assertTrue(capabilities.contains("\"id\": \"tencent-tokenhub\""))
    assertTrue(capabilities.contains("\"id\": \"bedrock\""))
    assertTrue(capabilities.contains("\"id\": \"gemini\""))
    assertTrue(capabilities.contains("\"id\": \"google-gemini-cli\""))
    assertTrue(capabilities.contains("\"id\": \"azure-foundry\""))
    assertTrue(capabilities.contains("\"id\": \"xai\""))
    assertTrue(capabilities.contains("\"id\": \"copilot\""))
    assertTrue(capabilities.contains("\"id\": \"copilot-acp\""))
    assertTrue(capabilities.contains("\"id\": \"openai-codex\""))
    assertTrue(capabilities.contains("\"id\": \"nous\""))
    assertTrue(capabilities.contains("\"id\": \"qwen-oauth\""))
    assertTrue(capabilities.contains("\"id\": \"minimax\""))
    assertTrue(capabilities.contains("\"id\": \"minimax-cn\""))
    assertTrue(capabilities.contains("\"id\": \"minimax-oauth\""))
    assertTrue(capabilities.contains("\"id\": \"custom-openai\""))
    assertTrue(capabilities.contains("\"aliases\""))
    assertTrue(capabilities.contains("\"suggestedModels\""))
    assertTrue(capabilities.contains("\"modelContexts\""))
    assertTrue(capabilities.contains("\"supportsReasoning\""))
    assertTrue(capabilities.contains("\"qwen3-coder-plus\""))
    assertTrue(capabilities.contains("\"contextWindowTokens\": 1000000"))
    assertTrue(capabilities.contains("\"source\": \"hermes_model_metadata\""))
    assertTrue(capabilities.contains("\"baseUrl\": \"https://dashscope-intl.aliyuncs.com/compatible-mode/v1\""))
    assertTrue(capabilities.contains("\"baseUrl\": \"https://ai-gateway.vercel.sh/v1\""))
    assertTrue(capabilities.contains("\"defaultHeaderNames\""))
    assertTrue(capabilities.contains("\"HTTP-Referer\""))
    assertTrue(capabilities.contains("\"X-Title\""))
    assertTrue(capabilities.contains("\"requestCompatibilityModes\""))
    assertTrue(capabilities.contains("\"requestHooks\""))
    assertTrue(capabilities.contains("\"omit_request_fields\""))
    assertTrue(capabilities.contains("\"add_request_fields\""))
    assertTrue(capabilities.contains("\"inject_kimi_thinking\""))
    assertTrue(capabilities.contains("\"inject_openrouter_routing\""))
    assertTrue(capabilities.contains("\"inject_lmstudio_reasoning\""))
    assertTrue(capabilities.contains("\"inject_tencent_tokenhub_reasoning\""))
    assertTrue(capabilities.contains("\"inject_nous_portal_reasoning\""))
    assertTrue(capabilities.contains("\"inject_qwen_portal_request_shape\""))
    assertTrue(capabilities.contains("\"inject_copilot_reasoning\""))
    assertTrue(capabilities.contains("\"transport\""))
    assertTrue(capabilities.contains("\"flovera_openai_compatible_chat_completions\""))
    assertTrue(capabilities.contains("\"flovera_codex_responses\""))
    assertTrue(capabilities.contains("\"koog_google_gemini_native\""))
    assertTrue(capabilities.contains("\"koog_bedrock_converse\""))
    assertTrue(capabilities.contains("\"flovera_anthropic_messages\""))
    assertTrue(capabilities.contains("\"flovera_google_cloud_code_assist\""))
    assertTrue(capabilities.contains("\"flovera_external_process\""))
    assertTrue(capabilities.contains("\"omittedRequestFields\""))
    assertTrue(capabilities.contains("\"addedRequestFields\""))
    assertTrue(capabilities.contains("\"reasoning\""))
    assertTrue(capabilities.contains("\"temperature\""))
    assertTrue(capabilities.contains("\"ollama\""))
    assertTrue(capabilities.contains("\"anthropic_messages\""))
    assertTrue(capabilities.contains("\"bedrock_converse\""))
    assertTrue(capabilities.contains("\"codex_responses\""))
    assertTrue(capabilities.contains("\"responsesPath\""))
    assertTrue(capabilities.contains("\"responses\""))
    assertTrue(capabilities.contains("\"authType\": \"oauth_device_code\""))
    assertTrue(capabilities.contains("\"authType\": \"oauth_external\""))
    assertTrue(capabilities.contains("\"authType\": \"copilot\""))
    assertTrue(capabilities.contains("\"authType\": \"external_process\""))
    assertTrue(capabilities.contains("\"providerRequestHooks\": true"))
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
