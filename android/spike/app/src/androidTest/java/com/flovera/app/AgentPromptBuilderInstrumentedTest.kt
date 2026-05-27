package com.flovera.app

import com.flovera.app.koog.AgentPromptBuilder
import com.flovera.app.session.AgentSession
import com.flovera.app.session.SessionMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptBuilderInstrumentedTest {
  @Test
  fun conversationMarkdownNormalizationRemovesUnsafeControlCharacters() {
    val normalized = normalizeConversationMarkdownContent("alpha\u0000\r\n1. beta\u0085\n\tgamma\uFEFF")

    assertFalse(normalized.contains('\u0000'))
    assertFalse(normalized.contains('\u0085'))
    assertFalse(normalized.contains('\uFEFF'))
    assertTrue(normalized.contains("alpha"))
    assertTrue(normalized.contains("1. beta"))
    assertEquals("beta", stripMarkdownListMarker("1. beta"))
    assertEquals("beta", stripMarkdownListMarker("- beta"))
    assertEquals("1.", parseMarkdownListItem("1. beta")?.marker)
    assertEquals("-", parseMarkdownListItem("- beta")?.marker)
  }

  @Test
  fun conversationMarkdownNormalizationRepairsCommonUtf8Mojibake() {
    val normalized = normalizeConversationMarkdownContent(
      "\u00E4\u00BD\u00A0\u00E5\u00A5\u00BD\u00EF\u00BC\u008CFlovera",
    )

    assertTrue(normalized.contains("\u4F60\u597D"))
    assertFalse(normalized.contains("\u00E4\u00BD"))
  }

  @Test
  fun systemPromptKeepsWorkspaceRulesOutOfSystemLayer() {
    val workspaceRule = "Prefer compact UI labels."
    val systemPrompt = AgentPromptBuilder.systemPrompt(networkEnabled = true, webSearchAvailable = true)
    val fullAuthorityPrompt = AgentPromptBuilder.systemPrompt(
      networkEnabled = true,
      webSearchAvailable = true,
      authorityMode = "full",
    )
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
    assertTrue(systemPrompt.contains("AGENT.md is user-owned workspace guidance"))
    assertTrue(systemPrompt.contains("If the user asks in Chinese, answer in Chinese"))
    assertTrue(systemPrompt.contains("Core boundaries:"))
    assertTrue(systemPrompt.contains("Stable Flovera runtime boundary:"))
    assertTrue(systemPrompt.contains("Tool routing:"))
    assertTrue(systemPrompt.contains("Interactive artifact rules:"))
    assertTrue(systemPrompt.contains("Current run facts:"))
    assertTrue(systemPrompt.contains("Use workspace_search before broad manual scanning"))
    assertTrue(systemPrompt.contains("Use python_run when calculation"))
    assertTrue(systemPrompt.contains("blocking and conversation-bound"))
    assertTrue(systemPrompt.contains("not a daemon, background server"))
    assertTrue(systemPrompt.contains("local_http/python_http apps"))
    assertTrue(systemPrompt.contains("Tool progress UI is app-generated"))
    assertTrue(systemPrompt.contains("Conversation UI renders app-generated status/tool events and real model text deltas"))
    assertTrue(systemPrompt.contains("not hidden chain-of-thought"))
    assertTrue(systemPrompt.contains("Use natural-language progress only when it helps the user"))
    assertTrue(systemPrompt.contains("Final-answer deltas may stream through AgentRunEvent"))
    assertTrue(systemPrompt.contains("do not fake-stream completed text"))
    assertTrue(systemPrompt.contains("Conversation UI can link existing workspace-relative paths"))
    assertTrue(systemPrompt.contains("/__flovera__/api/deepseek/stream"))
    assertTrue(systemPrompt.contains("fetch streaming to consume SSE"))
    assertTrue(systemPrompt.contains("reuse, stop, restart"))
    assertTrue(systemPrompt.contains("--flovera-viewport-height"))
    assertTrue(systemPrompt.contains("window.FloveraViewport"))
    assertTrue(systemPrompt.contains("flovera:viewport"))
    assertTrue(systemPrompt.contains("Provider credentials and API keys live in Flovera app settings"))
    assertTrue(systemPrompt.contains("window.Flovera.toast"))
    assertTrue(systemPrompt.contains("window.Flovera.runAction"))
    assertTrue(systemPrompt.contains("window.Flovera.getJob"))
    assertTrue(systemPrompt.contains("workspaceArtifacts=available"))
    assertTrue(systemPrompt.contains("agentRunTimeline=available"))
    assertTrue(systemPrompt.contains("artifact_diagnose"))
    assertTrue(systemPrompt.contains("confirm Flovera discovered and registered the app"))
    assertTrue(systemPrompt.contains("flovera.app.json"))
    assertTrue(systemPrompt.contains("explicit networkEnabled"))
    assertTrue(systemPrompt.contains("environment refs"))
    assertTrue(systemPrompt.contains("preferred kind local_http"))
    assertTrue(systemPrompt.contains("Default generated artifact layout"))
    assertTrue(systemPrompt.contains("README.md, flovera.app.json"))
    assertTrue(systemPrompt.contains("agent-demo/flovera.app.json"))
    assertTrue(systemPrompt.contains("includeReference=true"))
    assertTrue(systemPrompt.contains("hidden reference app shape"))
    assertTrue(systemPrompt.contains("entrypoints.preview"))
    assertTrue(systemPrompt.contains("Android/mobile WebView first"))
    assertTrue(systemPrompt.contains("readable touch targets"))
    assertTrue(systemPrompt.contains("safe bottom spacing"))
    assertTrue(systemPrompt.contains("do not cancel `touchstart`"))
    assertTrue(systemPrompt.contains("touch-action: manipulation"))
    assertTrue(systemPrompt.contains("Do not invent project-specific JSON handoff protocols"))
    assertTrue(systemPrompt.contains("avoid zero-height/offscreen root containers"))
    assertTrue(systemPrompt.contains("not proof of an end-to-end interactive loop"))
    assertTrue(systemPrompt.contains("Use python_package_install only for packages listed"))
    assertTrue(systemPrompt.contains("use artifact_inspect(path) to verify"))
    assertTrue(systemPrompt.contains("provider/model metadata"))
    assertTrue(systemPrompt.contains("Use the stable runtime boundary above unless"))
    assertTrue(systemPrompt.contains("workaround versus real platform support"))
    assertTrue(systemPrompt.contains("authorityMode=safe"))
    assertTrue(systemPrompt.contains("networkTools=enabled"))
    assertTrue(systemPrompt.contains("webSearch=enabled"))
    assertFalse(systemPrompt.contains(workspaceRule))
    assertFalse(systemPrompt.contains("\"provider\":\"custom-openai\""))
    assertFalse(systemPrompt.contains("\"openRouterProviderPreferences\""))
    assertTrue(fullAuthorityPrompt.contains("authorityMode=full"))
    assertTrue(fullAuthorityPrompt.contains("Full Authority mode: still write settings proposals"))
    assertTrue(fullAuthorityPrompt.contains(".flovera/logs/full-authority.jsonl"))
    assertTrue(fullAuthorityPrompt.contains("does not expose plaintext secrets"))
    assertTrue(userInput.contains("Workspace user rules from AGENT.md:"))
    assertTrue(userInput.contains(workspaceRule))
    assertTrue(userInput.contains("Recent session history:"))
    assertTrue(userInput.contains("Current user request:"))
  }

  @Test
  fun systemPromptUsesStablePrefixAndShortRunFacts() {
    val safePrompt = AgentPromptBuilder.systemPrompt(
      networkEnabled = false,
      webSearchAvailable = false,
      authorityMode = "safe",
    )
    val fullPrompt = AgentPromptBuilder.systemPrompt(
      networkEnabled = true,
      webSearchAvailable = true,
      authorityMode = "full",
    )
    val safeFactsStart = safePrompt.indexOf("Current run facts:")
    val fullFactsStart = fullPrompt.indexOf("Current run facts:")

    assertTrue(safeFactsStart > 0)
    assertEquals(
      safePrompt.substring(0, safeFactsStart),
      fullPrompt.substring(0, fullFactsStart),
    )
    assertTrue(safePrompt.contains("authorityMode=safe"))
    assertTrue(fullPrompt.contains("authorityMode=full"))
    assertTrue("safePrompt length=${safePrompt.length}", safePrompt.length < 30_000)
  }
}
