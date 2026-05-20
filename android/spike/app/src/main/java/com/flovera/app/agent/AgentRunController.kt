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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AgentRunController(
  private val runtime: AgentRuntime = KoogAgentRuntime(),
  private val handoffCompressor: SessionHandoffCompressor = KoogSessionHandoffCompressor(),
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
  private val shouldCompressContext: (ContextUsageRecord) -> Boolean = {
    it.contextBudgetStatus == AgentContextBudget.STATUS_COMPRESSION_RECOMMENDED
  },
) {
  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

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
      val startedAtMillis = System.currentTimeMillis()
      var latestCheckpointPath = checkpointPath(agentRunId)
      val recorder = ToolEventRecorder { events ->
        latestCheckpointPath = saveRunCheckpoint(
          status = AGENT_RUN_STATUS_RUNNING,
          agentRunId = agentRunId,
          startedAtMillis = startedAtMillis,
          settings = settings,
          session = preparedSession,
          input = trimmed,
          toolEvents = events,
          workspace = workspace,
        )
        onDraft(
          SessionMessage(
            role = "assistant",
            content = "Running tools...",
            toolEvents = events,
          ),
        )
      }
      latestCheckpointPath = saveRunCheckpoint(
        status = AGENT_RUN_STATUS_RUNNING,
        agentRunId = agentRunId,
        startedAtMillis = startedAtMillis,
        settings = settings,
        session = preparedSession,
        input = trimmed,
        toolEvents = emptyList(),
        workspace = workspace,
      )
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
          saveRunCheckpoint(
            status = AGENT_RUN_STATUS_COMPLETED,
            agentRunId = agentRunId,
            startedAtMillis = startedAtMillis,
            settings = settings,
            session = preparedSession,
            input = trimmed,
            toolEvents = recorder.snapshot(),
            workspace = workspace,
          )
          SessionMessage(role = "assistant", content = output, toolEvents = recorder.snapshot())
        },
        onFailure = { error ->
          val events = recorder.snapshot()
          val errorSummary = error.message ?: error.toString()
          val logPath = saveErrorLog(
            error = error,
            agentRunId = agentRunId,
            settings = settings,
            session = preparedSession,
            input = trimmed,
            toolEvents = events,
            workspace = workspace,
          )
          latestCheckpointPath = saveRunCheckpoint(
            status = AGENT_RUN_STATUS_FAILED,
            agentRunId = agentRunId,
            startedAtMillis = startedAtMillis,
            settings = settings,
            session = preparedSession,
            input = trimmed,
            toolEvents = events,
            workspace = workspace,
            errorSummary = errorSummary,
            errorLogPath = logPath,
          )
          val message = buildString {
            append(errorSummary)
            appendLine()
            appendLine()
            if (events.isNotEmpty()) {
              appendLine("Run interrupted after ${events.size} completed tool call(s).")
              appendLine("Checkpoint saved: $latestCheckpointPath")
              appendLine("Resume from this checkpoint instead of repeating completed tool calls.")
              appendLine()
              append(buildCompletedToolSummary(events))
              appendLine()
            }
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

  private fun saveRunCheckpoint(
    status: String,
    agentRunId: String,
    startedAtMillis: Long,
    settings: AppSettings,
    session: AgentSession,
    input: String,
    toolEvents: List<ToolEvent>,
    workspace: WorkspaceManager,
    errorSummary: String = "",
    errorLogPath: String = "",
  ): String {
    val path = checkpointPath(agentRunId)
    val checkpoint = AgentRunCheckpoint(
      agentRunId = agentRunId,
      sessionId = session.id,
      status = status,
      provider = settings.provider,
      model = settings.model,
      startedAtMillis = startedAtMillis,
      updatedAtMillis = System.currentTimeMillis(),
      messageCount = session.messages.size,
      inputPreview = input.take(CHECKPOINT_INPUT_PREVIEW_CHARS),
      toolCallCount = toolEvents.size,
      toolEvents = toolEvents.map { event ->
        event.copy(
          args = event.args.take(CHECKPOINT_EVENT_ARGS_CHARS),
          result = event.result.take(CHECKPOINT_EVENT_RESULT_CHARS),
        )
      },
      errorSummary = errorSummary.take(CHECKPOINT_ERROR_CHARS),
      errorLogPath = errorLogPath,
      resumePrompt = buildResumePrompt(input, toolEvents, errorSummary, errorLogPath),
    )
    val content = json.encodeToString(checkpoint)
    runCatching {
      workspace.writeFile(path = path, content = content, createAutoSnapshot = false)
      workspace.writeFile(path = AGENT_RUN_LATEST_CHECKPOINT_PATH, content = content, createAutoSnapshot = false)
    }
    return path
  }

  private fun checkpointPath(agentRunId: String): String {
    val safeRunId = agentRunId.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return "$AGENT_RUN_CHECKPOINT_DIR/$safeRunId.json"
  }

  private fun buildResumePrompt(
    input: String,
    toolEvents: List<ToolEvent>,
    errorSummary: String,
    errorLogPath: String,
  ): String {
    return buildString {
      appendLine("Continue the interrupted Flovera agent run.")
      appendLine("Original user request:")
      appendLine(input.take(CHECKPOINT_INPUT_PREVIEW_CHARS))
      if (errorSummary.isNotBlank()) {
        appendLine()
        appendLine("Last failure:")
        appendLine(errorSummary.take(CHECKPOINT_ERROR_CHARS))
      }
      if (errorLogPath.isNotBlank()) {
        appendLine("Error log: $errorLogPath")
      }
      appendLine()
      append(buildCompletedToolSummary(toolEvents, maxEvents = CHECKPOINT_RESUME_TOOL_EVENT_COUNT))
      appendLine("Use these completed tool results before deciding whether any tool call must be repeated.")
    }.take(CHECKPOINT_RESUME_PROMPT_CHARS)
  }

  private fun buildCompletedToolSummary(
    toolEvents: List<ToolEvent>,
    maxEvents: Int = FAILURE_MESSAGE_TOOL_EVENT_COUNT,
  ): String {
    if (toolEvents.isEmpty()) return "Completed tool calls: none\n"
    return buildString {
      appendLine("Completed tool calls:")
      toolEvents.takeLast(maxEvents).forEachIndexed { index, event ->
        appendLine("${index + 1}. ${event.name}")
        appendLine("args: ${event.args.lineSequence().firstOrNull().orEmpty().take(FAILURE_MESSAGE_LINE_CHARS)}")
        appendLine("result: ${event.result.lineSequence().firstOrNull().orEmpty().take(FAILURE_MESSAGE_LINE_CHARS)}")
      }
      if (toolEvents.size > maxEvents) {
        appendLine("... ${toolEvents.size - maxEvents} earlier tool call(s) are stored in the checkpoint.")
      }
    }
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

  companion object {
    private const val AGENT_RUN_STATUS_RUNNING = "running"
    private const val AGENT_RUN_STATUS_COMPLETED = "completed"
    private const val AGENT_RUN_STATUS_FAILED = "failed"
    private const val AGENT_RUN_CHECKPOINT_DIR = ".flovera/runs"
    private const val AGENT_RUN_LATEST_CHECKPOINT_PATH = ".flovera/runs/latest.json"
    private const val CHECKPOINT_INPUT_PREVIEW_CHARS = 4_000
    private const val CHECKPOINT_EVENT_ARGS_CHARS = 2_000
    private const val CHECKPOINT_EVENT_RESULT_CHARS = 4_000
    private const val CHECKPOINT_ERROR_CHARS = 2_000
    private const val CHECKPOINT_RESUME_PROMPT_CHARS = 8_000
    private const val CHECKPOINT_RESUME_TOOL_EVENT_COUNT = 20
    private const val FAILURE_MESSAGE_TOOL_EVENT_COUNT = 8
    private const val FAILURE_MESSAGE_LINE_CHARS = 240
  }
}

@Serializable
private data class AgentRunCheckpoint(
  val schemaVersion: Int = 1,
  val agentRunId: String,
  val sessionId: String,
  val status: String,
  val provider: String,
  val model: String,
  val startedAtMillis: Long,
  val updatedAtMillis: Long,
  val messageCount: Int,
  val inputPreview: String,
  val toolCallCount: Int,
  val toolEvents: List<ToolEvent>,
  val errorSummary: String = "",
  val errorLogPath: String = "",
  val resumePrompt: String = "",
)
