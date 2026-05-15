package com.flovera.app

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
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
import com.flovera.app.agent.AgentRunController
import com.flovera.app.koog.AgentRuntime
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.session.AgentSession
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.session.ContextUsageRecord
import com.flovera.app.session.SessionMessage
import com.flovera.app.workspace.WorkspaceManager
import java.io.ByteArrayOutputStream
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

    composeRule.onNodeWithText("\u53ef\u9009\u62e9 HTML / Markdown / Text \u8fdb\u884c\u6253\u5f00").assertIsDisplayed()
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
    composeRule.onNodeWithText("90% · 900k/1M").assertIsDisplayed()
    assertEquals(0, composeRule.onAllNodesWithText("unknown", substring = true).fetchSemanticsNodes().size)

    composeRule.onNodeWithContentDescription("Context usage details").performClick()

    composeRule.onNodeWithText("Used 900k tokens, total 1M", substring = true).assertIsDisplayed()
    composeRule.onNodeWithText("Flovera automatically compresses background information", substring = true).assertIsDisplayed()
  }

  @Test
  fun runningAgentCanBeInterruptedFromConversation() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en"))
    val runtime = BlockingAgentRuntime()
    val controller = AgentController(
      context,
      agentRunController = AgentRunController(runtime = runtime),
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

    composeRule.onNodeWithText("Run interrupted by user.").assertIsDisplayed()
    composeRule.runOnIdle {
      assertEquals("Agent loop interrupted", controller.state.value.status)
      assertEquals("Run interrupted by user.", controller.state.value.session?.messages?.lastOrNull()?.content)
    }
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
    composeRule.onNodeWithText("Select HTML").assertIsDisplayed()
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
    composeRule.onNodeWithText("index.html", substring = true).performClick()

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
