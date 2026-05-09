package com.example.ailinuxvmspike

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ailinuxvmspike.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test

class AgentFilePanelInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun filesPanelShowsWorkspaceTreeWithActionMenuSemantics() {
    val controller = AgentController(composeRule.activity.applicationContext)
    composeRule.setContent {
      MyApplicationTheme {
        AgentScreen(controller)
      }
    }

    composeRule.onNodeWithText("Menu").performClick()
    composeRule.onNodeWithText("Files").performClick()
    composeRule.onNodeWithText("Workspace Files").assertIsDisplayed()
    composeRule.onNodeWithText("Refresh").assertIsDisplayed()
    composeRule.onNodeWithText(" index.html", substring = true).assertIsDisplayed()
    composeRule.onNodeWithContentDescription("File actions for index.html").assertIsDisplayed()
  }
}
