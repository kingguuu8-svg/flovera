package com.flovera.app.agent

import com.flovera.app.config.AppSettings
import com.flovera.app.koog.AgentPromptBuilder
import com.flovera.app.koog.AgentRuntime
import com.flovera.app.koog.KoogAgentRuntime
import com.flovera.app.koog.KoogSessionHandoffCompressor
import com.flovera.app.koog.ModelProviderCatalog
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.session.AgentSession
import com.flovera.app.session.ContextUsageRecord
import com.flovera.app.session.RuntimeSessionHistory
import com.flovera.app.session.SessionMessage
import com.flovera.app.session.ToolEvent
import com.flovera.app.workspace.WorkspaceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AgentRunController(
  private val runtime: AgentRuntime = KoogAgentRuntime(),
  private val handoffCompressor: SessionHandoffCompressor = KoogSessionHandoffCompressor(),
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
  private val shouldCompressContext: (ContextUsageRecord) -> Boolean = {
    it.contextBudgetStatus == AgentContextBudget.STATUS_COMPRESSION_RECOMMENDED
  },
) {
  fun submit(
    input: String,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
    appendUserPrompt: (AgentSession, String) -> AgentSession,
    appendContextRecord: (AgentSession, ContextUsageRecord) -> AgentSession,
    appendCompressionDivider: (AgentSession, ContextUsageRecord, String) -> AgentSession,
    appendMessage: (AgentSession, SessionMessage) -> AgentSession,
    onStarted: (AgentSession, SessionMessage) -> Unit,
    onDraft: (SessionMessage) -> Unit,
    onSessionUpdated: (AgentSession, SessionMessage) -> Unit,
    onFinished: (AgentSession, Boolean) -> Unit,
  ): Job? {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return null

    val withUser = appendUserPrompt(session, trimmed)
    val contextRecord = estimateContextUsage(trimmed, settings, withUser, workspace)
    val contextCompressed = shouldCompressContext(contextRecord)
    val startedSession = if (contextCompressed) {
      withUser
    } else {
      appendContextRecord(withUser, contextRecord)
    }
    val startDraft = if (contextCompressed) {
      SessionMessage(role = "assistant", content = "Compressing context...")
    } else {
      SessionMessage(role = "assistant", content = "Working...")
    }
    onStarted(startedSession, startDraft)

    return scope.launch {
      val preparedSession = if (contextCompressed) {
        val compression = handoffCompressor.compress(
          settings = settings,
          session = withUser,
          record = contextRecord,
          workspace = workspace,
        )
        val compressedRecord = contextRecord.copy(
          compressed = true,
          summary = compression.summary,
          summarySource = compression.source,
          compressionError = compression.error,
        )
        val withContext = appendContextRecord(withUser, compressedRecord)
        appendCompressionDivider(withContext, compressedRecord, compression.summary).also { compressedSession ->
          onSessionUpdated(compressedSession, SessionMessage(role = "assistant", content = "Working..."))
        }
      } else {
        startedSession
      }
      val agentRunId = "${preparedSession.id}-${UUID.randomUUID()}"
      val recorder = ToolEventRecorder { events ->
        onDraft(
          SessionMessage(
            role = "assistant",
            content = "Running tools...",
            toolEvents = events,
          ),
        )
      }
      val result = try {
        Result.success(
          runtime.run(
            input = trimmed,
            agentRunId = agentRunId,
            settings = settings,
            session = preparedSession,
            workspace = workspace,
            recorder = recorder,
          ),
        )
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        Result.failure(error)
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
            session = preparedSession,
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
      val updated = appendMessage(preparedSession, assistantMessage)
      onFinished(updated, result.isSuccess)
    }
  }

  private fun estimateContextUsage(
    input: String,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
  ): ContextUsageRecord {
    val recentHistory = RuntimeSessionHistory.entries(session = session, currentInput = input)
    val historyChars = recentHistory.sumOf { message ->
      message.role.length + message.content.length + 2
    }
    val webSearchAvailable = settings.networkEnabled && settings.webSearchEnabled && settings.braveSearchApiKey.isNotBlank()
    val rulesChars = AgentPromptBuilder.systemPrompt(
      networkEnabled = settings.networkEnabled,
      webSearchAvailable = webSearchAvailable,
      authorityMode = settings.agentAuthorityMode,
    ).length + workspace.readAgentRules().length
    val workspaceListingChars = workspace.listFiles(".").length
    val totalChars = input.length + historyChars + rulesChars + workspaceListingChars
    val approximateTokens = approximateTokens(totalChars)
    val provider = ModelProviderCatalog.findProvider(settings.provider)
    val modelContext = ModelProviderCatalog.contextFor(settings)
    val contextWindowTokens = modelContext.contextWindowTokens
    val budget = AgentContextBudget.evaluate(
      tokens = approximateTokens,
      contextWindowTokens = contextWindowTokens,
      compressionThresholdPercent = modelContext.compressionThresholdPercent,
    )
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
      modelContextSource = modelContext.source,
      tokenUsageSource = modelContext.usageSource,
      contextUsagePermille = budget.usagePermille,
      compressionThresholdPercent = modelContext.compressionThresholdPercent,
      contextBudgetStatus = budget.status,
      contextBudgetReason = budget.reason,
      compressed = false,
      summary = "No compression was applied for this run.",
    )
  }

  private fun approximateTokens(chars: Int): Int {
    return ((chars + 3) / 4).coerceAtLeast(1)
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
