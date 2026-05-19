package com.flovera.app.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.utils.io.use
import com.flovera.app.agent.HANDOFF_SOURCE_LLM
import com.flovera.app.agent.HANDOFF_SOURCE_LOCAL_FALLBACK
import com.flovera.app.agent.SessionHandoffCompression
import com.flovera.app.agent.SessionHandoffCompressor
import com.flovera.app.config.AppSettings
import com.flovera.app.session.AgentSession
import com.flovera.app.session.ContextUsageRecord
import com.flovera.app.session.SESSION_ROLE_COMPRESSION
import com.flovera.app.session.SessionHandoffSummarizer
import com.flovera.app.workspace.WorkspaceManager

class KoogSessionHandoffCompressor : SessionHandoffCompressor {
  override suspend fun compress(
    settings: AppSettings,
    session: AgentSession,
    record: ContextUsageRecord,
    workspace: WorkspaceManager,
  ): SessionHandoffCompression {
    val localSummary = SessionHandoffSummarizer.summarize(session, record)
    return runCatching {
      val provider = ModelProviderCatalog.requireProvider(settings.provider)
      val apiKey = settings.apiKeyFor(provider.id)
      require(apiKey.isNotBlank()) { "${provider.label} API key is not configured." }
      val context = ModelProviderCatalog.contextFor(settings)
      val agent = AIAgent(
        promptExecutor = MultiLLMPromptExecutor(ModelProviderCatalog.createClient(provider, apiKey, settings)),
        llmModel = provider.createModel(settings.model, context),
        toolRegistry = ToolRegistry {},
        systemPrompt = handoffSystemPrompt(),
        maxIterations = 1,
      )
      val output = agent.use {
        it.run(
          agentInput = buildHandoffInput(session, record, workspace),
          sessionId = "${session.id}-${record.id}-handoff",
        )
      }
      SessionHandoffCompression(
        summary = normalizeSummary(output).ifBlank { localSummary },
        source = HANDOFF_SOURCE_LLM,
      )
    }.getOrElse { error ->
      SessionHandoffCompression(
        summary = localSummary,
        source = HANDOFF_SOURCE_LOCAL_FALLBACK,
        error = oneLine(error.message ?: error.toString(), 500),
      )
    }
  }

  private fun handoffSystemPrompt(): String {
    return """
      You are Flovera's session handoff compressor.
      Convert the current conversation into a compact continuation brief.
      Preserve only facts, user intent, files changed, open decisions, constraints, tool results, and next actions.
      Do not answer the user's task.
      Do not call tools.
      Do not include generic advice.
      Output Markdown with these sections:
      # Handoff Summary
      ## User Goal
      ## Current State
      ## Important Facts
      ## Files And Tools
      ## Next Step
    """.trimIndent()
  }

  private fun buildHandoffInput(session: AgentSession, record: ContextUsageRecord, workspace: WorkspaceManager): String {
    val messages = session.messages
      .filterNot { it.role == SESSION_ROLE_COMPRESSION }
      .joinToString("\n\n") { message ->
        val tools = if (message.toolEvents.isEmpty()) {
          ""
        } else {
          message.toolEvents.joinToString("\n") { event ->
            "- ${event.name}: ${oneLine(event.result, 220)}"
          }
        }
        buildString {
          appendLine("role: ${message.role}")
          appendLine("content:")
          appendLine(message.content.take(MESSAGE_LIMIT))
          if (tools.isNotBlank()) {
            appendLine("tools:")
            appendLine(tools)
          }
        }.trim()
      }
      .takeLast(HISTORY_LIMIT)

    return """
      Compression record:
      - provider: ${record.provider}
      - model: ${record.model}
      - approximateTokens: ${record.approximateTokens}
      - contextBudgetStatus: ${record.contextBudgetStatus}

      Workspace files:
      ${workspace.listFiles(".").take(WORKSPACE_LIMIT)}

      Conversation to compress:
      ${messages.ifBlank { "(empty)" }}
    """.trimIndent()
  }

  private fun normalizeSummary(value: String): String {
    return value.trim().take(MAX_SUMMARY_CHARS)
  }

  private fun oneLine(value: String, maxChars: Int): String {
    val normalized = value
      .lineSequence()
      .joinToString(" ") { it.trim() }
      .replace(Regex("\\s+"), " ")
      .trim()
    if (normalized.length <= maxChars) return normalized
    return normalized.take(maxChars).trimEnd() + "..."
  }

  private companion object {
    const val MESSAGE_LIMIT = 4_000
    const val HISTORY_LIMIT = 96_000
    const val WORKSPACE_LIMIT = 8_000
    const val MAX_SUMMARY_CHARS = 16_000
  }
}
