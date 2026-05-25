package com.flovera.app.agent

import com.flovera.app.config.AppSettings
import com.flovera.app.koog.AgentPromptBuilder
import com.flovera.app.koog.AgentRuntime
import com.flovera.app.koog.KoogAgentRuntime
import com.flovera.app.koog.KoogSessionHandoffCompressor
import com.flovera.app.koog.ModelProviderCatalog
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.session.AgentSession
import com.flovera.app.session.AgentRunTimelineEvent
import com.flovera.app.session.ContextUsageRecord
import com.flovera.app.session.ConversationTranscriptEvent
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
    onRunEvent: (AgentRunEvent) -> Unit = {},
  ): Job? {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return null

    val withUser = appendUserPrompt(session, trimmed)
    val agentRunId = "${withUser.id}-${UUID.randomUUID()}"
    val startedAtMillis = System.currentTimeMillis()
    val contextRecord = estimateContextUsage(trimmed, settings, withUser, workspace)
    val contextCompressed = shouldCompressContext(contextRecord)
    val runStartedEvent = lifecycleRunEvent(
      type = AgentRunEventType.RUN_STARTED,
      title = "Run started",
      detail = "provider=${settings.provider}, model=${settings.model}, sessionId=${withUser.id}, agentRunId=$agentRunId",
      status = AGENT_RUN_STATUS_RUNNING,
    )
    var activeRunEvents = listOfNotNull(runStartedEvent.timelineEvent) +
      buildInitialRunTimeline(contextRecord, trimmed, contextCompressed)
    val startedSession = if (contextCompressed) {
      withUser
    } else {
      appendContextRecord(withUser, contextRecord)
    }
    val startDraft = if (contextCompressed) {
      SessionMessage(
        role = "assistant",
        content = "Compressing context...",
        runEvents = activeRunEvents,
        transcriptEvents = buildConversationTranscriptEvents(
          timelineEvents = activeRunEvents,
          content = "",
          role = "assistant",
          includeContent = false,
        ),
      )
    } else {
      SessionMessage(
        role = "assistant",
        content = "Working...",
        runEvents = activeRunEvents,
        transcriptEvents = buildConversationTranscriptEvents(
          timelineEvents = activeRunEvents,
          content = "",
          role = "assistant",
          includeContent = false,
        ),
      )
    }
    val runState = AgentRunEventAccumulator(
      statusContent = startDraft.content,
      baseTimelineEvents = activeRunEvents,
      buildToolProgressNarration = ::buildToolProgressNarration,
      buildToolTimelineEvents = ::buildToolTimelineEvents,
      buildConversationTranscriptEvents = ::buildConversationTranscriptEvents,
      onDraft = onDraft,
    )
    val eventSink = AgentRunEventSink { event ->
      onRunEvent(event)
      runState.emit(event)
    }
    onRunEvent(runStartedEvent)
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
          activeRunEvents = buildCompressedRunTimeline(activeRunEvents, compressedRecord)
          runState.replaceBaseTimeline(activeRunEvents, statusContent = "Working...")
          onSessionUpdated(
            compressedSession,
            runState.draftMessage(),
          )
        }
      } else {
        startedSession
      }
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
        eventSink.emit(
          AgentRunEvent(
            type = AgentRunEventType.TOOL_EVENTS_CHANGED,
            title = "Tool events changed",
            detail = "Completed tool calls: ${events.size}",
            status = AGENT_RUN_STATUS_RUNNING,
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
          runtime.runStreaming(
            input = trimmed,
            agentRunId = agentRunId,
            settings = settings,
            session = preparedSession,
            workspace = workspace,
            recorder = recorder,
            eventSink = eventSink,
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
          val events = recorder.snapshot()
          val finalContent = runState.finalTextOr(output)
          val runCompletedEvent = lifecycleRunEvent(
            type = AgentRunEventType.RUN_COMPLETED,
            title = "Run completed",
            detail = "Completed with ${events.size} tool call(s).",
            status = AGENT_RUN_STATUS_COMPLETED,
          )
          onRunEvent(runCompletedEvent)
          val persistedEvents = runState.persistedTimelineEvents(
            listOfNotNull(
              finalTimelineEvent(
                title = "Final response ready",
                detail = "The assistant response was saved to the session.",
                status = AGENT_RUN_STATUS_COMPLETED,
              ),
              runCompletedEvent.timelineEvent,
            ),
          )
          SessionMessage(
            role = "assistant",
            content = finalContent,
            toolEvents = events,
            runEvents = persistedEvents,
            transcriptEvents = buildConversationTranscriptEvents(
              timelineEvents = persistedEvents,
              content = finalContent,
              role = "assistant",
              includeContent = true,
            ),
          )
        },
        onFailure = { error ->
          val events = recorder.snapshot()
          val errorSummary = error.message ?: error.toString()
          val errorCategory = classifyAgentRunError(error)
          val logPath = saveErrorLog(
            error = error,
            errorCategory = errorCategory,
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
            errorCategory = errorCategory,
          )
          val runFailedEvent = lifecycleRunEvent(
            type = AgentRunEventType.RUN_FAILED,
            title = "Run failed",
            detail = "category=$errorCategory, log=$logPath, message=${errorSummary.take(TIMELINE_DETAIL_CHARS)}",
            status = AGENT_RUN_STATUS_FAILED,
          )
          onRunEvent(runFailedEvent)
          val message = buildString {
            appendLine("Error category: $errorCategory")
            appendLine()
            append(errorSummary)
            appendLine()
            appendLine()
            if (events.isNotEmpty()) {
              appendLine("Run stopped after ${events.size} completed tool call(s).")
              appendLine("Checkpoint saved: $latestCheckpointPath")
              appendLine("Resume from this checkpoint instead of repeating completed tool calls.")
              appendLine()
              append(buildCompletedToolSummary(events))
              appendLine()
            }
            append("Error log saved: ")
            append(logPath)
          }
          val persistedEvents = runState.persistedTimelineEvents(
            listOfNotNull(
              runFailedEvent.timelineEvent,
            ),
          )
          SessionMessage(
            role = "error",
            content = message,
            toolEvents = events,
            runEvents = persistedEvents,
            transcriptEvents = buildConversationTranscriptEvents(
              timelineEvents = persistedEvents,
              content = message,
              role = "error",
              includeContent = true,
            ),
          )
        },
      )
      val updated = appendMessage(preparedSession, assistantMessage)
      onFinished(updated, result.isSuccess)
    }
  }

  private class AgentRunEventAccumulator(
    private var statusContent: String,
    baseTimelineEvents: List<AgentRunTimelineEvent>,
    private val buildToolProgressNarration: (List<ToolEvent>) -> String,
    private val buildToolTimelineEvents: (List<ToolEvent>) -> List<AgentRunTimelineEvent>,
    private val buildConversationTranscriptEvents: (
      timelineEvents: List<AgentRunTimelineEvent>,
      content: String,
      role: String,
      includeContent: Boolean,
    ) -> List<ConversationTranscriptEvent>,
    private val onDraft: (SessionMessage) -> Unit,
  ) {
    private var baseTimelineEvents: List<AgentRunTimelineEvent> = baseTimelineEvents
    private var latestToolEvents: List<ToolEvent> = emptyList()
    private val modelText = StringBuilder()
    private var modelTextStreamingStarted: Boolean = false

    fun replaceBaseTimeline(events: List<AgentRunTimelineEvent>, statusContent: String) {
      this.baseTimelineEvents = events
      this.statusContent = statusContent
    }

    fun emit(event: AgentRunEvent) {
      when (event.type) {
        AgentRunEventType.TOOL_EVENTS_CHANGED -> {
          latestToolEvents = event.toolEvents
        }

        AgentRunEventType.MODEL_TEXT_DELTA,
        AgentRunEventType.FINAL_TEXT_DELTA -> {
          val delta = event.modelTextDelta.ifEmpty { event.finalTextDelta }
          if (delta.isNotEmpty()) {
            modelTextStreamingStarted = true
            modelText.append(delta)
          }
        }

        else -> {
          event.timelineEvent?.let { timelineEvent ->
            baseTimelineEvents = baseTimelineEvents + timelineEvent
          }
        }
      }
      onDraft(draftMessage())
    }

    fun draftMessage(): SessionMessage {
      val timelineEvents = draftTimelineEvents()
      val content = draftContent()
      return SessionMessage(
        role = "assistant",
        content = content,
        toolEvents = latestToolEvents,
        runEvents = timelineEvents,
        transcriptEvents = buildConversationTranscriptEvents(
          timelineEvents,
          content,
          "assistant",
          modelTextStreamingStarted,
        ),
      )
    }

    fun finalTextOr(output: String): String {
      if (!modelTextStreamingStarted) return output
      val streamed = modelText.toString()
      if (output.isNotBlank() && output.length >= streamed.length) return output
      return streamed.ifBlank { output }
    }

    fun persistedTimelineEvents(finalEvents: List<AgentRunTimelineEvent>): List<AgentRunTimelineEvent> {
      return baseTimelineEvents +
        buildToolTimelineEvents(latestToolEvents) +
        listOfNotNull(finalStreamingSummaryEvent(status = AGENT_RUN_STATUS_COMPLETED)) +
        finalEvents
    }

    private fun draftContent(): String {
      return when {
        modelTextStreamingStarted -> modelText.toString().ifBlank { "Writing assistant response..." }
        latestToolEvents.isNotEmpty() -> buildToolProgressNarration(latestToolEvents)
        else -> statusContent
      }
    }

    private fun draftTimelineEvents(): List<AgentRunTimelineEvent> {
      return baseTimelineEvents +
        buildToolTimelineEvents(latestToolEvents) +
        if (modelTextStreamingStarted) {
          listOfNotNull(finalStreamingSummaryEvent(status = AGENT_RUN_STATUS_RUNNING))
        } else if (latestToolEvents.isNotEmpty()) {
          listOf(
            AgentRunTimelineEvent(
              type = "thinking",
              title = "Thinking",
              detail = "Waiting for the next model or tool result.",
              status = AGENT_RUN_STATUS_RUNNING,
            ),
          )
        } else {
          emptyList()
        }
    }

    private fun finalStreamingSummaryEvent(status: String): AgentRunTimelineEvent? {
      if (!modelTextStreamingStarted) return null
      val chars = modelText.length
      val hasTools = latestToolEvents.isNotEmpty()
      return AgentRunTimelineEvent(
        type = if (hasTools) "assistant_text_streaming" else "final_response_streaming",
        title = when {
          hasTools && status == AGENT_RUN_STATUS_COMPLETED -> "Assistant text streamed"
          hasTools -> "Assistant text streaming"
          status == AGENT_RUN_STATUS_COMPLETED -> "Final response streamed"
          else -> "Final response streaming"
        },
        detail = if (hasTools) {
          "Received $chars streamed assistant character(s) around tool activity from the runtime."
        } else {
          "Received $chars streamed final-answer character(s) from the runtime."
        },
        status = status,
        compact = false,
      )
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
    val toolSchemaChars = estimateToolCatalogChars(settings, webSearchAvailable)
    val providerOverheadChars = estimateProviderRequestOverheadChars(settings, recentHistory.size)
    val estimatedRequestChars = input.length +
      historyChars +
      rulesChars +
      workspaceListingChars +
      toolSchemaChars +
      providerOverheadChars
    val approximateTokens = approximateTokens(estimatedRequestChars)
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
      toolSchemaChars = toolSchemaChars,
      providerOverheadChars = providerOverheadChars,
      estimatedRequestChars = estimatedRequestChars,
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

  private fun estimateToolCatalogChars(settings: AppSettings, webSearchAvailable: Boolean): Int {
    var chars = 7_500
    if (settings.networkEnabled) chars += 1_600
    if (webSearchAvailable) chars += 1_200
    if (settings.agentAuthorityMode != "safe") chars += 900
    return chars
  }

  private fun estimateProviderRequestOverheadChars(settings: AppSettings, recentHistoryCount: Int): Int {
    val providerFields = settings.provider.length + settings.model.length
    val providerSpecific = when (settings.provider) {
      "deepseek", "custom-openai", "openrouter", "xai", "alibaba", "moonshot", "zai" -> 900
      "anthropic", "gemini", "bedrock" -> 1_200
      else -> 1_000
    }
    return providerSpecific + providerFields + (recentHistoryCount * 36)
  }

  private fun buildInitialRunTimeline(
    contextRecord: ContextUsageRecord,
    input: String,
    contextCompressed: Boolean,
  ): List<AgentRunTimelineEvent> {
    val events = mutableListOf(contextTimelineEvent(contextRecord))
    if (input.startsWith(GUIDANCE_INPUT_PREFIX)) {
      events += AgentRunTimelineEvent(
        type = "guidance",
        title = "Guidance queued into run",
        detail = "This run includes guidance that was sent while the previous run was active.",
        status = "queued",
        compact = false,
      )
    }
    if (contextCompressed) {
      events += AgentRunTimelineEvent(
        type = "compression",
        title = "Context compression started",
        detail = "The estimated request is over the configured threshold, so Flovera is preparing a handoff summary before calling the model.",
        status = AGENT_RUN_STATUS_RUNNING,
        compact = false,
      )
    } else {
      events += thinkingTimelineEvent("Preparing the model request.")
    }
    return events
  }

  private fun buildCompressedRunTimeline(
    events: List<AgentRunTimelineEvent>,
    compressedRecord: ContextUsageRecord,
  ): List<AgentRunTimelineEvent> {
    return events + AgentRunTimelineEvent(
      type = "compression",
      title = "Context compressed",
      detail = buildString {
        append("summarySource=${compressedRecord.summarySource.ifBlank { "local" }}")
        append(", approximateTokens=${compressedRecord.approximateTokens}")
        if (compressedRecord.compressionError.isNotBlank()) {
          append(", error=${compressedRecord.compressionError.take(TIMELINE_DETAIL_CHARS)}")
        }
      },
      status = AGENT_RUN_STATUS_COMPLETED,
      compact = false,
    ) + thinkingTimelineEvent("Continuing the model request from the compressed handoff.")
  }

  private fun contextTimelineEvent(record: ContextUsageRecord): AgentRunTimelineEvent {
    return AgentRunTimelineEvent(
      type = "context_checkpoint",
      title = "Context checkpoint",
      detail = buildString {
        append("provider=${record.provider.ifBlank { "unknown" }}")
        append(", model=${record.model.ifBlank { "unknown" }}")
        append(", estimatedRequestChars=${record.estimatedRequestChars}")
        append(", approximateTokens=${record.approximateTokens}")
        append(", budgetStatus=${record.contextBudgetStatus}")
        val usage = contextUsagePercent(record)
        if (usage.isNotBlank()) append(", usage=$usage")
        record.compressionThresholdPercent?.let { append(", threshold=$it%") }
      },
      status = record.contextBudgetStatus,
      compact = false,
    )
  }

  private fun contextUsagePercent(record: ContextUsageRecord): String {
    val permille = record.contextUsagePermille ?: return ""
    val contextWindow = record.modelContextWindowTokens ?: return ""
    val percent = String.format(Locale.US, "%.1f%%", permille / 10.0)
    return "$percent of $contextWindow"
  }

  private fun thinkingTimelineEvent(detail: String): AgentRunTimelineEvent {
    return AgentRunTimelineEvent(
      type = "thinking",
      title = "Thinking",
      detail = detail,
      status = AGENT_RUN_STATUS_RUNNING,
    )
  }

  private fun finalTimelineEvent(title: String, detail: String, status: String): AgentRunTimelineEvent {
    return AgentRunTimelineEvent(
      type = "final_response",
      title = title,
      detail = detail,
      status = status,
      compact = false,
    )
  }

  private fun buildConversationTranscriptEvents(
    timelineEvents: List<AgentRunTimelineEvent>,
    content: String,
    role: String,
    includeContent: Boolean,
  ): List<ConversationTranscriptEvent> {
    val events = timelineEvents
      .filter(::isConversationTranscriptStatusEvent)
      .map { event ->
        ConversationTranscriptEvent(
          type = event.type,
          title = event.title,
          detail = event.detail,
          timestampMillis = event.timestampMillis,
          status = event.status,
          compact = event.compact,
        )
      }
      .toMutableList()
    if (includeContent && content.isNotBlank()) {
      events += ConversationTranscriptEvent(
        type = if (role == "error") "error_text" else "assistant_text",
        role = role,
        content = content,
        timestampMillis = System.currentTimeMillis(),
      )
    }
    return events
  }

  private fun isConversationTranscriptStatusEvent(event: AgentRunTimelineEvent): Boolean {
    return when (event.type) {
      "guidance",
      "compression",
      "thinking",
      "tool_call",
      "tool_omitted",
      AgentRunEventType.RUN_FAILED,
      AgentRunEventType.RUN_INTERRUPTED -> true
      "assistant_text_streaming" -> event.status == AGENT_RUN_STATUS_RUNNING
      "final_response_streaming" -> event.status == AGENT_RUN_STATUS_RUNNING
      else -> false
    }
  }

  private fun lifecycleRunEvent(type: String, title: String, detail: String, status: String): AgentRunEvent {
    return AgentRunEvent(
      type = type,
      title = title,
      detail = detail,
      status = status,
      timelineEvent = AgentRunTimelineEvent(
        type = type,
        title = title,
        detail = detail.take(TIMELINE_DETAIL_CHARS),
        status = status,
        compact = false,
      ),
    )
  }

  private fun buildToolTimelineEvents(toolEvents: List<ToolEvent>): List<AgentRunTimelineEvent> {
    if (toolEvents.isEmpty()) return emptyList()
    val visibleEvents = toolEvents.takeLast(TIMELINE_TOOL_EVENT_COUNT)
    val omitted = toolEvents.size - visibleEvents.size
    val events = mutableListOf<AgentRunTimelineEvent>()
    if (omitted > 0) {
      events += AgentRunTimelineEvent(
        type = "tool_omitted",
        title = "Earlier tool calls hidden",
        detail = "$omitted earlier completed tool call(s) are stored in the session tool event list.",
        status = AGENT_RUN_STATUS_COMPLETED,
      )
    }
    events += visibleEvents.map { event ->
      AgentRunTimelineEvent(
        type = "tool_call",
        title = "Tool: ${event.name}",
        detail = buildString {
          append(toolProgressLine(event))
          if (event.args.isNotBlank()) {
            appendLine()
            append("args: ${event.args.take(TIMELINE_DETAIL_CHARS)}")
          }
          if (event.result.isNotBlank()) {
            appendLine()
            append("result: ${event.result.take(TIMELINE_DETAIL_CHARS)}")
          }
        },
        timestampMillis = event.timestampMillis,
        status = AGENT_RUN_STATUS_COMPLETED,
      )
    }
    return events
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
    errorCategory: String = "",
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
      errorCategory = errorCategory,
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
      appendLine("Continue the stopped Flovera agent run.")
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

  private fun buildToolProgressNarration(toolEvents: List<ToolEvent>): String {
    if (toolEvents.isEmpty()) return "Working..."
    return buildString {
      appendLine("Working...")
      appendLine()
      appendLine("Progress:")
      toolEvents.takeLast(PROGRESS_NARRATION_EVENT_COUNT).forEach { event ->
        appendLine("- ${toolProgressLine(event)}")
      }
      if (toolEvents.size > PROGRESS_NARRATION_EVENT_COUNT) {
        appendLine("- ... ${toolEvents.size - PROGRESS_NARRATION_EVENT_COUNT} earlier tool call(s)")
      }
    }.trimEnd()
  }

  private fun toolProgressLine(event: ToolEvent): String {
    val path = toolArg(event.args, "path")
    return when (event.name) {
      "list_files" -> "Listed ${path.ifBlank { "workspace" }}"
      "workspace_search" -> {
        val query = toolArg(event.args, "query").ifBlank { "query" }
        "Searched ${path.ifBlank { "workspace" }} for $query"
      }
      "read_file" -> "Read ${path.ifBlank { "a file" }}"
      "write_file" -> "Wrote ${path.ifBlank { "a file" }}"
      "edit_file" -> "Edited ${path.ifBlank { "a file" }}"
      "python_run" -> "Ran Python in ${toolArg(event.args, "cwd").ifBlank { "." }}"
      "python_package_install" -> "Checked Python package ${toolArg(event.args, "package").ifBlank { "(unknown)" }}"
      "artifact_inspect" -> "Inspected ${path.ifBlank { "artifact" }}"
      "fetch_url" -> "Fetched URL"
      "download_file" -> "Downloaded ${path.ifBlank { "file" }}"
      "web_search" -> "Searched the web"
      else -> "Ran ${event.name}"
    }
  }

  private fun toolArg(args: String, name: String): String {
    val prefix = "$name="
    return args.split(", ")
      .firstOrNull { it.startsWith(prefix) }
      ?.removePrefix(prefix)
      ?.trim()
      .orEmpty()
  }

  private fun saveErrorLog(
    error: Throwable,
    errorCategory: String,
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
      appendLine("- errorCategory: $errorCategory")
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

  private fun classifyAgentRunError(error: Throwable): String {
    if (error is SecurityException) return AGENT_RUN_ERROR_PERMISSION
    val type = error.javaClass.name.lowercase(Locale.US)
    val message = (error.message ?: error.toString()).lowercase(Locale.US)
    val combined = "$type\n$message"
    return when {
      combined.contains("permission") || combined.contains("denied") || combined.contains("unauthorized workspace") ->
        AGENT_RUN_ERROR_PERMISSION
      combined.contains("context length") ||
        combined.contains("context window") ||
        combined.contains("maximum context") ||
        combined.contains("token limit") ||
        combined.contains("too many tokens") ->
        AGENT_RUN_ERROR_CONTEXT
      combined.contains("unknownhost") ||
        combined.contains("socket") ||
        combined.contains("connection") ||
        combined.contains("timeout") ||
        combined.contains("timed out") ||
        combined.contains("ssl") ||
        combined.contains("network") ->
        AGENT_RUN_ERROR_NETWORK
      combined.contains("llmclient") ||
        combined.contains("provider") ||
        combined.contains("api key") ||
        combined.contains("apikey") ||
        combined.contains("status code") ||
        combined.contains("error from client") ->
        AGENT_RUN_ERROR_PROVIDER
      combined.contains("tool") ||
        combined.contains("python_run") ||
        combined.contains("floverapythonruntime") ->
        AGENT_RUN_ERROR_TOOL
      else -> AGENT_RUN_ERROR_UNKNOWN
    }
  }

  companion object {
    private const val AGENT_RUN_STATUS_RUNNING = "running"
    private const val AGENT_RUN_STATUS_COMPLETED = "completed"
    private const val AGENT_RUN_STATUS_FAILED = "failed"
    private const val AGENT_RUN_ERROR_PROVIDER = "provider"
    private const val AGENT_RUN_ERROR_NETWORK = "network"
    private const val AGENT_RUN_ERROR_TOOL = "tool"
    private const val AGENT_RUN_ERROR_PERMISSION = "permission"
    private const val AGENT_RUN_ERROR_CONTEXT = "context"
    private const val AGENT_RUN_ERROR_UNKNOWN = "unknown"
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
    private const val PROGRESS_NARRATION_EVENT_COUNT = 8
    private const val TIMELINE_TOOL_EVENT_COUNT = 20
    private const val TIMELINE_DETAIL_CHARS = 360
    private const val GUIDANCE_INPUT_PREFIX = "Guidance while the previous agent run was active:"
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
  val errorCategory: String = "",
  val resumePrompt: String = "",
)
