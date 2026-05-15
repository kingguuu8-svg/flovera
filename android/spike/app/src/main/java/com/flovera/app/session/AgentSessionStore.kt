package com.flovera.app.session

import android.content.Context
import com.flovera.app.storage.readUtf8Text
import com.flovera.app.storage.writeUtf8TextAtomically
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SESSION_ROLE_COMPRESSION = "compression"

@Serializable
data class AgentSession(
  val id: String,
  val title: String,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
  val archivedAtMillis: Long? = null,
  val pinnedAtMillis: Long? = null,
  val messages: List<SessionMessage> = emptyList(),
  val contextRecords: List<ContextUsageRecord> = emptyList(),
)

@Serializable
data class SessionMessage(
  val role: String,
  val content: String,
  val timestampMillis: Long = System.currentTimeMillis(),
  val toolEvents: List<ToolEvent> = emptyList(),
)

@Serializable
data class ToolEvent(
  val name: String,
  val args: String,
  val result: String,
  val timestampMillis: Long = System.currentTimeMillis(),
)

@Serializable
data class ContextUsageRecord(
  val id: String,
  val source: String,
  val createdAtMillis: Long = System.currentTimeMillis(),
  val provider: String = "",
  val model: String = "",
  val messageCount: Int,
  val inputChars: Int,
  val historyChars: Int,
  val rulesChars: Int,
  val workspaceListingChars: Int,
  val approximateTokens: Int,
  val modelContextWindowTokens: Int? = null,
  val modelContextSource: String = "unknown",
  val tokenUsageSource: String = "estimate",
  val contextUsagePermille: Int? = null,
  val compressionThresholdPercent: Int? = null,
  val contextBudgetStatus: String = "unknown",
  val contextBudgetReason: String = "",
  val compressed: Boolean = false,
  val summary: String = "",
  val summarySource: String = "",
  val compressionError: String = "",
)

