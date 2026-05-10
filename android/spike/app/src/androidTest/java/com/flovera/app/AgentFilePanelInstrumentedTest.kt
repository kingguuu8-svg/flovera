package com.flovera.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.flovera.app.theme.FloveraTheme
import org.junit.Rule
import org.junit.Test

class AgentFilePanelInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun filesPanelShowsWorkspaceTreeWithActionMenuSemantics() {
    val controller = AgentController(composeRule.activity.applicationContext)
    composeRule.setContent {
      FloveraTheme {
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
