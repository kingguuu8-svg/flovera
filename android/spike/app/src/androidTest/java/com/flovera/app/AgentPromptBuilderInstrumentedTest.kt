package com.flovera.app

import com.flovera.app.koog.AgentPromptBuilder
import com.flovera.app.session.AgentSession
import com.flovera.app.session.SessionMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptBuilderInstrumentedTest {
  @Test
  fun systemPromptKeepsWorkspaceRulesOutOfSystemLayer() {
    val workspaceRule = "Prefer compact UI labels."
    val systemPrompt = AgentPromptBuilder.systemPrompt(networkEnabled = true, webSearchAvailable = true)
    val userInput = AgentPromptBuilder.userInput(
      input = "build a timer",
      session = AgentSession(
        id = "prompt-test",
        title = "Prompt test",
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
        messages = listOf(SessionMessage(role = "assistant", content = "previous answer")),
      ),
      workspaceUserRules = workspaceRule,
    )

    assertTrue(systemPrompt.contains("System rules in this prompt have the highest priority"))
    assertTrue(systemPrompt.contains("Workspace user rules from AGENT.md"))
    assertFalse(systemPrompt.contains(workspaceRule))
    assertTrue(userInput.contains("Workspace user rules from AGENT.md:"))
    assertTrue(userInput.contains(workspaceRule))
    assertTrue(userInput.contains("Recent session history:"))
    assertTrue(userInput.contains("Current user request:"))
  }
}