class AgentSessionStore(
  context: Context,
  private val root: File = File(context.filesDir, "sessions"),
) {
  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun create(title: String = "New session"): AgentSession {
    val session = draft(title)
    save(session)
    return session
  }

  fun draft(title: String = "New session"): AgentSession {
    val now = System.currentTimeMillis()
    return AgentSession(
      id = UUID.randomUUID().toString(),
      title = title,
      createdAtMillis = now,
      updatedAtMillis = now,
    )
  }

  fun load(id: String): AgentSession? {
    val file = fileFor(id)
    if (!file.exists()) return null
    return runCatching { json.decodeFromString<AgentSession>(readUtf8Text(file)) }.getOrNull()
  }

  fun list(includeArchived: Boolean = false): List<AgentSession> {
    pruneEmptySessions()
    if (!root.exists()) return emptyList()
    return root.listFiles { file -> file.extension == "json" }
      ?.mapNotNull { runCatching { json.decodeFromString<AgentSession>(readUtf8Text(it)) }.getOrNull() }
      ?.filter { includeArchived || it.archivedAtMillis == null }
      ?.sortedWith(sessionSort)
      ?: emptyList()
  }

  fun listArchived(): List<AgentSession> = list(includeArchived = true)
    .filter { it.archivedAtMillis != null }
    .sortedByDescending { it.archivedAtMillis }

  fun rename(id: String, title: String): AgentSession? {
    val normalized = title.trim()
    if (normalized.isBlank()) return load(id)
    val session = load(id) ?: return null
    val updated = session.copy(
      title = normalized,
      updatedAtMillis = System.currentTimeMillis(),
    )
    save(updated)
    return updated
  }

  fun duplicate(id: String): AgentSession? {
    val source = load(id) ?: return null
    val now = System.currentTimeMillis()
    val copy = source.copy(
      id = UUID.randomUUID().toString(),
      title = "${source.title} copy",
      createdAtMillis = now,
      updatedAtMillis = now,
      archivedAtMillis = null,
      pinnedAtMillis = null,
      messages = source.messages,
    )
    save(copy)
    return copy
  }

  fun archive(id: String): AgentSession? {
    val session = load(id) ?: return null
    val now = System.currentTimeMillis()
    val updated = session.copy(
      archivedAtMillis = now,
      pinnedAtMillis = null,
      updatedAtMillis = now,
    )
    save(updated)
    return updated
  }

  fun setPinned(id: String, pinned: Boolean): AgentSession? {
    val session = load(id) ?: return null
    if (session.archivedAtMillis != null) return session
    val now = System.currentTimeMillis()
    val updated = session.copy(
      pinnedAtMillis = if (pinned) now else null,
      updatedAtMillis = now,
    )
    save(updated)
    return updated
  }

  fun restore(id: String): AgentSession? {
    val session = load(id) ?: return null
    val updated = session.copy(
      archivedAtMillis = null,
      updatedAtMillis = System.currentTimeMillis(),
    )
    save(updated)
    return updated
  }

  fun appendMessage(session: AgentSession, message: SessionMessage): AgentSession {
    val updated = session.copy(
      updatedAtMillis = System.currentTimeMillis(),
      messages = session.messages + message,
    )
    save(updated)
    return updated
  }

  fun appendContextRecord(session: AgentSession, record: ContextUsageRecord): AgentSession {
    val updated = session.copy(
      updatedAtMillis = System.currentTimeMillis(),
      contextRecords = (session.contextRecords + record).takeLast(CONTEXT_RECORD_LIMIT),
    )
    save(updated)
    return updated
  }

  fun appendCompressionDivider(session: AgentSession, record: ContextUsageRecord): AgentSession {
    return appendCompressionDivider(session, record, SessionHandoffSummarizer.summarize(session, record))
  }

  fun appendCompressionDivider(session: AgentSession, record: ContextUsageRecord, summary: String): AgentSession {
    return appendMessage(
      session,
      SessionMessage(
        role = SESSION_ROLE_COMPRESSION,
        content = buildString {
          appendLine("Context compressed")
          appendLine()
          appendLine("- recordId: ${record.id}")
          appendLine("- provider: ${record.provider}")
          appendLine("- model: ${record.model}")
          appendLine("- approximateTokens: ${record.approximateTokens}")
          appendLine("- budgetStatus: ${record.contextBudgetStatus}")
          appendLine("- summarySource: ${record.summarySource.ifBlank { "local" }}")
          if (record.compressionError.isNotBlank()) {
            appendLine("- compressionError: ${record.compressionError}")
          }
          appendLine()
          append(summary.trim().ifBlank { "No handoff summary was provided." })
        },
      ),
    )
  }

  fun truncateMessages(id: String, messageCount: Int): AgentSession? {
    val session = load(id) ?: return null
    val normalizedCount = messageCount.coerceIn(0, session.messages.size)
    if (normalizedCount == 0) {
      delete(id)
      return null
    }
    val updated = session.copy(
      updatedAtMillis = System.currentTimeMillis(),
      messages = session.messages.take(normalizedCount),
    )
    save(updated)
    return updated
  }

  fun save(session: AgentSession) {
    writeUtf8TextAtomically(fileFor(session.id), json.encodeToString(session))
  }

  fun delete(id: String): Boolean = fileFor(id).delete()

  fun pruneEmptySessions() {
    if (!root.exists()) return
    root.listFiles { file -> file.extension == "json" }
      ?.forEach { file ->
        val session = runCatching { json.decodeFromString<AgentSession>(readUtf8Text(file)) }.getOrNull()
        if (session?.messages?.isEmpty() == true) file.delete()
      }
  }

  private fun fileFor(id: String): File = File(root, "$id.json")

  private companion object {
    const val CONTEXT_RECORD_LIMIT = 80

    val sessionSort = compareByDescending<AgentSession> { it.pinnedAtMillis != null }
      .thenByDescending { it.updatedAtMillis }
  }
}
