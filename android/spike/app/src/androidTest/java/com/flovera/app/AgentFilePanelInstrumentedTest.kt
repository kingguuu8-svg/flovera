package com.flovera.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.theme.FloveraTheme
import org.junit.Rule
import org.junit.Test

class AgentFilePanelInstrumentedTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun filesPanelShowsWorkspaceTreeWithActionMenuSemantics() {
    val controller = AgentController(InstrumentationRegistry.getInstrumentation().targetContext)
    composeRule.setContent {
      FloveraTheme {
        AgentScreen(controller)
      }
    }
    composeRule.waitUntil(timeoutMillis = 10_000) {
      composeRule.onAllNodesWithText("Menu").fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithText("Menu").performClick()
    composeRule.onNodeWithText("Files").performClick()
    composeRule.onNodeWithText("Workspace Files").assertIsDisplayed()
    composeRule.onNodeWithText("Refresh").assertIsDisplayed()
    composeRule.onNodeWithText(" index.html", substring = true).assertIsDisplayed()
    composeRule.onNodeWithContentDescription("File actions for index.html").assertIsDisplayed()
  }
}
