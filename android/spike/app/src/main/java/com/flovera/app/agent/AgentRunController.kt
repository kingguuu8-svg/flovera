package com.flovera.app.agent

import com.flovera.app.config.AppSettings
import com.flovera.app.koog.AgentRuntime
import com.flovera.app.koog.KoogAgentRuntime
import com.flovera.app.koog.ModelProviderCatalog
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.session.AgentSession
import com.flovera.app.session.ContextUsageRecord
import com.flovera.app.session.SessionMessage
import com.flovera.app.session.ToolEvent
import com.flovera.app.workspace.WorkspaceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AgentRunController(
  private val runtime: AgentRuntime = KoogAgentRuntime(),
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
  fun submit(
    input: String,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
    appendUserPrompt: (AgentSession, String) -> AgentSession,
    appendContextRecord: (AgentSession, ContextUsageRecord) -> AgentSession,
    appendMessage: (AgentSession, SessionMessage) -> AgentSession,
    onStarted: (AgentSession, SessionMessage) -> Unit,
    onDraft: (SessionMessage) -> Unit,
    onFinished: (AgentSession, Boolean) -> Unit,
  ): Job? {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return null

    val withUser = appendUserPrompt(session, trimmed)
    val withContext = appendContextRecord(withUser, estimateContextUsage(trimmed, settings, withUser, workspace))
    val agentRunId = "${withContext.id}-${UUID.randomUUID()}"
    onStarted(withContext, SessionMessage(role = "assistant", content = "Working..."))

    return scope.launch {
      val recorder = ToolEventRecorder { events ->
        onDraft(
          SessionMessage(
            role = "assistant",
            content = "Running tools...",
            toolEvents = events,
          ),
        )
      }
      val result = runCatching {
        runtime.run(
          input = trimmed,
          agentRunId = agentRunId,
          settings = settings,
          session = withContext,
          workspace = workspace,
          recorder = recorder,
        )
      }

      val assistantMessage = result.fold(
        onSuccess = { output ->
          SessionMessage(role = "assistant", content = output, toolEvents = recorder.snapshot())
        },
        onFailure = { error ->
          val events = recorder.snapshot()
          val logPath = saveErrorLog(
            error = error,
            agentRunId = agentRunId,
            settings = settings,
            session = withContext,
            input = trimmed,
            toolEvents = events,
            workspace = workspace,
          )
          val message = buildString {
            append(error.message ?: error.toString())
            appendLine()
            appendLine()
            append("Error log saved: ")
            append(logPath)
          }
          SessionMessage(role = "error", content = message, toolEvents = events)
        },
      )
      val updated = appendMessage(withContext, assistantMessage)
      onFinished(updated, result.isSuccess)
    }
  }

  private fun estimateContextUsage(
    input: String,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
  ): ContextUsageRecord {
    val recentHistory = session.messages.takeLast(12)
    val historyChars = recentHistory.sumOf { message ->
      message.role.length + message.content.take(1_500).length + 2
    }
    val rulesChars = workspace.readAgentRules().length
    val workspaceListingChars = workspace.listFiles(".").length
    val totalChars = input.length + historyChars + rulesChars + workspaceListingChars
    val approximateTokens = approximateTokens(totalChars)
    val provider = ModelProviderCatalog.findProvider(settings.provider)
    val modelContext = provider?.contextFor(settings.model)
    val contextWindowTokens = modelContext?.contextWindowTokens
    return ContextUsageRecord(
      id = UUID.randomUUID().toString(),
      source = "agent_run",
      provider = provider?.id ?: settings.provider,
      model = settings.model,
      messageCount = session.messages.size,
      inputChars = input.length,
      historyChars = historyChars,
      rulesChars = rulesChars,
      workspaceListingChars = workspaceListingChars,
      approximateTokens = approximateTokens,
      modelContextWindowTokens = contextWindowTokens,
      modelContextSource = modelContext?.source ?: "unknown",
      tokenUsageSource = modelContext?.usageSource ?: "estimate",
      contextUsagePermille = usagePermille(approximateTokens, contextWindowTokens),
      compressionThresholdPercent = modelContext?.compressionThresholdPercent,
      compressed = false,
      summary = "No compression was applied for this run.",
    )
  }

  private fun approximateTokens(chars: Int): Int {
    return ((chars + 3) / 4).coerceAtLeast(1)
  }

  private fun usagePermille(tokens: Int, contextWindowTokens: Int?): Int? {
    if (contextWindowTokens == null || contextWindowTokens <= 0) return null
    return ((tokens.toLong() * 1_000L) / contextWindowTokens).coerceIn(0L, 1_000L).toInt()
  }

  private fun saveErrorLog(
    error: Throwable,
    agentRunId: String,
    settings: AppSettings,
    session: AgentSession,
    input: String,
    toolEvents: List<ToolEvent>,
    workspace: WorkspaceManager,
  ): String {
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
    val path = ".flovera/logs/agent-error-$timestamp-${UUID.randomUUID()}.md"
    val content = buildString {
      appendLine("# Agent Error Log")
      appendLine()
      appendLine("- provider: ${settings.provider}")
      appendLine("- model: ${settings.model}")
      appendLine("- sessionId: ${session.id}")
      appendLine("- agentRunId: $agentRunId")
      appendLine("- messageCount: ${session.messages.size}")
      appendLine("- contextRecords: ${session.contextRecords.size}")
      appendLine("- networkEnabled: ${settings.networkEnabled}")
      appendLine("- webSearchEnabled: ${settings.webSearchEnabled}")
      appendLine("- errorType: ${error.javaClass.name}")
      appendLine("- errorMessage: ${error.message ?: error.toString()}")
      appendLine()
      appendLine("## Current User Input")
      appendLine()
      appendLine("```")
      appendLine(input.take(4_000))
      appendLine("```")
      appendLine()
      appendLine("## Tool Events")
      appendLine()
      if (toolEvents.isEmpty()) {
        appendLine("(none)")
      } else {
        toolEvents.forEachIndexed { index, event ->
          appendLine("### ${index + 1}. ${event.name}")
          appendLine()
          appendLine("- timestampMillis: ${event.timestampMillis}")
          appendLine()
          appendLine("args:")
          appendLine("```")
          appendLine(event.args.take(4_000))
          appendLine("```")
          appendLine()
          appendLine("result:")
          appendLine("```")
          appendLine(event.result.take(8_000))
          appendLine("```")
          appendLine()
        }
      }
      appendLine("## Stack Trace")
      appendLine()
      appendLine("```")
      appendLine(error.stackTraceToString().take(16_000))
      appendLine("```")
    }
    workspace.writeFile(path = path, content = content, createAutoSnapshot = false)
    return path
  }
}
