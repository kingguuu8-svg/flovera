package com.flovera.app.agent

import com.flovera.app.config.AppSettings
import com.flovera.app.koog.AgentPayloadTokenEstimator
import com.flovera.app.koog.AgentRequestFootprintBuilder
import com.flovera.app.koog.AgentRuntime
import com.flovera.app.koog.KoogContextOverflowDetector
import com.flovera.app.koog.KoogAgentRuntime
import com.flovera.app.koog.KoogSessionHandoffCompressor
import com.flovera.app.koog.ModelProviderCatalog
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.session.AgentSession
import com.flovera.app.session.AgentRunTimelineEvent
import com.flovera.app.session.ContextUsageRecord
import com.flovera.app.session.ConversationTranscriptEvent
import com.flovera.app.session.PromptContextBlock
import com.flovera.app.session.PromptContextLedger
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

private const val STREAMING_DRAFT_MIN_INTERVAL_MS = 120L
private const val STREAMING_DRAFT_MIN_CHAR_DELTA = 96

class AgentRunController(
  private val runtime: AgentRuntime = KoogAgentRuntime(),
  private val handoffCompressor: SessionHandoffCompressor = KoogSessionHandoffCompressor(),
  private val contextOverflowDetector: ContextOverflowDetector = KoogContextOverflowDetector(),
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
    visibleInput: String = input,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
    appendUserPrompt: (AgentSession, String) -> AgentSession,
    appendContextRecord: (AgentSession, ContextUsageRecord) -> AgentSession,
    appendCompressionDivider: (AgentSession, ContextUsageRecord, String) -> AgentSession,
    appendPromptContextBlocks: (AgentSession, List<PromptContextBlock>) -> AgentSession,
    appendMessage: (AgentSession, SessionMessage) -> AgentSession,
    onStarted: (AgentSession, SessionMessage) -> Unit,
    onDraft: (SessionMessage) -> Unit,
    onSessionUpdated: (AgentSession, SessionMessage) -> Unit,
    onFinished: (AgentSession, Boolean) -> Unit,
    onRunEvent: (AgentRunEvent) -> Unit = {},
    guidanceProvider: AgentRunGuidanceProvider = AgentRunGuidanceProvider.None,
    additionalTranscriptEvents: () -> List<ConversationTranscriptEvent> = { emptyList() },
  ): Job? {
    val trimmed = input.trim()
    val visibleTrimmed = visibleInput.trim().ifBlank { trimmed }
    if (trimmed.isBlank()) return null

    val withUser = appendUserPrompt(session, visibleTrimmed)
    val agentRunId = "${withUser.id}-${UUID.randomUUID()}"
    val startedAtMillis = System.currentTimeMillis()
    val contextRecord = estimateContextUsage(trimmed, visibleTrimmed, settings, withUser, workspace)
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
      additionalTranscriptEvents = additionalTranscriptEvents,
      onDraft = onDraft,
    )
    val eventSink = AgentRunEventSink { event ->
      onRunEvent(event)
      runState.emit(event)
    }
    onRunEvent(runStartedEvent)
    onStarted(startedSession, startDraft)

    suspend fun maybeAppendAssistantSummariesForCompression(source: AgentSession): AgentSession {
      var updated = source
      var usage = estimateContextUsage(trimmed, visibleTrimmed, settings, updated, workspace)
      if ((usage.contextUsagePermille ?: 0) < PromptContextLedger.ASSISTANT_SUMMARY_TRIGGER_USAGE_PERMILLE) {
        return updated
      }
      for (candidate in PromptContextLedger.assistantSummaryCandidates(updated, trimmed, visibleTrimmed)) {
        if ((usage.contextUsagePermille ?: 0) < PromptContextLedger.ASSISTANT_SUMMARY_TRIGGER_USAGE_PERMILLE) {
          break
        }
        val runContext = PromptContextLedger.withBackfilledBlocks(updated)
          .filter { it.runIndex == candidate.run.index && it.kind == PromptContextLedger.KIND_RUN_CONTEXT }
          .joinToString("\n") { it.content }
        val summary = handoffCompressor.summarizeAssistantFinal(
          settings = settings,
          userContent = candidate.userContent,
          assistantContent = candidate.assistantContent,
          runContext = runContext,
        ).trim()
        if (summary.isBlank()) continue
        val sourceMessage = updated.messages.getOrNull(candidate.sourceMessageIndex) ?: continue
        updated = appendPromptContextBlocks(
          updated,
          listOf(
            PromptContextLedger.buildAssistantSummaryBlock(
              sourceMessageIndex = candidate.sourceMessageIndex,
              runIndex = candidate.run.index,
              role = candidate.assistantRole,
              summary = summary,
              sourceTimestampMillis = sourceMessage.timestampMillis,
            ),
          ),
        )
        usage = estimateContextUsage(trimmed, visibleTrimmed, settings, updated, workspace)
      }
      return updated
    }

    suspend fun prepareCompressionRestartSession(
      source: AgentSession,
      baseRecord: ContextUsageRecord,
      interruptedRun: InterruptedRunHandoff? = null,
    ): Pair<AgentSession, ContextUsageRecord> {
      val compression = handoffCompressor.compress(
        settings = settings,
        session = source,
        record = baseRecord,
        workspace = workspace,
        interruptedRun = interruptedRun,
      )
      val compressedRecord = baseRecord.copy(
        compressed = true,
        summary = compression.summary,
        summarySource = compression.source,
        compressionError = compression.error,
      )
      val withContext = appendContextRecord(source, compressedRecord)
      val withDivider = appendCompressionDivider(withContext, compressedRecord, compression.summary)
      return maybeAppendAssistantSummariesForCompression(withDivider) to compressedRecord
    }

    return scope.launch {
      var currentSession = if (contextCompressed) {
        val (compressedSession, compressedRecord) = prepareCompressionRestartSession(
          source = withUser,
          baseRecord = contextRecord,
        )
        compressedSession.also {
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

      fun newRecorder(sessionForCheckpoint: AgentSession): ToolEventRecorder {
        return ToolEventRecorder { events ->
          latestCheckpointPath = saveRunCheckpoint(
            status = AGENT_RUN_STATUS_RUNNING,
            agentRunId = agentRunId,
            startedAtMillis = startedAtMillis,
            settings = settings,
            session = sessionForCheckpoint,
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
      }

      fun saveRunningCheckpoint(sessionForCheckpoint: AgentSession, recorder: ToolEventRecorder) {
        latestCheckpointPath = saveRunCheckpoint(
          status = AGENT_RUN_STATUS_RUNNING,
          agentRunId = agentRunId,
          startedAtMillis = startedAtMillis,
          settings = settings,
          session = sessionForCheckpoint,
          input = trimmed,
          toolEvents = recorder.snapshot(),
          workspace = workspace,
        )
      }

      suspend fun runRuntime(sessionForRun: AgentSession, recorderForRun: ToolEventRecorder): Result<String> {
        return try {
          Result.success(
            runtime.runStreaming(
              input = trimmed,
              agentRunId = agentRunId,
              settings = settings,
              session = sessionForRun,
              workspace = workspace,
              recorder = recorderForRun,
              eventSink = eventSink,
              guidanceProvider = guidanceProvider,
            ),
          )
        } catch (error: CancellationException) {
          throw error
        } catch (error: Throwable) {
          Result.failure(error)
        }
      }

      var recorder = newRecorder(currentSession)
      saveRunningCheckpoint(currentSession, recorder)
      var result = runRuntime(currentSession, recorder)
      val firstError = result.exceptionOrNull()
      if (firstError != null) {
        val firstErrorCategory = classifyAgentRunError(firstError)
        val overflowDetection = when (firstErrorCategory) {
          AGENT_RUN_ERROR_CONTEXT -> ContextOverflowDetection(
            isOverflow = true,
            source = "local_error_classifier",
            reason = "error category is context",
          )
          AGENT_RUN_ERROR_PROVIDER -> contextOverflowDetector.detect(settings, firstError)
          else -> ContextOverflowDetection(
            isOverflow = false,
            source = "skipped_for_$firstErrorCategory",
            reason = "only provider/context failures are eligible for overflow recovery",
          )
        }
        if (overflowDetection.isOverflow) {
          val interruptedRun = runState.interruptedRunHandoff(
            originalInput = trimmed,
            error = firstError,
            failureStage = "provider_request",
          )
          val compressionStartedEvent = lifecycleRunEvent(
            type = AgentRunEventType.COMPRESSION_STARTED,
            title = "Context overflow detected",
            detail = "source=${overflowDetection.source}, reason=${overflowDetection.reason.take(TIMELINE_DETAIL_CHARS)}",
            status = AGENT_RUN_STATUS_RUNNING,
          )
          onRunEvent(compressionStartedEvent)
          runState.emit(compressionStartedEvent)
          val recoveryRecord = estimateContextUsage(trimmed, visibleTrimmed, settings, currentSession, workspace).copy(
            id = UUID.randomUUID().toString(),
            source = "agent_run_overflow_recovery",
            contextBudgetStatus = AgentContextBudget.STATUS_COMPRESSION_RECOMMENDED,
            contextBudgetReason = "Provider reported a likely context or token overflow during this run.",
          )
          val (preparedSession, compressedRecord) = prepareCompressionRestartSession(
            source = currentSession,
            baseRecord = recoveryRecord,
            interruptedRun = interruptedRun,
          )
          currentSession = preparedSession
          val compressionCompletedEvent = lifecycleRunEvent(
            type = AgentRunEventType.COMPRESSION_COMPLETED,
            title = "Context overflow compressed",
            detail = buildString {
              append("summarySource=${compressedRecord.summarySource.ifBlank { "local" }}")
              if (compressedRecord.compressionError.isNotBlank()) {
                append(", error=${compressedRecord.compressionError.take(TIMELINE_DETAIL_CHARS)}")
              }
            },
            status = AGENT_RUN_STATUS_COMPLETED,
          )
          onRunEvent(compressionCompletedEvent)
          val retryBaseEvents = runState.persistedTimelineEvents(
            listOfNotNull(compressionCompletedEvent.timelineEvent),
          ) + thinkingTimelineEvent("Retrying the interrupted request from the compressed handoff.")
          runState.resetForRecoveryRetry(
            baseTimelineEvents = retryBaseEvents,
            statusContent = "Retrying from compressed handoff...",
          )
          onSessionUpdated(currentSession, runState.draftMessage())
          recorder = newRecorder(currentSession)
          saveRunningCheckpoint(currentSession, recorder)
          result = runRuntime(currentSession, recorder)
        }
      }

      val assistantMessage = result.fold(
        onSuccess = { output ->
          saveRunCheckpoint(
            status = AGENT_RUN_STATUS_COMPLETED,
            agentRunId = agentRunId,
            startedAtMillis = startedAtMillis,
            settings = settings,
            session = currentSession,
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
            transcriptEvents = runState.finalTranscriptEvents(persistedEvents, finalContent, "assistant"),
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
            session = currentSession,
            input = trimmed,
            toolEvents = events,
            workspace = workspace,
          )
          latestCheckpointPath = saveRunCheckpoint(
            status = AGENT_RUN_STATUS_FAILED,
            agentRunId = agentRunId,
            startedAtMillis = startedAtMillis,
            settings = settings,
            session = currentSession,
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
            transcriptEvents = runState.finalTranscriptEvents(persistedEvents, message, "error"),
          )
        },
      )
      val updated = appendMessage(currentSession, assistantMessage)
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
    private val additionalTranscriptEvents: () -> List<ConversationTranscriptEvent>,
    private val onDraft: (SessionMessage) -> Unit,
  ) {
    private var baseTimelineEvents: List<AgentRunTimelineEvent> = baseTimelineEvents
    private var latestToolEvents: List<ToolEvent> = emptyList()
    private val modelText = StringBuilder()
    private var modelTextStreamingStarted: Boolean = false
    private data class ChronoEntry(
      val timestampMillis: Long,
      val sequence: Long,
      val text: String = "",
      val tool: ToolEvent? = null,
    )
    private val chronoEntries = mutableListOf<ChronoEntry>()
    private var previousToolSnapshotSize: Int = 0
    private var nextChronoSequence: Long = 0
    private var lastDraftEmittedAtMillis: Long = 0
    private var lastDraftEmittedCharCount: Int = 0

    fun replaceBaseTimeline(events: List<AgentRunTimelineEvent>, statusContent: String) {
      this.baseTimelineEvents = events
      this.statusContent = statusContent
      emitDraft(force = true)
    }

    fun emit(event: AgentRunEvent) {
      var forceDraft = false
      when (event.type) {
        AgentRunEventType.TOOL_EVENTS_CHANGED -> {
          val newTools = event.toolEvents.drop(previousToolSnapshotSize)
          latestToolEvents = event.toolEvents
          for (tool in newTools) {
            chronoEntries += ChronoEntry(
              timestampMillis = tool.timestampMillis,
              sequence = nextChronoSequence++,
              tool = tool,
            )
          }
          previousToolSnapshotSize = event.toolEvents.size
          forceDraft = true
        }

        AgentRunEventType.MODEL_TEXT_DELTA,
        AgentRunEventType.FINAL_TEXT_DELTA -> {
          val delta = event.modelTextDelta.ifEmpty { event.finalTextDelta }
          if (delta.isNotEmpty()) {
            val wasStreaming = modelTextStreamingStarted
            modelTextStreamingStarted = true
            modelText.append(delta)
            appendChronoText(delta, event.timestampMillis)
            forceDraft = !wasStreaming
          }
        }

        else -> {
          event.timelineEvent?.let { timelineEvent ->
            baseTimelineEvents = baseTimelineEvents + timelineEvent
            forceDraft = true
          }
        }
      }
      if (forceDraft || shouldEmitStreamingDraft()) {
        emitDraft(force = forceDraft)
      }
    }

    private fun appendChronoText(delta: String, timestampMillis: Long) {
      val lastIndex = chronoEntries.lastIndex
      val last = chronoEntries.getOrNull(lastIndex)
      if (last != null && last.tool == null && last.text.isNotEmpty()) {
        chronoEntries[lastIndex] = last.copy(text = last.text + delta)
      } else {
        chronoEntries += ChronoEntry(
          timestampMillis = timestampMillis,
          sequence = nextChronoSequence++,
          text = delta,
        )
      }
    }

    private fun shouldEmitStreamingDraft(): Boolean {
      if (!modelTextStreamingStarted) return true
      val now = System.currentTimeMillis()
      val charsSinceLastDraft = modelText.length - lastDraftEmittedCharCount
      return charsSinceLastDraft >= STREAMING_DRAFT_MIN_CHAR_DELTA ||
        now - lastDraftEmittedAtMillis >= STREAMING_DRAFT_MIN_INTERVAL_MS
    }

    private fun emitDraft(force: Boolean = false) {
      if (!force && !shouldEmitStreamingDraft()) return
      lastDraftEmittedAtMillis = System.currentTimeMillis()
      lastDraftEmittedCharCount = modelText.length
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
        transcriptEvents = buildTranscriptEvents(role = "assistant", timelineEvents = timelineEvents),
      )
    }

    fun interruptedRunHandoff(
      originalInput: String,
      error: Throwable,
      failureStage: String,
    ): InterruptedRunHandoff {
      val timelineEvents = draftTimelineEvents()
      return InterruptedRunHandoff(
        originalInput = originalInput,
        assistantDraft = draftContent(),
        toolEvents = latestToolEvents,
        runEvents = timelineEvents,
        transcriptEvents = buildTranscriptEvents(role = "assistant", timelineEvents = timelineEvents),
        failureStage = failureStage,
        providerError = error.message ?: error.toString(),
        recoveryInstruction = "Retry the same user request from this handoff. Treat completed tool results as known facts and avoid repeating completed tool work unless verification requires it.",
      )
    }

    fun resetForRecoveryRetry(
      baseTimelineEvents: List<AgentRunTimelineEvent>,
      statusContent: String,
    ) {
      this.baseTimelineEvents = baseTimelineEvents
      this.statusContent = statusContent
      latestToolEvents = emptyList()
      modelText.clear()
      modelTextStreamingStarted = false
      chronoEntries.clear()
      previousToolSnapshotSize = 0
      nextChronoSequence = 0
      lastDraftEmittedAtMillis = 0
      lastDraftEmittedCharCount = 0
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

    fun buildTranscriptEvents(
      role: String,
      timelineEvents: List<AgentRunTimelineEvent>,
      finalContent: String = "",
      includeFallbackContent: Boolean = false,
    ): List<ConversationTranscriptEvent> {
      val baseEvents = buildTranscriptStatusEvents(baseTimelineEvents, role)
      val dynamicEvents = buildTranscriptStatusEvents(
        timelineEvents = timelineEvents.drop(baseTimelineEvents.size),
        role = role,
      )
      val runningStreamingEvents = dynamicEvents.filter { it.isRunningStreamingStatus() }
      val trailingStatusEvents = dynamicEvents.filterNot { it.isRunningStreamingStatus() }
      val events = baseEvents.toMutableList()
      val chronologicalEvents = additionalTranscriptEvents().toMutableList()

      var pendingText = StringBuilder()
      var pendingTextTimestampMillis: Long? = null
      for (entry in orderedChronoEntries()) {
        if (entry.tool != null) {
          if (pendingText.isNotEmpty()) {
            chronologicalEvents += ConversationTranscriptEvent(
              type = "assistant_text",
              role = role,
              content = pendingText.toString(),
              timestampMillis = pendingTextTimestampMillis ?: entry.timestampMillis,
            )
            pendingText = StringBuilder()
            pendingTextTimestampMillis = null
          }
          val toolTimeline = buildToolTimelineEvents(listOf(entry.tool))
          val toolDetail = toolTimeline.firstOrNull()?.detail ?: "Tool: ${entry.tool.name}"
          chronologicalEvents += ConversationTranscriptEvent(
            type = "tool_call",
            title = "Tool: ${entry.tool.name}",
            detail = toolDetail,
            timestampMillis = entry.tool.timestampMillis,
            status = AGENT_RUN_STATUS_COMPLETED,
          )
        } else if (entry.text.isNotEmpty()) {
          if (pendingTextTimestampMillis == null) {
            pendingTextTimestampMillis = entry.timestampMillis
          }
          pendingText.append(entry.text)
        }
      }

      if (pendingText.isNotEmpty()) {
        chronologicalEvents += ConversationTranscriptEvent(
          type = "assistant_text",
          role = role,
          content = pendingText.toString(),
          timestampMillis = pendingTextTimestampMillis ?: System.currentTimeMillis(),
        )
      }
      events += chronologicalEvents.sortedWith(
        compareBy<ConversationTranscriptEvent> { it.timestampMillis }
          .thenBy { transcriptEventSortRank(it) },
      )
      events += runningStreamingEvents
      events += trailingStatusEvents

      if ((includeFallbackContent || role == "error") && finalContent.isNotBlank()) {
        events += ConversationTranscriptEvent(
          type = if (role == "error") "error_text" else "assistant_text",
          role = role,
          content = finalContent,
          timestampMillis = System.currentTimeMillis(),
        )
      }

      return events
    }

    private fun transcriptEventSortRank(event: ConversationTranscriptEvent): Int {
      return when (event.type) {
        "assistant_text",
        "error_text",
        "user_guidance",
        "user_text",
        "guidance" -> 0
        "tool_call" -> 2
        else -> 3
      }
    }

    private fun orderedChronoEntries(): List<ChronoEntry> {
      return chronoEntries.sortedWith(
        compareBy<ChronoEntry> { it.timestampMillis }
          .thenBy { it.sequence },
      )
    }

    private fun buildTranscriptStatusEvents(
      timelineEvents: List<AgentRunTimelineEvent>,
      role: String,
    ): List<ConversationTranscriptEvent> {
      return buildConversationTranscriptEvents(
        timelineEvents.filterNot { it.isChronoOwnedToolStatus() },
        "",
        role,
        false,
      )
    }

    private fun AgentRunTimelineEvent.isChronoOwnedToolStatus(): Boolean {
      return type == "tool_call" || type == "tool_omitted"
    }

    private fun ConversationTranscriptEvent.isRunningStreamingStatus(): Boolean {
      return (type == "assistant_text_streaming" || type == "final_response_streaming") &&
        status == AGENT_RUN_STATUS_RUNNING
    }

    fun finalTranscriptEvents(
      timelineEvents: List<AgentRunTimelineEvent>,
      finalContent: String,
      role: String,
    ): List<ConversationTranscriptEvent> {
      return buildTranscriptEvents(
        role = role,
        timelineEvents = timelineEvents,
        finalContent = finalContent,
        includeFallbackContent = !modelTextStreamingStarted,
      )
    }
  }

  private fun estimateContextUsage(
    input: String,
    visibleInput: String,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
  ): ContextUsageRecord {
    val recentHistory = RuntimeSessionHistory.entries(
      session = session,
      currentInput = input,
      currentVisibleInput = visibleInput,
    )
    val historyChars = recentHistory.sumOf { message ->
      message.role.length + message.content.length + 2
    }
    val footprint = AgentRequestFootprintBuilder.build(
      input = input,
      currentVisibleInput = visibleInput,
      settings = settings,
      session = session,
      workspace = workspace,
    )
    val estimatedRequestChars = footprint.requestChars
    val providerOverheadChars = (
      estimatedRequestChars -
        input.length -
        historyChars -
        footprint.rulesChars -
        footprint.toolSchemaChars
      ).coerceAtLeast(0)
    val tokenEstimate = AgentPayloadTokenEstimator.estimate(
      payloadJson = footprint.payloadJson,
      transportOverheadChars = footprint.transportOverheadChars,
      model = settings.model,
    )
    val provider = ModelProviderCatalog.findProvider(settings.provider)
    val modelContext = ModelProviderCatalog.contextFor(settings)
    val contextWindowTokens = modelContext.contextWindowTokens
    val budget = AgentContextBudget.evaluate(
      tokens = tokenEstimate.tokens,
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
      rulesChars = footprint.rulesChars,
      workspaceListingChars = 0,
      toolSchemaChars = footprint.toolSchemaChars,
      providerOverheadChars = providerOverheadChars,
      estimatedRequestChars = estimatedRequestChars,
      approximateTokens = tokenEstimate.tokens,
      modelContextWindowTokens = contextWindowTokens,
      modelContextSource = modelContext.source,
      tokenUsageSource = tokenEstimate.source,
      contextUsagePermille = budget.usagePermille,
      compressionThresholdPercent = modelContext.compressionThresholdPercent,
      contextBudgetStatus = budget.status,
      contextBudgetReason = budget.reason,
      compressed = false,
      summary = "No compression was applied for this run.",
    )
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
      "workspace_command_run" -> "Ran workspace command in ${toolArg(event.args, "cwd").ifBlank { "." }}"
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
