package com.flovera.app

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.config.AppSettings
import com.flovera.app.workspace.WorkspaceController
import com.flovera.app.workspace.FloveraSettingsView
import com.flovera.app.workspace.WorkspaceManager
import java.io.File
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
    assertTrue(capabilities.contains("\"webSearch\": true"))
    assertTrue(capabilities.contains("\"previewFormats\""))
    assertTrue(capabilities.contains("\"json\""))
    assertTrue(capabilities.contains("\"csv\""))
    assertTrue(capabilities.contains("\"pdf\""))
    assertTrue(capabilities.contains("\"modelContextOverrides\": true"))
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
    assertTrue(capabilities.contains("\"id\": \"custom-openai\""))
    assertTrue(capabilities.contains("\"aliases\""))
    assertTrue(capabilities.contains("\"suggestedModels\""))
    assertTrue(capabilities.contains("\"modelContexts\""))
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
    assertTrue(capabilities.contains("\"inject_openrouter_routing\""))
    assertTrue(capabilities.contains("\"transport\""))
    assertTrue(capabilities.contains("\"flovera_openai_compatible_chat_completions\""))
    assertTrue(capabilities.contains("\"koog_anthropic_messages\""))
    assertTrue(capabilities.contains("\"omittedRequestFields\""))
    assertTrue(capabilities.contains("\"addedRequestFields\""))
    assertTrue(capabilities.contains("\"reasoning\""))
    assertTrue(capabilities.contains("\"reasoning_effort\""))
    assertTrue(capabilities.contains("\"thinking\""))
    assertTrue(capabilities.contains("\"temperature\""))
    assertTrue(capabilities.contains("\"ollama\""))
    assertTrue(capabilities.contains("\"anthropic_messages\""))
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
    assertTrue(settingsView.contains("\"providerAuthType\""))
    assertTrue(settingsView.contains("\"providerDefaultHeaderNames\""))
    assertTrue(settingsView.contains("\"providerSupportsHealthCheck\""))
    assertTrue(settingsView.contains("\"customOpenAICompatibilityMode\""))
    assertTrue(settingsView.contains("\"openRouterProviderPreferences\""))
    assertTrue(settingsView.contains("\"openRouterMinCodingScore\""))
    assertTrue(settingsView.contains("\"providerInjectsOllamaNumCtx\""))
    assertTrue(settingsView.contains("\"providerInjectsOpenRouterRouting\""))
    assertTrue(settingsView.contains("\"providerRequestHookIds\""))
    assertTrue(settingsView.contains("\"providerRequestOmittedFields\""))
    assertTrue(settingsView.contains("\"providerRequestAddedFields\""))
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
