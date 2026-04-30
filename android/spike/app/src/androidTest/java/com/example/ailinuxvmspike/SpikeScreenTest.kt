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
  fun screen_hasFiveRequiredElements() {
    val controller = VmController(composeRule.activity.applicationContext)
    try {
      composeRule.setContent {
        SpikeScreen(controller)
      }

      composeRule.onNodeWithText("Prepare Assets").assertIsDisplayed()
      composeRule.onNodeWithText("Start VM").assertIsDisplayed()
      composeRule.onNodeWithText("Stop VM").assertIsDisplayed()
      composeRule.onNodeWithText("Run echo ready").assertIsDisplayed()
      composeRule.onNodeWithText("AI Linux VM Spike ready.").assertIsDisplayed()
    } finally {
      controller.close()
    }
  }
}
