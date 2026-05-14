package com.flovera.app

import androidx.activity.ComponentActivity
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
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.session.SessionMessage
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

    composeRule.onNodeWithText("\u53ef\u9009\u62e9 HTML \u8fdb\u884c\u6253\u5f00").assertIsDisplayed()
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
  fun mainSurfaceExposesOnlyAgentEntryAndConversationOwnsSecondaryEntries() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en"))
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Agent").assertIsDisplayed()
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
