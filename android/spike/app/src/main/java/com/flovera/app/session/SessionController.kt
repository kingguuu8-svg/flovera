package com.flovera.app.session

class SessionController(private val store: AgentSessionStore) {
  fun initialSession(activeSessionId: String?): AgentSession? {
    store.pruneEmptySessions()
    val active = activeSessionId?.let { store.load(it) }
    return if (active != null && active.archivedAtMillis == null && active.messages.isNotEmpty()) {
      active
    } else {
      nextUsableSession()
    }
  }

  fun listActive(): List<AgentSession> = store.list()

  fun listArchived(): List<AgentSession> = store.listArchived()

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
    return store.list().firstOrNull()
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
