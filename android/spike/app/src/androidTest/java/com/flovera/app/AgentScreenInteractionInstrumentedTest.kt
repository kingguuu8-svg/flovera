package com.flovera.app

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import com.flovera.app.config.AppSettings
import com.flovera.app.config.SettingsStore
import com.flovera.app.agent.AgentRunEvent
import com.flovera.app.agent.AgentRunController
import com.flovera.app.agent.AgentRunEventSink
import com.flovera.app.agent.AgentRunGuidanceProvider
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
import java.io.File
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AgentScreenInteractionInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  private fun openConversation() {
    composeRule.onNodeWithContentDescription("Open conversation").performClick()
  }

  private fun usableSettings(settings: AppSettings = AppSettings()): AppSettings {
    return settings.copy(provider = "lmstudio", model = "local-model")
  }

  @Test
  fun defaultWebSurfaceShowsEmptyHtmlPrompt() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(usableSettings(AppSettings(selectedHtmlPath = "")))
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("No preview \u00b7 choose display").assertIsDisplayed()
    composeRule.onNodeWithText("Make a scientific calculator").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Open conversation").assertIsDisplayed()
  }

  @Test
  fun starterPromptsFollowChineseLanguageSetting() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(usableSettings(AppSettings(selectedHtmlPath = "", language = "zh")))
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("\u505a\u4e00\u4e2a\u79d1\u5b66\u8ba1\u7b97\u5668").assertIsDisplayed()
    composeRule.onNodeWithText("\u505a\u4e00\u4e2a\u8d2a\u5403\u86c7\u5c0f\u6e38\u620f").assertIsDisplayed()
  }

  @Test
  fun firstOpenConfigurationStartsEmptyAndUnselected() {
    val context = composeRule.activity.applicationContext
    val root = File(context.cacheDir, "first-open-${System.currentTimeMillis()}").apply {
      deleteRecursively()
      mkdirs()
    }
    val missingSettingsStore = SettingsStore(context, File(root, "missing-settings.json"))
    assertEquals(AppSettings(), missingSettingsStore.load())

    val workspaceId = "first-open-${System.currentTimeMillis()}"
    val settingsStore = SettingsStore(context, File(root, "settings.json"))
    settingsStore.save(AppSettings(activeWorkspaceId = workspaceId))
    val sessionStore = AgentSessionStore(context, File(root, "sessions"))
    val controller = AgentController(context, settingsStore, sessionStore)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("No preview \u00b7 choose display").assertIsDisplayed()
    composeRule.onNodeWithText("Make a scientific calculator").assertIsDisplayed()
    composeRule.onNodeWithText("Model API not configured").assertIsDisplayed()
    composeRule.onNodeWithText("Configure an API key to start chatting").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Open settings to configure model API").assertIsDisplayed()
    composeRule.runOnIdle {
      val state = controller.state.value
      assertEquals(null, state.session)
      assertTrue(state.sessions.isEmpty())
      assertEquals("", state.settings.selectedHtmlPath)
      assertEquals("", state.selectedHtmlPath)
      assertEquals("", state.selectedPreviewPath)
      assertEquals(null, state.selectedHtmlUrl)
      assertTrue(state.htmlFiles.contains("index.html"))
    }
  }

  @Test
  fun missingApiGuidanceFollowsChineseLanguageSetting() {
    val context = composeRule.activity.applicationContext
    val root = File(context.cacheDir, "missing-api-zh-${System.currentTimeMillis()}").apply {
      deleteRecursively()
      mkdirs()
    }
    val workspaceId = "missing-api-zh-${System.currentTimeMillis()}"
    val settingsStore = SettingsStore(context, File(root, "settings.json")).also {
      it.save(AppSettings(selectedHtmlPath = "", language = "zh", activeWorkspaceId = workspaceId))
    }
    val controller = AgentController(
      context,
      settingsStore = settingsStore,
      sessionStore = AgentSessionStore(context, File(root, "sessions")),
    )

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("\u5c1a\u672a\u914d\u7f6e\u6a21\u578b API").assertIsDisplayed()
    composeRule.onNodeWithText("\u914d\u7f6e API \u5bc6\u94a5\u540e\u5373\u53ef\u5f00\u59cb\u5bf9\u8bdd").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Open settings to configure model API").performClick()
    composeRule.onNodeWithText("\u8bbe\u7f6e").assertIsDisplayed()
    root.deleteRecursively()
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
    SettingsStore(context).save(usableSettings(AppSettings(language = "en")))
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

    openConversation()
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
    SettingsStore(context).save(usableSettings(AppSettings(language = "en")))
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    openConversation()
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
    SettingsStore(context).save(usableSettings(AppSettings(language = "en")))
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

    openConversation()
    composeRule.onNodeWithText("Conversation").assertIsDisplayed()
    assertEquals(0, composeRule.onAllNodesWithText(title).fetchSemanticsNodes().size)
  }

  @Test
  fun conversationRendersCompressionDividerSeparatelyFromAssistantBubble() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(usableSettings(AppSettings(language = "en")))
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

    openConversation()
    composeRule.onNodeWithText("Context compressed").assertIsDisplayed()
    composeRule.onNodeWithText("Show handoff summary").performClick()
    composeRule.onNodeWithText("Remember the active workspace", substring = true).assertIsDisplayed()
  }

  @Test
  fun contextUsageHeaderUsesDeepSeekCatalogForOldRecordsAndShowsDetailsOnClick() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(usableSettings(AppSettings(language = "en")))
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

    openConversation()
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
    SettingsStore(context).save(usableSettings(AppSettings(language = "en", activeWorkspaceId = workspaceId, selectedHtmlPath = "index.html")))
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

    openConversation()
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
    SettingsStore(context).save(usableSettings(AppSettings(language = "en", activeWorkspaceId = workspaceId, selectedHtmlPath = "index.html")))
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

    openConversation()
    composeRule.onNodeWithContentDescription("Open conversation path $logPath").performClick()

    composeRule.onNodeWithText("Agent Error Log").assertIsDisplayed()
    composeRule.runOnIdle {
      assertEquals(logPath, controller.state.value.selectedPreviewPath)
      assertTrue(controller.state.value.selectedPreviewContent.contains("errorCategory: provider"))
    }

    openConversation()
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
    SettingsStore(context).save(usableSettings(AppSettings(language = "en")))
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

    openConversation()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("start long task")
    composeRule.onNodeWithContentDescription("Send message").performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) { controller.state.value.isRunning }
    composeRule.onNodeWithContentDescription("Interrupt agent").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) { !controller.state.value.isRunning }

    composeRule.onNodeWithText("Run interrupted").assertIsDisplayed()
    composeRule.runOnIdle {
      assertEquals("Agent loop interrupted", controller.state.value.status)
      assertEquals("", controller.state.value.session?.messages?.lastOrNull()?.content)
      assertTrue(controller.state.value.session?.messages?.lastOrNull()?.runEvents?.any { it.type == AgentRunEventType.RUN_INTERRUPTED } == true)
      assertTrue(controller.state.value.session?.messages?.lastOrNull()?.transcriptEvents?.any { it.type == AgentRunEventType.RUN_INTERRUPTED } == true)
      assertTrue(notifier.events.contains("running:Working..."))
      assertTrue(notifier.events.contains("interrupted"))
    }
  }

  @Test
  fun runningAgentFreezesSessionSettingsAndAgentRulesMutations() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(usableSettings(AppSettings(language = "en")))
    val controller = AgentController(
      context,
      agentRunController = AgentRunController(runtime = BlockingAgentRuntime()),
      agentRunStatusNotifier = FakeAgentRunStatusNotifier(),
    )
    val originalSessionId = controller.state.value.session?.id.orEmpty()
    controller.newSession()
    val runningSessionId = controller.state.value.session?.id.orEmpty()
    val originalRules = controller.state.value.agentRulesDraft
    val originalNetwork = controller.state.value.settings.networkEnabled

    composeRule.setContent {
      AgentScreen(controller)
    }

    openConversation()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("start locked task")
    composeRule.onNodeWithContentDescription("Send message").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) { controller.state.value.isRunning }

    composeRule.onNodeWithContentDescription("More").performClick()
    composeRule.onNodeWithText("Sessions").assertIsNotEnabled()
    composeRule.onNodeWithText("Settings").assertIsNotEnabled()
    composeRule.onNodeWithText("AGENT.md").assertIsNotEnabled()

    composeRule.runOnIdle {
      controller.openSession(originalSessionId)
      controller.saveAgentRules("must not be saved during a run")
      controller.setNetworkEnabled(!originalNetwork)
      assertEquals(runningSessionId, controller.state.value.session?.id)
      assertEquals(originalRules, controller.state.value.agentRulesDraft)
      assertEquals(originalNetwork, controller.state.value.settings.networkEnabled)
    }

    composeRule.runOnIdle { controller.interruptAgentRun() }
    composeRule.waitUntil(timeoutMillis = 5_000) { !controller.state.value.isRunning }
  }

  @Test
  fun runningAgentQueuesNextMessageAndStartsItAfterCurrentRun() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(usableSettings(AppSettings(language = "en")))
    val runtime = QueueingAgentRuntime()
    val controller = AgentController(
      context,
      agentRunController = AgentRunController(runtime = runtime),
      agentRunStatusNotifier = FakeAgentRunStatusNotifier(),
    )
    val firstTask = "first queued task ${System.currentTimeMillis()}"

    composeRule.setContent {
      AgentScreen(controller)
    }

    openConversation()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput(firstTask)
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
      assertTrue(contents.contains(firstTask))
      assertTrue(contents.contains("second task"))
      assertTrue(contents.contains("assistant output for $firstTask"))
      assertTrue(contents.contains("assistant output for second task"))
    }
  }

  @Test
  fun runningAgentAcceptsGuidanceForNextRun() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(usableSettings(AppSettings(language = "en")))
    val runtime = QueueingAgentRuntime()
    val controller = AgentController(
      context,
      agentRunController = AgentRunController(runtime = runtime),
      agentRunStatusNotifier = FakeAgentRunStatusNotifier(),
    )
    val firstTask = "first guidance task ${System.currentTimeMillis()}"

    composeRule.setContent {
      AgentScreen(controller)
    }

    openConversation()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput(firstTask)
    composeRule.onNodeWithContentDescription("Send message").performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) { runtime.inputCount() == 1 && controller.state.value.isRunning }
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("keep the UI compact")
    composeRule.onNodeWithContentDescription("Send message").performClick()
    composeRule.onNodeWithContentDescription("Guide queued message").performClick()

    composeRule.runOnIdle {
      assertTrue(controller.state.value.queuedInputs.isEmpty())
      assertEquals("Guidance waiting for next tool result", controller.state.value.status)
    }

    runtime.finishNext()
    composeRule.waitUntil(timeoutMillis = 5_000) { !controller.state.value.isRunning }
    composeRule.runOnIdle {
      assertEquals(listOf(firstTask), runtime.inputsSnapshot())
      assertEquals(listOf("keep the UI compact"), runtime.guidanceSnapshot())
      val firstAssistant = controller.state.value.session?.messages
        ?.lastOrNull { it.role == "assistant" && it.content == "assistant output for $firstTask" }
      val transcript = firstAssistant?.transcriptEvents.orEmpty()
      val guidanceBubble = transcript.indexOfFirst { it.type == "user_guidance" && it.content == "keep the UI compact" }
      val guidanceStatus = transcript.indexOfFirst { it.type == "guidance" && it.title == "Guidance applied" }
      val assistantText = transcript.indexOfFirst { it.type == "assistant_text" && it.content == "assistant output for $firstTask" }
      assertTrue("guidance should be persisted as a transcript user bubble", guidanceBubble >= 0)
      assertTrue("guidance applied status should follow the guidance bubble", guidanceStatus > guidanceBubble)
      assertTrue("assistant output should remain after the guidance events", assistantText > guidanceStatus)
    }
  }

  @Test
  fun unappliedGuidanceRunShowsOnlyUserTextInConversation() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(usableSettings(AppSettings(language = "en")))
    val runtime = QueueingAgentRuntime(consumeGuidanceOnCompletion = false)
    val controller = AgentController(
      context,
      agentRunController = AgentRunController(runtime = runtime),
      agentRunStatusNotifier = FakeAgentRunStatusNotifier(),
    )
    val firstTask = "first unapplied guidance task ${System.currentTimeMillis()}"
    val guidance = "\u6211\u5728\u6D4B\u8BD5markdown\u6E32\u67D3\u529F\u80FD"

    composeRule.setContent {
      AgentScreen(controller)
    }

    openConversation()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput(firstTask)
    composeRule.onNodeWithContentDescription("Send message").performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) { runtime.inputCount() == 1 && controller.state.value.isRunning }
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput(guidance)
    composeRule.onNodeWithContentDescription("Send message").performClick()
    composeRule.onNodeWithContentDescription("Guide queued message").performClick()

    runtime.finishNext()
    composeRule.waitUntil(timeoutMillis = 5_000) { runtime.inputCount() == 2 && controller.state.value.isRunning }

    composeRule.runOnIdle {
      val modelInput = runtime.inputsSnapshot().last()
      assertTrue(modelInput.startsWith("Guidance while the previous agent run was active:"))
      assertTrue(modelInput.contains(guidance))
      assertTrue(modelInput.contains("Continue the current task using this guidance"))

      val userMessages = controller.state.value.session?.messages
        ?.filter { it.role == "user" }
        ?.map { it.content }
        .orEmpty()
      assertTrue(userMessages.contains(guidance))
      assertFalse(userMessages.any { it.contains("Guidance while the previous agent run was active") })
      assertFalse(userMessages.any { it.contains("Continue the current task using this guidance") })
    }

    runtime.finishNext()
    composeRule.waitUntil(timeoutMillis = 5_000) { !controller.state.value.isRunning }
  }

  @Test
  fun mainSurfaceExposesAgentAndHtmlQuickPickerWhileConversationOwnsSecondaryEntries() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(usableSettings(AppSettings(language = "en")))
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithContentDescription("Open conversation").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Current display target").assertIsDisplayed()
    assertEquals(0, composeRule.onAllNodesWithText("Menu").fetchSemanticsNodes().size)

    openConversation()
    composeRule.onNodeWithContentDescription("More").performClick()
    composeRule.onNodeWithText("Sessions").assertIsDisplayed()
    composeRule.onNodeWithText("Open Preview").assertIsDisplayed()
    composeRule.onNodeWithText("Files").assertIsDisplayed()
    composeRule.onNodeWithText("Permissions").performClick()
    composeRule.onNodeWithText("Grant all").assertIsDisplayed()
    composeRule.onNodeWithText("Done").performClick()
    composeRule.onNodeWithContentDescription("More").performClick()
    composeRule.onNodeWithText("AGENT.md").assertIsDisplayed()
    composeRule.onNodeWithText("Settings").assertIsDisplayed()
  }

  @Test
  fun htmlQuickPickerOpensWorkspaceHtmlFromMainSurface() {
    val context = composeRule.activity.applicationContext
    val workspaceId = "quick-picker-${System.currentTimeMillis()}"
    SettingsStore(context).save(
      usableSettings(
        AppSettings(
          language = "en",
          activeWorkspaceId = workspaceId,
          recentHtmlPaths = listOf("quick.html"),
        ),
      ),
    )
    WorkspaceManager(context, workspaceId).also {
      it.ensureSeedFiles()
      it.writeFile("quick.html", "<html><body>quick</body></html>", createAutoSnapshot = false)
    }
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithContentDescription("Current display target").performClick()
    composeRule.onNodeWithText("Preview Display").assertIsDisplayed()
    composeRule.onNodeWithText("HTML Display Files").assertIsDisplayed()
    val firstHtmlRowIndex = 1 + if (controller.state.value.workspaceArtifacts.isEmpty()) {
      0
    } else {
      1 + controller.state.value.workspaceArtifacts.size
    }
    composeRule.onNodeWithTag("preview-display-list").performScrollToIndex(firstHtmlRowIndex)
    composeRule.onNodeWithText("quick.html").performClick()

    composeRule.runOnIdle {
      assertEquals("quick.html", controller.state.value.selectedHtmlPath)
    }
  }

  @Test
  fun currentDisplayPickerExposesShareAction() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(usableSettings(AppSettings(language = "en", selectedHtmlPath = "index.html")))
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithContentDescription("Current display target").performClick()
    composeRule.onNodeWithText("Current Display").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Share current display").assertIsDisplayed()
  }

  @Test
  fun tappingHtmlFileOpensItInWebView() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(usableSettings(AppSettings(language = "en")))
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    openConversation()
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
    SettingsStore(context).save(usableSettings(AppSettings(language = "en", selectedHtmlPath = "index.html")))
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    openConversation()
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
    SettingsStore(context).save(usableSettings(AppSettings(language = "en", activeWorkspaceId = workspaceId, selectedHtmlPath = "index.html")))
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
    SettingsStore(context).save(usableSettings(AppSettings(language = "en")))
    val runtime = ToolProgressAgentRuntime()
    val controller = AgentController(
      context,
      agentRunController = AgentRunController(runtime = runtime),
      agentRunStatusNotifier = FakeAgentRunStatusNotifier(),
    )

    composeRule.setContent {
      AgentScreen(controller)
    }

    openConversation()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("inspect workspace")
    composeRule.onNodeWithContentDescription("Send message").performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) {
      controller.state.value.assistantDraft?.toolEvents?.size == 2
    }
    composeRule.waitForIdle()
    assertFalse(composeRule.onAllNodesWithText("Progress:").fetchSemanticsNodes().isNotEmpty())
    assertFalse(composeRule.onAllNodesWithText("Run timeline").fetchSemanticsNodes().isNotEmpty())
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
    SettingsStore(context).save(usableSettings(AppSettings(language = "en")))
    val runtime = StreamingFinalDraftAgentRuntime()
    val controller = AgentController(
      context,
      agentRunController = AgentRunController(runtime = runtime),
      agentRunStatusNotifier = FakeAgentRunStatusNotifier(),
    )

    composeRule.setContent {
      AgentScreen(controller)
    }

    openConversation()
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
  fun streamingDoesNotPullConversationBackAfterUserScrollsAway() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(usableSettings(AppSettings(language = "en")))
    val store = AgentSessionStore(context)
    var session = store.create("Scroll retention ${System.currentTimeMillis()}")
    repeat(18) { index ->
      session = store.appendMessage(
        session,
        SessionMessage(role = if (index % 2 == 0) "user" else "assistant", content = "history message $index"),
      )
    }
    val runtime = ControlledStreamingAgentRuntime()
    val controller = AgentController(
      context,
      agentRunController = AgentRunController(runtime = runtime),
      agentRunStatusNotifier = FakeAgentRunStatusNotifier(),
    )
    controller.openSession(session.id)

    composeRule.setContent {
      AgentScreen(controller)
    }

    openConversation()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("stream while I read history")
    composeRule.onNodeWithContentDescription("Send message").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      controller.state.value.assistantDraft?.content?.contains("initial streaming block") == true
    }
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodesWithText("initial streaming block", substring = true).fetchSemanticsNodes().isNotEmpty() &&
        composeRule.onAllNodesWithTag("conversation-bottom-anchor").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("conversation-bottom-anchor").assertIsDisplayed()

    composeRule.onNodeWithTag("conversation-list").performTouchInput {
      down(center)
      moveBy(Offset(0f, 80f))
      up()
    }
    composeRule.onNodeWithContentDescription("Jump to latest and lock conversation").assertIsDisplayed()
    repeat(6) {
      composeRule.onNodeWithTag("conversation-list").performTouchInput {
        down(center)
        moveBy(Offset(0f, 240f))
        up()
      }
    }
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodesWithText("history message 17").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("history message 17").assertIsDisplayed()
    runtime.emitNextDelta()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      controller.state.value.assistantDraft?.content?.contains("later streaming delta") == true
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("history message 17").assertIsDisplayed()

    composeRule.onNodeWithContentDescription("Jump to latest and lock conversation").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodesWithTag("conversation-bottom-anchor").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("conversation-bottom-anchor").assertIsDisplayed()
    assertTrue(
      composeRule.onAllNodesWithContentDescription("Jump to latest and lock conversation")
        .fetchSemanticsNodes()
        .isEmpty(),
    )

    runtime.finish()
    composeRule.waitUntil(timeoutMillis = 5_000) { !controller.state.value.isRunning }
  }

  @Test
  fun tappingImageFileOpensNativeImagePreviewOverPreviousHtml() {
    val context = composeRule.activity.applicationContext
    val workspaceId = "image-preview-${System.currentTimeMillis()}"
    SettingsStore(context).save(usableSettings(AppSettings(language = "en", activeWorkspaceId = workspaceId, selectedHtmlPath = "index.html")))
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    workspace.writeBytes("sample.png", testPngBytes(), createAutoSnapshot = false)
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    openConversation()
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
    SettingsStore(context).save(usableSettings(AppSettings(language = "en", activeWorkspaceId = workspaceId, selectedHtmlPath = "index.html")))
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    workspace.writeBytes("sample.pdf", testPdfBytes(), createAutoSnapshot = false)
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    openConversation()
    composeRule.onNodeWithContentDescription("More").performClick()
    composeRule.onNodeWithText("Files").performClick()
    composeRule.onNodeWithText("sample.pdf", substring = true).performClick()

    composeRule.onNodeWithText("sample.pdf").assertIsDisplayed()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodesWithText("Page 1 of 2").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Page 1 of 2").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("PDF preview for sample.pdf, page 1").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Next page").performClick()
    composeRule.onNodeWithText("Page 2 of 2").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("PDF preview for sample.pdf, page 2").assertIsDisplayed()
    composeRule.runOnIdle {
      assertEquals("index.html", controller.state.value.selectedHtmlPath)
      assertEquals("sample.pdf", controller.state.value.selectedPreviewPath)
      assertEquals("application/pdf", controller.state.value.selectedPreviewMimeType)
      assertTrue(controller.state.value.selectedPreviewUri.startsWith("content://"))
      assertEquals("", controller.state.value.selectedPreviewContent)
    }
  }

  @Test
  fun tappingDocxFileOpensPagedNativeTextPreview() {
    val context = composeRule.activity.applicationContext
    val workspaceId = "docx-preview-${System.currentTimeMillis()}"
    SettingsStore(context).save(usableSettings(AppSettings(language = "en", activeWorkspaceId = workspaceId)))
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    workspace.writeBytes(
      "sample.docx",
      officeZip(
        "word/document.xml" to """
          <w:document xmlns:w="word"><w:body>
            <w:p><w:r><w:t>First page</w:t></w:r></w:p>
            <w:p><w:r><w:br w:type="page"/></w:r></w:p>
            <w:p><w:r><w:t>Second page</w:t></w:r></w:p>
          </w:body></w:document>
        """.trimIndent(),
      ),
      createAutoSnapshot = false,
    )
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    openConversation()
    composeRule.onNodeWithContentDescription("More").performClick()
    composeRule.onNodeWithText("Files").performClick()
    composeRule.onNodeWithText("sample.docx", substring = true).performClick()

    composeRule.onNodeWithText("Reading page 1 of 2").assertIsDisplayed()
    composeRule.onNodeWithText("First page").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Next page").performClick()
    composeRule.onNodeWithText("Reading page 2 of 2").assertIsDisplayed()
    composeRule.onNodeWithText("Second page").assertIsDisplayed()
  }

  @Test
  fun agentRulesCancelKeepsPersistedRules() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(usableSettings(AppSettings(language = "en")))
    val controller = AgentController(context)
    val original = controller.state.value.agentRulesDraft

    composeRule.setContent {
      AgentScreen(controller)
    }

    openConversation()
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
    repeat(2) { index ->
      val pageInfo = PdfDocument.PageInfo.Builder(240, 240, index + 1).create()
      val page = document.startPage(pageInfo)
      page.canvas.drawColor(android.graphics.Color.WHITE)
      document.finishPage(page)
    }
    return ByteArrayOutputStream().use { output ->
      document.writeTo(output)
      document.close()
      output.toByteArray()
    }
  }

  private fun officeZip(vararg entries: Pair<String, String>): ByteArray {
    return ByteArrayOutputStream().use { output ->
      ZipOutputStream(output).use { zip ->
        entries.forEach { (path, content) ->
          zip.putNextEntry(ZipEntry(path))
          zip.write(content.toByteArray(Charsets.UTF_8))
          zip.closeEntry()
        }
      }
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

  private class QueueingAgentRuntime(
    private val consumeGuidanceOnCompletion: Boolean = true,
  ) : AgentRuntime {
    private val inputs = Collections.synchronizedList(mutableListOf<String>())
    private val completions = Collections.synchronizedList(mutableListOf<CompletableDeferred<Unit>>())
    private val guidance = Collections.synchronizedList(mutableListOf<String>())

    fun inputCount(): Int = synchronized(inputs) { inputs.size }

    fun inputsSnapshot(): List<String> = synchronized(inputs) { inputs.toList() }

    fun guidanceSnapshot(): List<String> = synchronized(guidance) { guidance.toList() }

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

    override suspend fun runStreaming(
      input: String,
      agentRunId: String,
      settings: AppSettings,
      session: AgentSession,
      workspace: WorkspaceManager,
      recorder: ToolEventRecorder,
      eventSink: AgentRunEventSink,
      guidanceProvider: AgentRunGuidanceProvider,
    ): String {
      val completion = CompletableDeferred<Unit>()
      synchronized(inputs) { inputs += input }
      synchronized(completions) { completions += completion }
      completion.await()
      if (consumeGuidanceOnCompletion) {
        synchronized(guidance) { guidance += guidanceProvider.consumePendingGuidance() }
      }
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
      guidanceProvider: AgentRunGuidanceProvider,
    ): String {
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.MODEL_TEXT_DELTA, modelTextDelta = "partial "))
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.MODEL_TEXT_DELTA, modelTextDelta = "final"))
      completion.await()
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.MODEL_TEXT_DELTA, modelTextDelta = " answer"))
      return "partial final answer"
    }
  }

  private class ControlledStreamingAgentRuntime : AgentRuntime {
    private val nextDelta = CompletableDeferred<Unit>()
    private val completion = CompletableDeferred<Unit>()

    fun emitNextDelta() {
      nextDelta.complete(Unit)
    }

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
    ): String = "initial streaming block\nlater streaming delta"

    override suspend fun runStreaming(
      input: String,
      agentRunId: String,
      settings: AppSettings,
      session: AgentSession,
      workspace: WorkspaceManager,
      recorder: ToolEventRecorder,
      eventSink: AgentRunEventSink,
      guidanceProvider: AgentRunGuidanceProvider,
    ): String {
      eventSink.emit(
        AgentRunEvent(
          type = AgentRunEventType.MODEL_TEXT_DELTA,
          modelTextDelta = buildString {
            repeat(24) { index -> appendLine("initial streaming block $index") }
            append("initial streaming block end")
          },
        ),
      )
      nextDelta.await()
      eventSink.emit(
        AgentRunEvent(
          type = AgentRunEventType.MODEL_TEXT_DELTA,
          modelTextDelta = buildString {
            repeat(80) { index -> appendLine("\nlater streaming delta $index") }
          },
        ),
      )
      completion.await()
      return "initial streaming block\nlater streaming delta"
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
    SettingsStore(context).save(usableSettings(AppSettings(language = "en")))
    val controller = AgentController(context)
    val originalModel = controller.state.value.modelDraft

    composeRule.setContent {
      AgentScreen(controller)
    }

    openConversation()
    composeRule.onNodeWithContentDescription("More").performClick()
    composeRule.onNodeWithText("Settings").performClick()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextClearance()
    composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("discard-model")
    composeRule.onNodeWithText("Cancel").performClick()

    composeRule.runOnIdle {
      assertEquals(originalModel, controller.state.value.modelDraft)
      assertEquals("en", controller.state.value.settings.language)
    }

    openConversation()
    composeRule.onNodeWithContentDescription("More").performClick()
    composeRule.onNodeWithText("Settings").performClick()
    composeRule.onNodeWithText("Language: English").performClick()
    composeRule.onNodeWithText("\u4e2d\u6587").performClick()
    composeRule.onNodeWithText("Save").performClick()

    composeRule.onNodeWithContentDescription("Open conversation").assertIsDisplayed()
    composeRule.runOnIdle {
      assertEquals("zh", controller.state.value.settings.language)
    }
  }
}
