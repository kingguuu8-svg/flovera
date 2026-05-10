package com.flovera.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AgentScreenInteractionInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun tappingSessionRowOpensSession() {
    val controller = AgentController(composeRule.activity.applicationContext)
    val suffix = System.currentTimeMillis()
    controller.newSession()
    val target = controller.state.value.session!!
    controller.renameSession(target.id, "Tap target $suffix")
    controller.newSession()

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithText(">").performClick()
    composeRule.onNodeWithText("Sessions").performClick()
    composeRule.onNodeWithContentDescription("Open session Tap target $suffix").performClick()

    composeRule.runOnIdle {
      assertEquals(target.id, controller.state.value.session?.id)
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
