package com.flovera.app.session

import java.security.MessageDigest

object PromptContextLedger {
  private const val MESSAGE_CONTENT_LIMIT = 1_500
  private const val HANDOFF_CONTENT_LIMIT = 6_000
  private const val INTERRUPTED_CONTEXT_LIMIT = 3_000
  private const val INTERRUPTED_EVENT_LIMIT = 24
  private const val TRANSCRIPT_CONTEXT_LIMIT = 6_000
  private const val RUN_CONTEXT_LIMIT = 2_000
  private const val CONTEXT_EVENT_LIMIT = 48

  fun withBackfilledBlocks(session: AgentSession): List<PromptContextBlock> {
    val existingIndexes = session.promptContextBlocks
      .map { it.sourceMessageIndex }
      .toSet()
    val missing = session.messages.flatMapIndexed { index, message ->
      if (index in existingIndexes) emptyList() else blocksForMessage(message, index)
    }
    return session.promptContextBlocks + missing
  }

  fun blocksForMessage(message: SessionMessage, sourceMessageIndex: Int): List<PromptContextBlock> {
    return when {
      message.role == SESSION_ROLE_COMPRESSION -> listOfNotNull(
        message.toBlock(
          sourceMessageIndex = sourceMessageIndex,
          sequence = 0,
          role = "handoff_summary",
          content = message.content.normalized(HANDOFF_CONTENT_LIMIT),
          origin = "compression",
        ).takeIf { it.content.isNotBlank() },
      )
      !message.hasRuntimeHistoryPayload() -> emptyList()
      else -> buildList {
        message.toBlock(
          sourceMessageIndex = sourceMessageIndex,
          sequence = size,
          role = message.role,
          content = message.content.normalized(MESSAGE_CONTENT_LIMIT),
          origin = "message",
        ).takeIf { it.content.isNotBlank() }?.let(::add)
        if (message.hasInterruptedRun()) {
          add(
            message.toBlock(
              sourceMessageIndex = sourceMessageIndex,
              sequence = size,
              role = "interrupted_run_context",
              content = message.buildInterruptedRunContext().normalized(INTERRUPTED_CONTEXT_LIMIT),
              origin = "transcript",
              retentionPriority = ToolContextRetentionPolicy.RETENTION_ACTIVE_CRITICAL,
            ),
          )
        }
        message.transcriptContext().takeIf { it.isNotBlank() }?.let { context ->
          add(
            message.toBlock(
              sourceMessageIndex = sourceMessageIndex,
              sequence = size,
              role = "transcript_context",
              content = context.normalized(TRANSCRIPT_CONTEXT_LIMIT),
              origin = "transcript",
            ),
          )
        }
        message.runContext().takeIf { it.isNotBlank() }?.let { context ->
          add(
            message.toBlock(
              sourceMessageIndex = sourceMessageIndex,
              sequence = size,
              role = "run_context",
              content = context.normalized(RUN_CONTEXT_LIMIT),
              origin = "run",
            ),
          )
        }
        ToolContextRetentionPolicy.ledgerSlicesForMessage(message).forEach { entry ->
          add(
            message.toBlock(
              sourceMessageIndex = sourceMessageIndex,
              sequence = size,
              role = entry.role,
              content = entry.content,
              origin = "tool",
            ),
          )
        }
      }
    }
  }

  fun promptBlocks(
    session: AgentSession,
    currentInput: String = "",
    currentVisibleInput: String = currentInput,
    maxMessages: Int,
  ): List<PromptContextBlock> {
    val blocks = withoutCurrentInputBlocks(
      session = session,
      blocks = withBackfilledBlocks(session),
      currentInput = currentInput,
      currentVisibleInput = currentVisibleInput,
    )
    if (blocks.isEmpty()) return emptyList()
    val dividerIndex = blocks.indexOfLast { it.role == "handoff_summary" }
    if (dividerIndex < 0) {
      return blocks.takeLastMessageIndexes(maxMessages)
    }
    val divider = blocks[dividerIndex]
    val afterDivider = blocks
      .drop(dividerIndex + 1)
      .filterNot { it.role == "handoff_summary" }
      .takeLastMessageIndexes((maxMessages - 1).coerceAtLeast(0))
    return listOf(divider) + afterDivider
  }

  private fun withoutCurrentInputBlocks(
    session: AgentSession,
    blocks: List<PromptContextBlock>,
    currentInput: String,
    currentVisibleInput: String,
  ): List<PromptContextBlock> {
    val normalizedInput = currentInput.trim()
    val normalizedVisibleInput = currentVisibleInput.trim()
    if (normalizedInput.isBlank() && normalizedVisibleInput.isBlank()) return blocks
    val lastIndex = session.messages.lastIndex
    val last = session.messages.lastOrNull() ?: return blocks
    if (last.role != "user") return blocks
    val lastContent = last.content.trim()
    val isCurrentInput = lastContent == normalizedInput ||
      lastContent == normalizedVisibleInput ||
      (lastContent.isNotBlank() && normalizedInput.contains(lastContent))
    return if (isCurrentInput) {
      blocks.filterNot { it.sourceMessageIndex == lastIndex }
    } else {
      blocks
    }
  }

