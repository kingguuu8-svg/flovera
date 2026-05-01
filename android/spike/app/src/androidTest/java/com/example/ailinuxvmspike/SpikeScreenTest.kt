package com.example.ailinuxvmspike

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class SpikeScreenTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun screen_hasLinuxComputerControls() {
    val controller = VmController(composeRule.activity.applicationContext)
    try {
      composeRule.setContent {
        SpikeScreen(controller)
      }

      composeRule.onNodeWithText("Linux status: stopped").assertIsDisplayed()
      composeRule.onNodeWithText("Prepare Linux").assertIsDisplayed()
      composeRule.onNodeWithText("Start Linux").assertIsDisplayed()
      composeRule.onNodeWithText("Pause").assertIsDisplayed()
      composeRule.onNodeWithText("Resume").assertIsDisplayed()
      composeRule.onNodeWithText("Shutdown").assertIsDisplayed()
      composeRule.onNodeWithText("Terminal command").assertIsDisplayed()
      composeRule.onNodeWithText("Run Command").assertIsDisplayed()
      composeRule.onNodeWithText("AI Linux VM Spike ready.").assertIsDisplayed()
    } finally {
      controller.close()
    }
  }
}
