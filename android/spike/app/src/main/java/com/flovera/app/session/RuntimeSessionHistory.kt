package com.flovera.app.session

data class RuntimeHistoryEntry(
  val role: String,
  val content: String,
)

object RuntimeSessionHistory {
  private const val DEFAULT_MAX_MESSAGES = 12
  private const val MESSAGE_CONTENT_LIMIT = 1_500
  private const val HANDOFF_CONTENT_LIMIT = 6_000

  fun entries(
    session: AgentSession,
    currentInput: String = "",
    maxMessages: Int = DEFAULT_MAX_MESSAGES,
  ): List<RuntimeHistoryEntry> {
    val messages = withoutCurrentInput(session.messages, currentInput)
      .filter { it.role == SESSION_ROLE_COMPRESSION || it.content.isNotBlank() }
    val dividerIndex = messages.indexOfLast { it.role == SESSION_ROLE_COMPRESSION }
    if (dividerIndex < 0) {
      return messages
        .takeLast(maxMessages)
        .map { it.toRuntimeEntry() }
    }

    val divider = messages[dividerIndex]
    val afterDivider = messages
      .drop(dividerIndex + 1)
      .filterNot { it.role == SESSION_ROLE_COMPRESSION }
      .takeLast((maxMessages - 1).coerceAtLeast(0))
      .map { it.toRuntimeEntry() }

    return listOf(
      RuntimeHistoryEntry(
        role = "handoff_summary",
        content = divider.content.normalized(HANDOFF_CONTENT_LIMIT),
      ),
    ) + afterDivider
  }

  fun promptText(
    session: AgentSession,
    currentInput: String = "",
    maxMessages: Int = DEFAULT_MAX_MESSAGES,
  ): String {
    return entries(
      session = session,
      currentInput = currentInput,
      maxMessages = maxMessages,
    ).joinToString("\n") { entry ->
      "${entry.role}: ${entry.content}"
    }
  }

  private fun withoutCurrentInput(messages: List<SessionMessage>, currentInput: String): List<SessionMessage> {
    val normalizedInput = currentInput.trim()
    if (normalizedInput.isBlank()) return messages
    val last = messages.lastOrNull() ?: return messages
    if (last.role != "user") return messages
    return if (last.content.trim() == normalizedInput) messages.dropLast(1) else messages
  }

  private fun SessionMessage.toRuntimeEntry(): RuntimeHistoryEntry {
    return RuntimeHistoryEntry(
      role = role,
      content = content.normalized(MESSAGE_CONTENT_LIMIT),
    )
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