  private fun List<PromptContextBlock>.takeLastMessageIndexes(maxMessages: Int): List<PromptContextBlock> {
    if (maxMessages <= 0) return emptyList()
    val retainedIndexes = asReversed()
      .map { it.sourceMessageIndex }
      .distinct()
      .take(maxMessages)
      .toSet()
    return filter { it.sourceMessageIndex in retainedIndexes }
  }

  private fun SessionMessage.toBlock(
    sourceMessageIndex: Int,
    sequence: Int,
    role: String,
    content: String,
    origin: String,
    retentionPriority: String = "",
  ): PromptContextBlock {
    val normalizedContent = content.trim()
    return PromptContextBlock(
      id = "m$sourceMessageIndex-${timestampMillis}-$role-$sequence-${stableHash(normalizedContent)}",
      sourceMessageIndex = sourceMessageIndex,
      sourceTimestampMillis = timestampMillis,
      role = role,
      content = normalizedContent,
      retentionPriority = retentionPriority,
      origin = origin,
    )
  }

  private fun SessionMessage.hasRuntimeHistoryPayload(): Boolean {
    return content.isNotBlank() ||
      toolEvents.isNotEmpty() ||
      transcriptContext().isNotBlank() ||
      runContext().isNotBlank() ||
      hasInterruptedRun()
  }

  private fun SessionMessage.hasInterruptedRun(): Boolean {
    return runEvents.any { it.type == "run_interrupted" } ||
      transcriptEvents.any { it.type == "run_interrupted" }
  }

  private fun SessionMessage.buildInterruptedRunContext(): String {
    val transcript = transcriptEvents
      .filterNot { it.type == "thinking" }
      .takeLast(INTERRUPTED_EVENT_LIMIT)
    val source = if (transcript.isNotEmpty()) {
      transcript.map { event ->
        when {
          event.content.isNotBlank() -> "${event.type}:${event.role.ifBlank { "status" }}:${event.content}"
          event.detail.isNotBlank() -> "${event.type}:${event.title}:${event.detail}"
          event.title.isNotBlank() -> "${event.type}:${event.title}"
          else -> event.type
        }
      }
    } else {
      runEvents.takeLast(INTERRUPTED_EVENT_LIMIT).map { event ->
        when {
          event.detail.isNotBlank() -> "${event.type}:${event.title}:${event.detail}"
          event.title.isNotBlank() -> "${event.type}:${event.title}"
          else -> event.type
        }
      }
    }
    return buildString {
      append("Interrupted assistant run visible in conversation UI. ")
      append("toolCallCount=")
      append(toolEvents.size)
      append(". Timeline: ")
      append(source.joinToString(" | "))
    }
  }

  private fun SessionMessage.transcriptContext(): String {
    val events = transcriptEvents
      .filter { it.isPromptRelevantTranscriptEvent() }
      .takeLast(CONTEXT_EVENT_LIMIT)
    if (events.isEmpty()) return ""
    return events.joinToString(" | ") { event ->
      when {
        event.content.isNotBlank() -> "${event.type}:${event.role.ifBlank { "status" }}:${event.content}"
        event.detail.isNotBlank() -> "${event.type}:${event.title}:${event.detail}"
        event.title.isNotBlank() -> "${event.type}:${event.title}"
        else -> event.type
      }
    }
  }

  private fun SessionMessage.runContext(): String {
    val events = runEvents
      .filter { it.isPromptRelevantRunEvent() }
      .takeLast(CONTEXT_EVENT_LIMIT)
    if (events.isEmpty()) return ""
    return events.joinToString(" | ") { event ->
      when {
        event.detail.isNotBlank() -> "${event.type}:${event.title}:${event.detail}"
        event.title.isNotBlank() -> "${event.type}:${event.title}"
        else -> event.type
      }
    }
  }

  private fun ConversationTranscriptEvent.isPromptRelevantTranscriptEvent(): Boolean {
    return when (type) {
      "assistant_text",
      "error_text",
      "user_guidance",
      "user_text",
      "guidance",
      "tool_call",
      "tool_omitted",
      "run_failed",
      "run_interrupted" -> true
      else -> false
    }
  }

  private fun AgentRunTimelineEvent.isPromptRelevantRunEvent(): Boolean {
    return when (type) {
      "run_failed",
      "run_interrupted",
      "compression_started",
      "compression_completed" -> true
      else -> false
    }
  }

  private fun String.normalized(limit: Int): String {
    val normalized = lineSequence()
      .joinToString(" ") { it.trim() }
      .replace(Regex("\\s+"), " ")
      .trim()
    if (normalized.length <= limit) return normalized
    return normalized.take(limit).trimEnd() + "..."
  }

  private fun stableHash(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { "%02x".format(it) }
  }
}
