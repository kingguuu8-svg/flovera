package com.flovera.app.session

data class SessionLists(
  val active: List<AgentSession>,
  val archived: List<AgentSession>,
)

class SessionController(private val store: AgentSessionStore) {
  fun initialSessionForStartup(activeSessionId: String?): AgentSession? {
    return activeSessionId
      ?.let(store::load)
      ?.takeIf { it.archivedAtMillis == null && it.messages.isNotEmpty() }
  }

  fun initialSession(activeSessionId: String?): AgentSession? {
    val sessions = listState()
    return sessions.active.firstOrNull { it.id == activeSessionId && it.messages.isNotEmpty() }
      ?: sessions.active.firstOrNull()
  }

  fun listState(): SessionLists {
    val all = store.list(includeArchived = true)
    return SessionLists(
      active = all.filter { it.archivedAtMillis == null },
      archived = all
        .filter { it.archivedAtMillis != null }
        .sortedByDescending { it.archivedAtMillis },
    )
  }

  fun listSummaryState(): SessionLists {
    val all = store.listSummaries(includeArchived = true)
    return SessionLists(
      active = all.filter { it.archivedAtMillis == null },
      archived = all
        .filter { it.archivedAtMillis != null }
        .sortedByDescending { it.archivedAtMillis },
    )
  }

  fun listActive(): List<AgentSession> = listState().active

  fun listArchived(): List<AgentSession> = listState().archived

  fun createSession(): AgentSession {
    return store.draft("New session")
  }

  fun openSession(sessionId: String): AgentSession? {
    val session = store.load(sessionId) ?: return null
    return session.takeIf { it.archivedAtMillis == null }
  }

  fun renameSession(sessionId: String, title: String): AgentSession? {
    return store.rename(sessionId, title)
  }

  fun duplicateSession(sessionId: String): AgentSession? {
    return store.duplicate(sessionId)
  }

  fun archiveSession(sessionId: String): AgentSession? {
    return store.archive(sessionId)
  }

  fun restoreSession(sessionId: String): AgentSession? {
    return store.restore(sessionId)
  }

  fun setSessionPinned(sessionId: String, pinned: Boolean): AgentSession? {
    return store.setPinned(sessionId, pinned)
  }

  fun appendMessage(session: AgentSession, message: SessionMessage): AgentSession {
    return store.appendMessage(session, message)
  }

  fun appendPromptContextBlocks(session: AgentSession, blocks: List<PromptContextBlock>): AgentSession {
    return store.appendPromptContextBlocks(session, blocks)
  }

  fun appendContextRecord(session: AgentSession, record: ContextUsageRecord): AgentSession {
    return store.appendContextRecord(session, record)
  }

  fun appendCompressionDivider(session: AgentSession, record: ContextUsageRecord, summary: String): AgentSession {
    return store.appendCompressionDivider(session, record, summary)
  }

  fun appendCompressionDivider(session: AgentSession, record: ContextUsageRecord): AgentSession {
    return store.appendCompressionDivider(session, record)
  }

  fun appendUserPrompt(session: AgentSession, prompt: String): AgentSession {
    val withPrompt = store.appendMessage(session, SessionMessage(role = "user", content = prompt))
    if (session.messages.isNotEmpty()) return withPrompt
    return store.rename(withPrompt.id, firstPromptTitle(prompt)) ?: withPrompt
  }

  fun revertToBeforeMessage(sessionId: String, messageIndex: Int): AgentSession? {
    return store.truncateMessages(sessionId, messageIndex)
  }

  fun nextUsableSession(): AgentSession? {
    val summary = listSummaryState().active.firstOrNull() ?: return null
    return openSession(summary.id)
  }

  private fun firstPromptTitle(prompt: String): String {
    val normalized = prompt
      .lineSequence()
      .joinToString(" ") { it.trim() }
      .replace(Regex("\\s+"), " ")
      .trim()
    return normalized.take(30).ifBlank { "Untitled session" }
  }
}
