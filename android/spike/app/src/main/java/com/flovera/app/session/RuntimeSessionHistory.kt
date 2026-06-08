package com.flovera.app.session

data class RuntimeHistoryEntry(
  val role: String,
  val content: String,
)

object RuntimeSessionHistory {
  private const val DEFAULT_MAX_MESSAGES = 12

  fun entries(
    session: AgentSession,
    currentInput: String = "",
    currentVisibleInput: String = currentInput,
    maxMessages: Int = DEFAULT_MAX_MESSAGES,
  ): List<RuntimeHistoryEntry> {
    val ledgerEntries = PromptContextLedger.promptBlocks(
      session = session,
      currentInput = currentInput,
      currentVisibleInput = currentVisibleInput,
      maxMessages = maxMessages,
    ).map { block ->
      RuntimeHistoryEntry(
        role = block.role,
        content = block.content,
      )
    }
    if (ledgerEntries.isNotEmpty()) return ledgerEntries

    return withoutCurrentInput(session.messages, currentInput, currentVisibleInput)
      .filter { it.role != SESSION_ROLE_COMPRESSION && it.content.isNotEmpty() }
      .map { message ->
        RuntimeHistoryEntry(
          role = message.role,
          content = message.content,
        )
      }
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
}
