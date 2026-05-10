package com.flovera.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
  fun tappingSessionRowOpensSession() {
    val store = AgentSessionStore(composeRule.activity.applicationContext)
    val suffix = System.currentTimeMillis()
    val target = store.appendMessage(
      store.create("Tap target $suffix"),
      SessionMessage(role = "user", content = "persisted"),
    )
    store.appendMessage(
      store.create("Other target $suffix"),
      SessionMessage(role = "user", content = "other"),
    )
    val controller = AgentController(composeRule.activity.applicationContext)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText(">").performClick()
    composeRule.onNodeWithText("Sessions").performClick()
    composeRule.onNodeWithTag("open-session-${target.id}").performScrollTo().performClick()

    composeRule.runOnIdle {
      assertEquals(target.id, controller.state.value.session?.id)
    }
  }

  @Test
  fun newConversationShowsDraftAndDoesNotPersistUntilMessage() {
    val context = composeRule.activity.applicationContext
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText(">").performClick()
    composeRule.onNodeWithText("New").performClick()

    val draftId = controller.state.value.session?.id
    composeRule.onNodeWithText("New conversation").assertIsDisplayed()
    composeRule.onNodeWithText("Draft: send a message to create this session.").assertIsDisplayed()
    composeRule.runOnIdle {
      assertTrue(draftId != null)
      assertFalse(controller.state.value.sessions.any { it.id == draftId })
    }

    composeRule.onNodeWithText("Close").performClick()

    composeRule.runOnIdle {
      assertFalse(controller.state.value.sessions.any { it.id == draftId })
      assertTrue(controller.state.value.session?.id != draftId)
    }
  }

  @Test
  fun tappingHtmlFileOpensItInWebView() {
    val controller = AgentController(composeRule.activity.applicationContext)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText("Menu").performClick()
    composeRule.onNodeWithText("Files").performClick()
    composeRule.onNodeWithText("index.html", substring = true).performClick()

    composeRule.runOnIdle {
      assertEquals("index.html", controller.state.value.selectedHtmlPath)
      assertTrue(controller.state.value.selectedHtmlUrl?.endsWith("index.html") == true)
    }
  }
}
