package com.flovera.app

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.config.AppSettings
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.koog.WorkspaceSearchTool
import com.flovera.app.workspace.WorkspaceController
import com.flovera.app.workspace.FloveraSettingsView
import com.flovera.app.workspace.WorkspaceManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    assertTrue(capabilities.contains("\"workspaceSearch\": true"))
    assertTrue(capabilities.contains("\"workspaceSearchScopes\""))
    assertTrue(capabilities.contains("\"workspace_app_metadata\""))
    assertTrue(capabilities.contains("\"webSearch\": true"))
    assertTrue(capabilities.contains("\"previewFormats\""))
    assertTrue(capabilities.contains("\"json\""))
    assertTrue(capabilities.contains("\"csv\""))
    assertTrue(capabilities.contains("\"pdf\""))
    assertTrue(capabilities.contains("\"modelContextOverrides\": true"))
    assertTrue(capabilities.contains("\"reasoningEffort\": true"))
    assertTrue(capabilities.contains("\"directSettingsWrite\": false"))
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
    assertTrue(settingsView.contains("\"openRouterProviderPreferences\""))
    assertTrue(settingsView.contains("\"openRouterMinCodingScore\""))
    assertTrue(settingsView.contains("\"providerInjectsOllamaNumCtx\""))
    assertTrue(settingsView.contains("\"providerInjectsOpenRouterRouting\""))
    assertTrue(settingsView.contains("\"providerRequestHookIds\""))
    assertTrue(settingsView.contains("\"providerRequestOmittedFields\""))
    assertTrue(settingsView.contains("\"providerRequestAddedFields\""))
    assertTrue(settingsView.contains("\"modelSupportsReasoning\""))
    assertTrue(settingsView.contains("\"compressionThresholdPercent\""))
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
}
