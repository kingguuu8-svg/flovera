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
    assertTrue(systemPrompt.contains("Use workspace_search before broad manual scanning"))
    assertTrue(systemPrompt.contains("Use python_run when calculation"))
    assertTrue(systemPrompt.contains("blocking and conversation-bound"))
    assertTrue(systemPrompt.contains("Use python_package_install only for packages listed"))
    assertTrue(systemPrompt.contains("use artifact_inspect(path) to verify"))
    assertTrue(systemPrompt.contains("supported provider/model profiles"))
    assertFalse(systemPrompt.contains(workspaceRule))
    assertTrue(systemPrompt.contains("\"provider\":\"custom-openai\""))
    assertTrue(systemPrompt.contains("\"model\":\"model-id\""))
    assertTrue(systemPrompt.contains("\"reasoningEffort\":\"medium\""))
    assertTrue(systemPrompt.contains("\"customOpenAICompatibilityMode\":\"generic\""))
    assertTrue(systemPrompt.contains("\"openRouterProviderPreferences\":{\"sort\":\"latency\"}"))
    assertTrue(systemPrompt.contains("\"openRouterMinCodingScore\":0.7"))
    assertTrue(userInput.contains("Workspace user rules from AGENT.md:"))
    assertTrue(userInput.contains(workspaceRule))
    assertTrue(userInput.contains("Recent session history:"))
    assertTrue(userInput.contains("Current user request:"))
  }
}
