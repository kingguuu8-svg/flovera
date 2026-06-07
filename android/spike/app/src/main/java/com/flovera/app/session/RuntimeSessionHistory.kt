package com.flovera.app.session

data class RuntimeHistoryEntry(
  val role: String,
  val content: String,
)

object RuntimeSessionHistory {
  private const val DEFAULT_MAX_MESSAGES = 12
  private const val MESSAGE_CONTENT_LIMIT = 1_500
  private const val HANDOFF_CONTENT_LIMIT = 6_000
  private const val INTERRUPTED_CONTEXT_LIMIT = 3_000
  private const val INTERRUPTED_EVENT_LIMIT = 24

  fun entries(
    session: AgentSession,
    currentInput: String = "",
    currentVisibleInput: String = currentInput,
    maxMessages: Int = DEFAULT_MAX_MESSAGES,
  ): List<RuntimeHistoryEntry> {
    val messages = withoutCurrentInput(session.messages, currentInput, currentVisibleInput)
      .filter { it.role == SESSION_ROLE_COMPRESSION || it.hasRuntimeHistoryPayload() }
    val dividerIndex = messages.indexOfLast { it.role == SESSION_ROLE_COMPRESSION }
    if (dividerIndex < 0) {
      val retained = messages
        .takeLast(maxMessages)
      return retained.toRuntimeEntries()
    }

    val divider = messages[dividerIndex]
    val afterDivider = messages
      .drop(dividerIndex + 1)
      .filterNot { it.role == SESSION_ROLE_COMPRESSION }
      .takeLast((maxMessages - 1).coerceAtLeast(0))

    return listOf(
      RuntimeHistoryEntry(
        role = "handoff_summary",
        content = divider.content.normalized(HANDOFF_CONTENT_LIMIT),
      ),
    ) + afterDivider.toRuntimeEntries()
  }

  fun promptText(
    session: AgentSession,
    currentInput: String = "",
    currentVisibleInput: String = currentInput,
    maxMessages: Int = DEFAULT_MAX_MESSAGES,
  ): String {
    return entries(
      session = session,
      currentInput = currentInput,
      currentVisibleInput = currentVisibleInput,
      maxMessages = maxMessages,
    ).joinToString("\n") { entry ->
      "${entry.role}: ${entry.content}"
    }
  }

  private fun withoutCurrentInput(
    messages: List<SessionMessage>,
    currentInput: String,
    currentVisibleInput: String,
  ): List<SessionMessage> {
    val normalizedInput = currentInput.trim()
    val normalizedVisibleInput = currentVisibleInput.trim()
    if (normalizedInput.isBlank() && normalizedVisibleInput.isBlank()) return messages
    val last = messages.lastOrNull() ?: return messages
    if (last.role != "user") return messages
    val lastContent = last.content.trim()
    // Wrapped guidance model inputs include the visible guidance inside an internal instruction block.
    return if (
      lastContent == normalizedInput ||
      lastContent == normalizedVisibleInput ||
      (lastContent.isNotBlank() && normalizedInput.contains(lastContent))
    ) {
      messages.dropLast(1)
    } else {
      messages
    }
  }

  private fun SessionMessage.toRuntimeEntry(): RuntimeHistoryEntry {
    return RuntimeHistoryEntry(
      role = role,
      content = content.normalized(MESSAGE_CONTENT_LIMIT),
    )
  }

  private fun SessionMessage.hasRuntimeHistoryPayload(): Boolean {
    return content.isNotBlank() || toolEvents.isNotEmpty() || hasInterruptedRun()
  }

  private fun List<SessionMessage>.toRuntimeEntries(): List<RuntimeHistoryEntry> {
    return flatMapIndexed { index, message ->
      val distanceFromNewestMessage = lastIndex - index
      listOfNotNull(message.toRuntimeEntry().takeIf { it.content.isNotBlank() }) +
        message.interruptedRunContextEntry() +
        ToolContextRetentionPolicy.slicesForMessage(message, distanceFromNewestMessage)
    }
  }

  private fun SessionMessage.hasInterruptedRun(): Boolean {
    return runEvents.any { it.type == "run_interrupted" } ||
      transcriptEvents.any { it.type == "run_interrupted" }
  }

  private fun SessionMessage.interruptedRunContextEntry(): List<RuntimeHistoryEntry> {
    if (!hasInterruptedRun()) return emptyList()
    return listOf(
      RuntimeHistoryEntry(
        role = "interrupted_run_context",
        content = buildInterruptedRunContext().normalized(INTERRUPTED_CONTEXT_LIMIT),
      ),
    )
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

  private fun String.normalized(limit: Int): String {
    val normalized = lineSequence()
      .joinToString(" ") { it.trim() }
      .replace(Regex("\\s+"), " ")
      .trim()
    if (normalized.length <= limit) return normalized
    return normalized.take(limit).trimEnd() + "..."
  }
}
