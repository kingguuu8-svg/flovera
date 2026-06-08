package com.flovera.app.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.utils.io.use
import com.flovera.app.agent.HANDOFF_SOURCE_LLM
import com.flovera.app.agent.HANDOFF_SOURCE_LOCAL_FALLBACK
import com.flovera.app.agent.InterruptedRunHandoff
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
    interruptedRun: InterruptedRunHandoff?,
  ): SessionHandoffCompression {
    val localSummary = SessionHandoffSummarizer.summarize(session, record, interruptedRun)
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
          agentInput = buildHandoffInput(session, record, workspace, interruptedRun),
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

  override suspend fun summarizeAssistantFinal(
    settings: AppSettings,
    userContent: String,
    assistantContent: String,
    runContext: String,
  ): String {
    val localSummary = localAssistantSummary(userContent, assistantContent, runContext)
    return runCatching {
      val provider = ModelProviderCatalog.requireProvider(settings.provider)
      val apiKey = settings.apiKeyFor(provider.id)
      require(apiKey.isNotBlank()) { "${provider.label} API key is not configured." }
      val context = ModelProviderCatalog.contextFor(settings)
      val agent = AIAgent(
        promptExecutor = MultiLLMPromptExecutor(ModelProviderCatalog.createClient(provider, apiKey, settings)),
        llmModel = provider.createModel(settings.model, context),
        toolRegistry = ToolRegistry {},
        systemPrompt = assistantSummarySystemPrompt(),
        maxIterations = 1,
      )
      val output = agent.use {
        it.run(
          agentInput = buildAssistantSummaryInput(userContent, assistantContent, runContext),
          sessionId = "assistant-summary-${stableHash(userContent + assistantContent)}",
        )
      }
      normalizeSummary(output).ifBlank { localSummary }
    }.getOrElse { localSummary }
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

  private fun buildHandoffInput(
    session: AgentSession,
    record: ContextUsageRecord,
    workspace: WorkspaceManager,
    interruptedRun: InterruptedRunHandoff?,
  ): String {
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

      Interrupted run:
      ${interruptedRun?.toHandoffText() ?: "(none)"}

      Conversation to compress:
      ${messages.ifBlank { "(empty)" }}
    """.trimIndent()
  }

  private fun assistantSummarySystemPrompt(): String {
    return """
      You are Flovera's assistant final-response compressor.
      Summarize one older assistant final response for long-term session carry-forward.
      Preserve decisions made, files or artifacts changed, important verification, unresolved constraints, and the next useful continuation fact.
      Do not restate generic filler, process narration, or repeated explanation.
      Do not invent facts.
      Output plain text under 1200 characters.
    """.trimIndent()
  }

  private fun InterruptedRunHandoff.toHandoffText(): String {
    val tools = toolEvents
      .takeLast(12)
      .joinToString("\n") { event ->
        "- ${event.name}: args=${oneLine(event.args, 180)} result=${oneLine(event.result, 300)}"
      }
      .ifBlank { "- none" }
    val transcript = transcriptEvents
      .takeLast(16)
      .joinToString("\n") { event ->
        "- ${event.type}: ${oneLine(event.content.ifBlank { event.title.ifBlank { event.detail } }, 220)}"
      }
      .ifBlank { "- none" }
    return """
      ## Recovery Mode
      This handoff is for retrying the same interrupted run after a provider failure.
      Do not redo completed tool work unless verification requires it.

      ## Original Input
      ${originalInput.take(MESSAGE_LIMIT)}

      ## Assistant Draft So Far
      ${assistantDraft.take(MESSAGE_LIMIT).ifBlank { "(empty)" }}

      ## Completed Tool Results During Interrupted Run
      $tools

      ## Transcript During Interrupted Run
      $transcript

      ## Failure
      - stage: $failureStage
      - providerError: ${oneLine(providerError, 600)}

      ## Recovery Instruction
      $recoveryInstruction
    """.trimIndent()
  }

  private fun buildAssistantSummaryInput(
    userContent: String,
    assistantContent: String,
    runContext: String,
  ): String {
    return """
      User request:
      ${userContent.take(MESSAGE_LIMIT)}

      Assistant final response:
      ${assistantContent.take(MESSAGE_LIMIT)}

      Run context:
      ${runContext.take(MESSAGE_LIMIT).ifBlank { "(none)" }}
    """.trimIndent()
  }

  private fun normalizeSummary(value: String): String {
    return value.trim().take(MAX_SUMMARY_CHARS)
  }

  private fun localAssistantSummary(
    userContent: String,
    assistantContent: String,
    runContext: String,
  ): String {
    return buildString {
      append("Summary for older assistant response. ")
      if (userContent.isNotBlank()) {
        append("user=")
        append(oneLine(userContent, 220))
        append(". ")
      }
      append("assistant=")
      append(oneLine(assistantContent, 900))
      if (runContext.isNotBlank()) {
        append(" runContext=")
        append(oneLine(runContext, 220))
      }
    }.trim()
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

  private fun stableHash(value: String): String {
    return value.hashCode().toUInt().toString(16)
  }
}
