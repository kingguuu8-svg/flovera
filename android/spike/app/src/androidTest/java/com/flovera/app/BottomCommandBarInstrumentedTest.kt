package com.flovera.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.flovera.app.config.AppSettings
import com.flovera.app.config.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BottomCommandBarInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun mainDisplayUsesBottomCommandBarInsteadOfFloatingDualEntries() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en", provider = "lmstudio"))
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithContentDescription("Current display target").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Open conversation").assertIsDisplayed()
    assertEquals(0, composeRule.onAllNodesWithText("Agent").fetchSemanticsNodes().size)
    assertEquals(0, composeRule.onAllNodesWithText("HTML").fetchSemanticsNodes().size)
  }

  @Test
  fun currentDisplayStateIsAvailableFromBottomBar() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en", provider = "lmstudio"))
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithTag("bottom-display-state").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Current display target").assertIsDisplayed()
  }

  @Test
  fun noPreviewStateStillOpensPreviewSelection() {
    val context = composeRule.activity.applicationContext
    SettingsStore(context).save(AppSettings(language = "en", provider = "lmstudio"))
    val controller = AgentController(context)

    composeRule.setContent {
      AgentScreen(controller)
    }

    composeRule.onNodeWithTag("bottom-display-state").performClick()
    composeRule.onNodeWithText("Preview Display").assertIsDisplayed()
  }
}
