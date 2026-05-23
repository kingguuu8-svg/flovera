package com.flovera.app

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import com.flovera.app.config.AppSettings
import com.flovera.app.config.SettingsStore
import com.flovera.app.agent.AgentRunEvent
import com.flovera.app.agent.AgentRunController
import com.flovera.app.agent.AgentRunEventSink
import com.flovera.app.agent.AgentRunStatusNotifier
import com.flovera.app.agent.AgentRunEventType
import com.flovera.app.koog.AgentRuntime
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.session.AgentSession
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.session.ContextUsageRecord
import com.flovera.app.session.SessionMessage
import com.flovera.app.workspace.WorkspaceManager
import java.io.ByteArrayOutputStream
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AgentScreenInteractionInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun defaultWebSurfaceShowsEmptyHtmlPrompt() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(selectedHtmlPath = ""))
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("\u53ef\u9009\u62e9 HTML / Markdown / JSON / CSV / Text \u8fdb\u884c\u6253\u5f00").assertIsDisplayed()
  }

  @Test
  fun statusToastOnlyCoversFailuresAndInvisibleSuccesses() {
    assertFalse(shouldShowStatusToast("Session loaded"))
    assertFalse(shouldShowStatusToast("Conversation reverted"))
    assertFalse(shouldShowStatusToast("Displaying index.html"))
    assertTrue(shouldShowStatusToast("No app can open notes.txt"))
    assertTrue(shouldShowStatusToast("Invalid file name: bad/name"))
    assertTrue(shouldShowStatusToast("Imported shared.txt"))
  }

  @Test
  fun tappingSessionRowOpensSession() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en"))
    val store = AgentSessionStore(context)
    val suffix = System.currentTimeMillis()
    val target = store.appendMessage(
      store.create("Tap target $suffix"),
      SessionMessage(role = "user", content = "persisted"),
    )
    store.appendMessage(
      store.create("Other target $suffix"),
      SessionMessage(role = "user", content = "other"),
    )
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onNodeWithContentDescription("More").performClick()
    composeRule.onNodeWithText("Sessions").performClick()
    composeRule.onNodeWithTag("open-session-${target.id}").performScrollTo().performClick()

    composeRule.runOnIdle {
      assertEquals(target.id, controller.state.value.session?.id)
    }
  }

  @Test
  fun newConversationShowsDraftAndDoesNotPersistUntilMessage() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en"))
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onNodeWithText("New").performClick()

    val draftId = controller.state.value.session?.id
    composeRule.onNodeWithText("New conversation").assertIsDisplayed()
    composeRule.onNodeWithText("Draft: send a message to create this session.").assertIsDisplayed()
    composeRule.runOnIdle {
      assertTrue(draftId != null)
      assertFalse(controller.state.value.sessions.any { it.id == draftId })
    }

    composeRule.onNodeWithContentDescription("Close").performClick()

    composeRule.runOnIdle {
      assertFalse(controller.state.value.sessions.any { it.id == draftId })
      assertTrue(controller.state.value.session?.id != draftId)
    }
  }

  @Test
  fun conversationHeaderDoesNotExposeSessionTitle() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en"))
    val store = AgentSessionStore(context)
    val title = "Hidden title ${System.currentTimeMillis()}"
    val session = store.appendMessage(
      store.create(title),
      SessionMessage(role = "user", content = "hello"),
    )
    val controller = AgentController(context)
    controller.openSession(session.id)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onNodeWithText("Conversation").assertIsDisplayed()
    assertEquals(0, composeRule.onAllNodesWithText(title).fetchSemanticsNodes().size)
  }

  @Test
  fun conversationRendersCompressionDividerSeparatelyFromAssistantBubble() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en"))
    val store = AgentSessionStore(context)
    val session = store.appendMessage(
      store.create("Compression visible ${System.currentTimeMillis()}"),
      SessionMessage(role = "user", content = "before compression"),
    )
    val withDivider = store.appendCompressionDivider(
      session,
      ContextUsageRecord(
        id = "divider-record",
        source = "agent_run",
        provider = "deepseek",
        model = "deepseek-v4-pro",
        messageCount = 4,
        inputChars = 10,
        historyChars = 20,
        rulesChars = 30,
        workspaceListingChars = 40,
        approximateTokens = 900_000,
        contextBudgetStatus = "compression_recommended",
        compressed = true,
        summary = "handoff",
      ),
      "Remember the active workspace and pending UI polish.",
    )
    val controller = AgentController(context)
    controller.openSession(withDivider.id)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onNodeWithText("Context compressed").assertIsDisplayed()
    composeRule.onNodeWithText("Show handoff summary").performClick()
    composeRule.onNodeWithText("Remember the active workspace", substring = true).assertIsDisplayed()
  }

  @Test
  fun contextUsageHeaderUsesDeepSeekCatalogForOldRecordsAndShowsDetailsOnClick() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en"))
    val store = AgentSessionStore(context)
    val session = store.appendMessage(
      store.create("Context compact ${System.currentTimeMillis()}"),
      SessionMessage(role = "user", content = "hello"),
    )
    val withContext = store.appendContextRecord(
      session,
      ContextUsageRecord(
        id = "legacy-context-record",
        source = "agent_run",
        provider = "deepseek",
        model = "deepseek-v4-pro",
        messageCount = 1,
        inputChars = 10,
        historyChars = 20,
        rulesChars = 30,
        workspaceListingChars = 40,
        approximateTokens = 900_000,
      ),
    )
    val controller = AgentController(context)
    controller.openSession(withContext.id)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onNodeWithText("est 90% · 900k/1M").assertIsDisplayed()
    assertEquals(0, composeRule.onAllNodesWithText("unknown", substring = true).fetchSemanticsNodes().size)

    composeRule.onNodeWithContentDescription("Context usage details").performClick()

    composeRule.onNodeWithText("Used 900k tokens, total 1M", substring = true).assertIsDisplayed()
    composeRule.onNodeWithText("Estimated from request characters", substring = true).assertIsDisplayed()
    composeRule.onNodeWithText("toolSchemaChars", substring = true).assertIsDisplayed()
    composeRule.onNodeWithText("Flovera automatically compresses background information", substring = true).assertIsDisplayed()
  }

  @Test
  fun conversationPathLinkOpensExistingWorkspaceFilePreview() {
    val context = composeRule.activity.applicationContext
    val workspaceId = "conversation-path-${System.currentTimeMillis()}"
    SettingsStore(context).save(AppSettings(language = "en", activeWorkspaceId = workspaceId, selectedHtmlPath = "index.html"))
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    workspace.writeFile("notes/info.md", "# Linked note\nOpen target", createAutoSnapshot = false)
    val store = AgentSessionStore(context)
    val session = store.appendMessage(
      store.create("Path link ${System.currentTimeMillis()}"),
      SessionMessage(role = "assistant", content = "See `notes/info.md` for the concrete note."),
    )
    val controller = AgentController(context)
    controller.openSession(session.id)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onNodeWithContentDescription("Open conversation path notes/info.md").performClick()

    composeRule.onNodeWithText("Linked note").assertIsDisplayed()
    composeRule.runOnIdle {
      assertEquals("notes/info.md", controller.state.value.selectedPreviewPath)
      assertTrue(controller.state.value.selectedPreviewContent.contains("Open target"))
    }
  }

  @Test
  fun conversationPathLinksOpenAgentRunLogAndCheckpointFiles() {
    val context = composeRule.activity.applicationContext
    val workspaceId = "conversation-run-path-${System.currentTimeMillis()}"
    SettingsStore(context).save(AppSettings(language = "en", activeWorkspaceId = workspaceId, selectedHtmlPath = "index.html"))
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    val logPath = ".flovera/logs/agent-error-test.md"
    val checkpointPath = ".flovera/runs/test-checkpoint.json"
    workspace.writeFile(logPath, "# Agent Error Log\n\n- errorCategory: provider", createAutoSnapshot = false)
    workspace.writeFile(checkpointPath, """{"status":"failed","errorCategory":"provider"}""", createAutoSnapshot = false)
    val store = AgentSessionStore(context)
    val session = store.appendMessage(
      store.create("Run path link ${System.currentTimeMillis()}"),
      SessionMessage(
        role = "error",
        content = """
          Error category: provider

          Checkpoint saved: $checkpointPath
          Error log saved: $logPath
        """.trimIndent(),
      ),
    )
    val controller = AgentController(context)
    controller.openSession(session.id)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onNodeWithContentDescription("Open conversation path $logPath").performClick()

    composeRule.onNodeWithText("Agent Error Log").assertIsDisplayed()
    composeRule.runOnIdle {
      assertEquals(logPath, controller.state.value.selectedPreviewPath)
      assertTrue(controller.state.value.selectedPreviewContent.contains("errorCategory: provider"))
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onNodeWithContentDescription("Open conversation path $checkpointPath").performClick()

    composeRule.onNodeWithText("JSON preview").assertIsDisplayed()
    composeRule.runOnIdle {
      assertEquals(checkpointPath, controller.state.value.selectedPreviewPath)
      assertTrue(controller.state.value.selectedPreviewContent.contains("\"status\": \"failed\""))
    }
  }

  @Test
  fun runningAgentCanBeInterruptedFromConversation() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en"))
    val runtime = BlockingAgentRuntime()
    val notifier = FakeAgentRunStatusNotifier()
    val controller = AgentController(
      context,
      agentRunController = AgentRunController(runtime = runtime),
      agentRunStatusNotifier = notifier,
    )

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("start long task")
    composeRule.onNodeWithContentDescription("Send message").performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) { controller.state.value.isRunning }
    composeRule.onNodeWithContentDescription("Interrupt agent").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) { !controller.state.value.isRunning }

    assertTrue(composeRule.onAllNodesWithText("Run interrupted by user.").fetchSemanticsNodes().isNotEmpty())
    composeRule.onNodeWithText("Run interrupted").assertIsDisplayed()
    composeRule.runOnIdle {
      assertEquals("Agent loop interrupted", controller.state.value.status)
      assertEquals("Run interrupted by user.", controller.state.value.session?.messages?.lastOrNull()?.content)
      assertTrue(controller.state.value.session?.messages?.lastOrNull()?.runEvents?.any { it.type == AgentRunEventType.RUN_INTERRUPTED } == true)
      assertTrue(notifier.events.contains("running:Working..."))
      assertTrue(notifier.events.contains("interrupted"))
    }
  }

  @Test
  fun runningAgentQueuesNextMessageAndStartsItAfterCurrentRun() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en"))
    val runtime = QueueingAgentRuntime()
    val controller = AgentController(
      context,
      agentRunController = AgentRunController(runtime = runtime),
      agentRunStatusNotifier = FakeAgentRunStatusNotifier(),
    )

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("first task")
    composeRule.onNodeWithContentDescription("Send message").performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) { runtime.inputCount() == 1 && controller.state.value.isRunning }
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("second task")
    composeRule.onNodeWithContentDescription("Send message").performClick()

    composeRule.onNodeWithText("second task").assertIsDisplayed()
    composeRule.runOnIdle {
      assertEquals(listOf("second task"), controller.state.value.queuedInputs.map { it.content })
    }

    runtime.finishNext()
    composeRule.waitUntil(timeoutMillis = 5_000) { runtime.inputCount() == 2 }
    composeRule.runOnIdle {
      assertTrue(controller.state.value.queuedInputs.isEmpty())
      assertEquals("second task", runtime.inputsSnapshot().last())
    }

    runtime.finishNext()
    composeRule.waitUntil(timeoutMillis = 5_000) { !controller.state.value.isRunning }
    composeRule.runOnIdle {
      val contents = controller.state.value.session?.messages?.map { it.content }.orEmpty()
      assertTrue(contents.contains("first task"))
      assertTrue(contents.contains("second task"))
      assertTrue(contents.contains("assistant output for first task"))
      assertTrue(contents.contains("assistant output for second task"))
    }
  }

  @Test
  fun runningAgentAcceptsGuidanceForNextRun() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en"))
    val runtime = QueueingAgentRuntime()
    val controller = AgentController(
      context,
      agentRunController = AgentRunController(runtime = runtime),
      agentRunStatusNotifier = FakeAgentRunStatusNotifier(),
    )

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("first task")
    composeRule.onNodeWithContentDescription("Send message").performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) { runtime.inputCount() == 1 && controller.state.value.isRunning }
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("keep the UI compact")
    composeRule.onNodeWithContentDescription("Send message").performClick()
    composeRule.onNodeWithContentDescription("Guide queued message").performClick()

    assertTrue(composeRule.onAllNodesWithText("Guidance").fetchSemanticsNodes().isNotEmpty())
    composeRule.onNodeWithText("keep the UI compact").assertIsDisplayed()
    composeRule.runOnIdle {
      assertEquals(listOf(QUEUED_INPUT_GUIDANCE), controller.state.value.queuedInputs.map { it.mode })
    }

    runtime.finishNext()
    composeRule.waitUntil(timeoutMillis = 5_000) { runtime.inputCount() == 2 }
    composeRule.runOnIdle {
      val guidedInput = runtime.inputsSnapshot().last()
      assertTrue(guidedInput.contains("Guidance while the previous agent run was active"))
      assertTrue(guidedInput.contains("keep the UI compact"))
    }

    runtime.finishNext()
    composeRule.waitUntil(timeoutMillis = 5_000) { !controller.state.value.isRunning }
  }

  @Test
  fun mainSurfaceExposesAgentAndHtmlQuickPickerWhileConversationOwnsSecondaryEntries() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en"))
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").assertIsDisplayed()
    composeRule.onNodeWithText("HTML").assertIsDisplayed()
    assertEquals(0, composeRule.onAllNodesWithText("Menu").fetchSemanticsNodes().size)

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onNodeWithContentDescription("More").performClick()
    composeRule.onNodeWithText("Sessions").assertIsDisplayed()
    composeRule.onNodeWithText("Open Preview").assertIsDisplayed()
    composeRule.onNodeWithText("Files").assertIsDisplayed()
    composeRule.onNodeWithText("AGENT.md").assertIsDisplayed()
    composeRule.onNodeWithText("Settings").assertIsDisplayed()
  }

  @Test
  fun htmlQuickPickerOpensWorkspaceHtmlFromMainSurface() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en"))
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithContentDescription("Open HTML quick picker").performClick()
    composeRule.onNodeWithText("Workspace Apps").assertIsDisplayed()
    composeRule.onNodeWithText("HTML Files").assertIsDisplayed()
    composeRule.onNodeWithText("index.html").performClick()

    composeRule.runOnIdle {
      assertEquals("index.html", controller.state.value.selectedHtmlPath)
    }
  }

  @Test
  fun tappingHtmlFileOpensItInWebView() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en"))
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onNodeWithContentDescription("More").performClick()
    composeRule.onNodeWithText("Files").performClick()
    composeRule.onNodeWithText("index.html", substring = true).performClick()

    composeRule.runOnIdle {
      assertEquals("index.html", controller.state.value.selectedHtmlPath)
      assertTrue(controller.state.value.selectedHtmlUrl?.endsWith("index.html") == true)
    }
  }

  @Test
  fun tappingMarkdownFileOpensNativePreviewOverPreviousHtml() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en", selectedHtmlPath = "index.html"))
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onNodeWithContentDescription("More").performClick()
    composeRule.onNodeWithText("Files").performClick()
    composeRule.onNodeWithText("README.md", substring = true).performClick()

    composeRule.onNodeWithText("README.md").assertIsDisplayed()
    composeRule.onNodeWithText("Android Agent Workspace").assertIsDisplayed()
    composeRule.runOnIdle {
      assertEquals("index.html", controller.state.value.selectedHtmlPath)
      assertTrue(controller.state.value.selectedHtmlUrl?.endsWith("index.html") == true)
      assertEquals("README.md", controller.state.value.selectedPreviewPath)
      assertTrue(controller.state.value.selectedPreviewContent.contains("Android Agent Workspace"))
    }
  }

  @Test
  fun structuredWorkspaceFilesRenderAsJsonAndCsvPreviews() {
    val context = composeRule.activity.applicationContext
    val workspaceId = "structured-preview-${System.currentTimeMillis()}"
    SettingsStore(context).save(AppSettings(language = "en", activeWorkspaceId = workspaceId, selectedHtmlPath = "index.html"))
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    workspace.writeFile("data.json", """{"name":"Flovera","status":"ready"}""", createAutoSnapshot = false)
    workspace.writeFile("table.csv", "name,status\nFlovera,ready", createAutoSnapshot = false)
    workspace.writeFile("tool.py", "print('ready')", createAutoSnapshot = false)
    val controller = AgentController(context)
    controller.selectWorkspacePreview("data.json")

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("JSON preview").assertIsDisplayed()
    composeRule.onNodeWithText("\"name\": \"Flovera\"", substring = true).assertIsDisplayed()

    composeRule.runOnIdle {
      controller.selectWorkspacePreview("table.csv")
    }

    composeRule.onNodeWithText("CSV preview").assertIsDisplayed()
    composeRule.onNodeWithText("name").assertIsDisplayed()
    composeRule.onNodeWithText("status").assertIsDisplayed()
    composeRule.onNodeWithText("Flovera").assertIsDisplayed()
    composeRule.onNodeWithText("ready").assertIsDisplayed()

    composeRule.runOnIdle {
      controller.selectWorkspacePreview("tool.py")
    }

    composeRule.onNodeWithText("Code preview").assertIsDisplayed()
    composeRule.onNodeWithText("print('ready')").assertIsDisplayed()
  }

  @Test
  fun runningAgentShowsDeterministicToolProgressNarration() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en"))
    val runtime = ToolProgressAgentRuntime()
    val controller = AgentController(
      context,
      agentRunController = AgentRunController(runtime = runtime),
      agentRunStatusNotifier = FakeAgentRunStatusNotifier(),
    )

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("inspect workspace")
    composeRule.onNodeWithContentDescription("Send message").performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) {
      controller.state.value.assistantDraft?.toolEvents?.size == 2
    }
    composeRule.waitForIdle()
    assertTrue(composeRule.onAllNodesWithText("Progress:").fetchSemanticsNodes().isNotEmpty())
    assertTrue(composeRule.onAllNodesWithText("Run timeline").fetchSemanticsNodes().isNotEmpty())
    assertTrue(composeRule.onAllNodesWithText("Context checkpoint").fetchSemanticsNodes().isNotEmpty())
    assertTrue(composeRule.onAllNodesWithText("Tool: list_files").fetchSemanticsNodes().isNotEmpty())
    assertTrue(composeRule.onAllNodesWithText("Tool: read_file").fetchSemanticsNodes().isNotEmpty())
    composeRule.runOnIdle {
      val draft = controller.state.value.assistantDraft?.content.orEmpty()
      assertTrue(draft.contains("Listed agent-demo"))
      assertTrue(draft.contains("Read agent-demo/README.md"))
      assertTrue(controller.state.value.assistantDraft?.runEvents?.any { it.title == "Tool: list_files" } == true)
    }

    runtime.finish()
    composeRule.waitUntil(timeoutMillis = 5_000) { !controller.state.value.isRunning }
  }

  @Test
  fun runningAgentShowsStreamingFinalResponseDraft() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en"))
    val runtime = StreamingFinalDraftAgentRuntime()
    val controller = AgentController(
      context,
      agentRunController = AgentRunController(runtime = runtime),
      agentRunStatusNotifier = FakeAgentRunStatusNotifier(),
    )

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("stream final")
    composeRule.onNodeWithContentDescription("Send message").performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) {
      controller.state.value.assistantDraft?.content == "partial final"
    }
    composeRule.waitForIdle()
    assertTrue(composeRule.onAllNodesWithText("partial final").fetchSemanticsNodes().isNotEmpty())
    composeRule.runOnIdle {
      assertTrue(controller.state.value.assistantDraft?.runEvents?.any { it.title == "Final response streaming" } == true)
    }

    runtime.finish()
    composeRule.waitUntil(timeoutMillis = 5_000) { !controller.state.value.isRunning }
    composeRule.runOnIdle {
      assertEquals("partial final answer", controller.state.value.session?.messages?.lastOrNull()?.content)
      assertTrue(controller.state.value.session?.messages?.lastOrNull()?.runEvents?.any { it.title == "Final response streamed" } == true)
    }
  }

  @Test
  fun tappingImageFileOpensNativeImagePreviewOverPreviousHtml() {
    val context = composeRule.activity.applicationContext
    val workspaceId = "image-preview-${System.currentTimeMillis()}"
    SettingsStore(context).save(AppSettings(language = "en", activeWorkspaceId = workspaceId, selectedHtmlPath = "index.html"))
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    workspace.writeBytes("sample.png", testPngBytes(), createAutoSnapshot = false)
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onNodeWithContentDescription("More").performClick()
    composeRule.onNodeWithText("Files").performClick()
    composeRule.onNodeWithText("sample.png", substring = true).performClick()

    composeRule.onNodeWithText("sample.png").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Image preview for sample.png").assertIsDisplayed()
    composeRule.runOnIdle {
      assertEquals("index.html", controller.state.value.selectedHtmlPath)
      assertEquals("sample.png", controller.state.value.selectedPreviewPath)
      assertEquals("image/png", controller.state.value.selectedPreviewMimeType)
      assertTrue(controller.state.value.selectedPreviewUri.startsWith("content://"))
      assertEquals("", controller.state.value.selectedPreviewContent)
    }
  }

  @Test
  fun tappingPdfFileOpensNativePdfPreviewOverPreviousHtml() {
    val context = composeRule.activity.applicationContext
    val workspaceId = "pdf-preview-${System.currentTimeMillis()}"
    SettingsStore(context).save(AppSettings(language = "en", activeWorkspaceId = workspaceId, selectedHtmlPath = "index.html"))
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    workspace.writeBytes("sample.pdf", testPdfBytes(), createAutoSnapshot = false)
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onNodeWithContentDescription("More").performClick()
    composeRule.onNodeWithText("Files").performClick()
    composeRule.onNodeWithText("sample.pdf", substring = true).performClick()

    composeRule.onNodeWithText("sample.pdf").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("PDF preview for sample.pdf").assertIsDisplayed()
    composeRule.runOnIdle {
      assertEquals("index.html", controller.state.value.selectedHtmlPath)
      assertEquals("sample.pdf", controller.state.value.selectedPreviewPath)
      assertEquals("application/pdf", controller.state.value.selectedPreviewMimeType)
      assertTrue(controller.state.value.selectedPreviewUri.startsWith("content://"))
      assertEquals("", controller.state.value.selectedPreviewContent)
    }
  }

  @Test
  fun agentRulesCancelKeepsPersistedRules() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en"))
    val controller = AgentController(context)
    val original = controller.state.value.agentRulesDraft

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onNodeWithContentDescription("More").performClick()
    composeRule.onNodeWithText("AGENT.md").performClick()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextClearance()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("discard me")
    composeRule.onNodeWithText("Cancel").performClick()

    composeRule.runOnIdle {
      assertEquals(original, controller.state.value.agentRulesDraft)
    }
  }

  private fun testPngBytes(): ByteArray {
    val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    return ByteArrayOutputStream().use { output ->
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
      output.toByteArray()
    }
  }

  private fun testPdfBytes(): ByteArray {
    val document = PdfDocument()
    val pageInfo = PdfDocument.PageInfo.Builder(240, 240, 1).create()
    val page = document.startPage(pageInfo)
    page.canvas.drawColor(android.graphics.Color.WHITE)
    document.finishPage(page)
    return ByteArrayOutputStream().use { output ->
      document.writeTo(output)
      document.close()
      output.toByteArray()
    }
  }

  private class BlockingAgentRuntime : AgentRuntime {
    private val never = CompletableDeferred<Unit>()

    override suspend fun run(
      input: String,
      agentRunId: String,
      settings: AppSettings,
      session: AgentSession,
      workspace: WorkspaceManager,
      recorder: ToolEventRecorder,
    ): String {
      never.await()
      return "unreachable"
    }
  }

  private class QueueingAgentRuntime : AgentRuntime {
    private val inputs = Collections.synchronizedList(mutableListOf<String>())
    private val completions = Collections.synchronizedList(mutableListOf<CompletableDeferred<Unit>>())

    fun inputCount(): Int = synchronized(inputs) { inputs.size }

    fun inputsSnapshot(): List<String> = synchronized(inputs) { inputs.toList() }

    fun finishNext() {
      synchronized(completions) {
        completions.firstOrNull { !it.isCompleted }
      }?.complete(Unit)
    }

    override suspend fun run(
      input: String,
      agentRunId: String,
      settings: AppSettings,
      session: AgentSession,
      workspace: WorkspaceManager,
      recorder: ToolEventRecorder,
    ): String {
      val completion = CompletableDeferred<Unit>()
      synchronized(inputs) { inputs += input }
      synchronized(completions) { completions += completion }
      completion.await()
      return "assistant output for $input"
    }
  }

  private class ToolProgressAgentRuntime : AgentRuntime {
    private val completion = CompletableDeferred<Unit>()

    fun finish() {
      completion.complete(Unit)
    }

    override suspend fun run(
      input: String,
      agentRunId: String,
      settings: AppSettings,
      session: AgentSession,
      workspace: WorkspaceManager,
      recorder: ToolEventRecorder,
    ): String {
      recorder.record("list_files", "path=agent-demo", "agent-demo/README.md")
      recorder.record("read_file", "path=agent-demo/README.md", "# demo")
      completion.await()
      return "assistant output after progress"
    }
  }

  private class StreamingFinalDraftAgentRuntime : AgentRuntime {
    private val completion = CompletableDeferred<Unit>()

    fun finish() {
      completion.complete(Unit)
    }

    override suspend fun run(
      input: String,
      agentRunId: String,
      settings: AppSettings,
      session: AgentSession,
      workspace: WorkspaceManager,
      recorder: ToolEventRecorder,
    ): String {
      return "fallback output"
    }

    override suspend fun runStreaming(
      input: String,
      agentRunId: String,
      settings: AppSettings,
      session: AgentSession,
      workspace: WorkspaceManager,
      recorder: ToolEventRecorder,
      eventSink: AgentRunEventSink,
    ): String {
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.FINAL_TEXT_DELTA, finalTextDelta = "partial "))
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.FINAL_TEXT_DELTA, finalTextDelta = "final"))
      completion.await()
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.FINAL_TEXT_DELTA, finalTextDelta = " answer"))
      return "partial final answer"
    }
  }

  private class FakeAgentRunStatusNotifier : AgentRunStatusNotifier {
    val events = mutableListOf<String>()

    override fun running(message: String) {
      events += "running:$message"
    }

    override fun finished(succeeded: Boolean) {
      events += "finished:$succeeded"
    }

    override fun interrupted() {
      events += "interrupted"
    }
  }

  @Test
  fun settingsCancelKeepsModelDraftAndLanguageSaveSwitchesUi() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en"))
    val controller = AgentController(context)
    val originalModel = controller.state.value.modelDraft

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onNodeWithContentDescription("More").performClick()
    composeRule.onNodeWithText("Settings").performClick()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextClearance()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("discard-model")
    composeRule.onNodeWithText("Cancel").performClick()

    composeRule.runOnIdle {
      assertEquals(originalModel, controller.state.value.modelDraft)
      assertEquals("en", controller.state.value.settings.language)
    }

    composeRule.onNodeWithText("Agent").performClick()
    composeRule.onNodeWithContentDescription("More").performClick()
    composeRule.onNodeWithText("Settings").performClick()
    composeRule.onNodeWithText("Language: English").performClick()
    composeRule.onNodeWithText("\u4e2d\u6587").performClick()
    composeRule.onNodeWithText("Save").performClick()

    composeRule.onNodeWithText("Agent").assertIsDisplayed()
    composeRule.runOnIdle {
      assertEquals("zh", controller.state.value.settings.language)
    }
  }
}
