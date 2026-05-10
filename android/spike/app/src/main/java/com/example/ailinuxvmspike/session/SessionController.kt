package com.example.ailinuxvmspike.session

class SessionController(private val store: AgentSessionStore) {
  fun initialSession(activeSessionId: String?): AgentSession {
    val active = activeSessionId?.let { store.load(it) }
    return if (active?.archivedAtMillis == null) {
      active ?: nextUsableSession(defaultTitle = "Default")
    } else {
      nextUsableSession(defaultTitle = "Default")
    }
  }

  fun listActive(): List<AgentSession> = store.list()

  fun listArchived(): List<AgentSession> = store.listArchived()

  fun createSession(): AgentSession {
    return store.create("Session ${store.list().size + 1}")
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

  fun revertToBeforeMessage(sessionId: String, messageIndex: Int): AgentSession? {
    return store.truncateMessages(sessionId, messageIndex)
  }

  fun nextUsableSession(defaultTitle: String = "Default"): AgentSession {
    return store.list().firstOrNull() ?: store.create(defaultTitle)
  }
}
