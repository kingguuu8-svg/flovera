package com.flovera.app.session

import android.content.Context
import com.flovera.app.storage.readUtf8Text
import com.flovera.app.storage.writeUtf8TextAtomically
import java.io.File
import java.util.LinkedHashMap
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
  val promptContextBlocks: List<PromptContextBlock> = emptyList(),
  @kotlinx.serialization.Transient
  val summaryOnly: Boolean = false,
)

@Serializable
data class SessionMessage(
  val role: String,
  val content: String,
  val timestampMillis: Long = System.currentTimeMillis(),
  val toolEvents: List<ToolEvent> = emptyList(),
  val runEvents: List<AgentRunTimelineEvent> = emptyList(),
  val transcriptEvents: List<ConversationTranscriptEvent> = emptyList(),
)

@Serializable
data class ToolEvent(
  val name: String,
  val args: String,
  val result: String,
  val timestampMillis: Long = System.currentTimeMillis(),
  val success: Boolean = true,
  val resultKind: String = "generic",
  val outputChars: Int = result.length,
  val outputTruncated: Boolean = false,
  val retentionPriority: String = ToolContextRetentionPolicy.RETENTION_RECENT_FULL,
  val retentionReason: String = "",
)

@Serializable
data class AgentRunTimelineEvent(
  val type: String,
  val title: String,
  val detail: String = "",
  val timestampMillis: Long = System.currentTimeMillis(),
  val status: String = "",
  val compact: Boolean = true,
)

@Serializable
data class ConversationTranscriptEvent(
  val type: String,
  val role: String = "",
  val content: String = "",
  val title: String = "",
  val detail: String = "",
  val timestampMillis: Long = System.currentTimeMillis(),
  val status: String = "",
  val compact: Boolean = true,
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
  val toolSchemaChars: Int = 0,
  val providerOverheadChars: Int = 0,
  val estimatedRequestChars: Int = 0,
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

@Serializable
data class PromptContextBlock(
  val id: String,
  val sourceMessageIndex: Int,
  val sourceTimestampMillis: Long,
  val runIndex: Int = -1,
  val role: String,
  val content: String,
  val createdAtMillis: Long = System.currentTimeMillis(),
  val retentionPriority: String = "",
  val origin: String = "message",
  val kind: String = "",
  val summaryOfBlockId: String = "",
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
  private val cacheLock = Any()
  private val sessionLocks = Array(SESSION_LOCK_STRIPES) { Any() }
  private val sessionCache = object : LinkedHashMap<String, CachedSession>(SESSION_CACHE_LIMIT + 1, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSession>?): Boolean {
      return size > SESSION_CACHE_LIMIT
    }
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
    return withSessionLock(id) { loadLocked(id) }
  }

  private fun loadLocked(id: String): AgentSession? {
    val file = fileFor(id)
    if (!file.exists()) {
      removeCached(id)
      return null
    }
    val modifiedAtMillis = file.lastModified()
    val lengthBytes = file.length()
    cached(id)?.takeIf {
      it.modifiedAtMillis == modifiedAtMillis && it.lengthBytes == lengthBytes
    }?.let { return it.session }
    val session = runCatching { json.decodeFromString<AgentSession>(readUtf8Text(file)) }.getOrNull()
    if (session != null) {
      cache(
        id,
        CachedSession(
        modifiedAtMillis = modifiedAtMillis,
        lengthBytes = lengthBytes,
        session = session,
        ),
      )
    }
    return session
  }

  fun list(includeArchived: Boolean = false): List<AgentSession> {
    if (!root.exists()) return emptyList()
    return root.listFiles { file -> file.extension == "json" }
      ?.mapNotNull(::loadNonEmptySession)
      ?.filter { includeArchived || it.archivedAtMillis == null }
      ?.sortedWith(sessionSort)
      ?: emptyList()
  }

  fun listSummaries(includeArchived: Boolean = false): List<AgentSession> {
    if (!root.exists()) return emptyList()
    return root.listFiles { file -> file.extension == "json" }
      ?.mapNotNull(::loadSessionSummary)
      ?.filter { includeArchived || it.archivedAtMillis == null }
      ?.sortedWith(sessionSort)
      ?: emptyList()
  }

  private fun loadNonEmptySession(file: File): AgentSession? {
    val id = file.nameWithoutExtension
    return withSessionLock(id) {
      val session = loadLocked(id) ?: return@withSessionLock null
      if (session.messages.isEmpty()) {
        deleteLocked(id)
        null
      } else {
        session
      }
    }
  }

  private fun loadSessionSummary(file: File): AgentSession? {
    val id = file.nameWithoutExtension
    return withSessionLock(id) {
      val cached = cached(id)
      if (cached != null &&
        cached.modifiedAtMillis == file.lastModified() &&
        cached.lengthBytes == file.length()
      ) {
        if (cached.session.messages.isEmpty()) {
          deleteLocked(id)
          return@withSessionLock null
        }
        return@withSessionLock cached.session.asSummary()
      }
      readSummaryHeader(file)?.let { header ->
        if (!header.hasMessages) {
          deleteLocked(id)
          return@withSessionLock null
        }
        return@withSessionLock summarySession(file, header.summary)
      }
      val content = runCatching { readUtf8Text(file) }.getOrNull() ?: return@withSessionLock null
      if (!serializedMessagesArePresent(content)) {
        val decoded = runCatching { json.decodeFromString<AgentSession>(content) }.getOrNull()
          ?: return@withSessionLock null
        if (decoded.messages.isEmpty()) {
          deleteLocked(id)
          return@withSessionLock null
        }
        cache(
          id,
          CachedSession(file.lastModified(), file.length(), decoded),
        )
        return@withSessionLock decoded.asSummary()
      }
      val summary = runCatching { json.decodeFromString<AgentSessionSummaryPayload>(content) }.getOrNull()
        ?: return@withSessionLock null
      summarySession(file, summary)
    }
  }

  private fun AgentSession.asSummary(): AgentSession {
    return copy(
      messages = emptyList(),
      contextRecords = emptyList(),
      promptContextBlocks = emptyList(),
      summaryOnly = true,
    )
  }

  fun listArchived(): List<AgentSession> = list(includeArchived = true)
    .filter { it.archivedAtMillis != null }
    .sortedByDescending { it.archivedAtMillis }

  fun rename(id: String, title: String): AgentSession? {
    val normalized = title.trim()
    if (normalized.isBlank()) return load(id)
    return withSessionLock(id) {
      val session = loadLocked(id) ?: return@withSessionLock null
      val updated = session.copy(
        title = normalized,
        updatedAtMillis = System.currentTimeMillis(),
      )
      saveLocked(updated)
      updated
    }
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
    return withSessionLock(id) {
      val session = loadLocked(id) ?: return@withSessionLock null
      val now = System.currentTimeMillis()
      val updated = session.copy(
        archivedAtMillis = now,
        pinnedAtMillis = null,
        updatedAtMillis = now,
      )
      saveLocked(updated)
      updated
    }
  }

  fun setPinned(id: String, pinned: Boolean): AgentSession? {
    return withSessionLock(id) {
      val session = loadLocked(id) ?: return@withSessionLock null
      if (session.archivedAtMillis != null) return@withSessionLock session
      val now = System.currentTimeMillis()
      val updated = session.copy(
        pinnedAtMillis = if (pinned) now else null,
        updatedAtMillis = now,
      )
      saveLocked(updated)
      updated
    }
  }

  fun restore(id: String): AgentSession? {
    return withSessionLock(id) {
      val session = loadLocked(id) ?: return@withSessionLock null
      val updated = session.copy(
        archivedAtMillis = null,
        updatedAtMillis = System.currentTimeMillis(),
      )
      saveLocked(updated)
      updated
    }
  }

  fun appendMessage(session: AgentSession, message: SessionMessage): AgentSession {
    return withSessionLock(session.id) {
      val latest = loadLocked(session.id) ?: session
      val backfilledBlocks = PromptContextLedger.withBackfilledBlocks(latest)
      val messageIndex = latest.messages.size
      val runIndex = PromptContextLedger.runIndexForMessage(latest.messages, message)
      val updated = latest.copy(
        updatedAtMillis = System.currentTimeMillis(),
        messages = latest.messages + message,
        promptContextBlocks = backfilledBlocks +
          PromptContextLedger.blocksForMessage(message, messageIndex, runIndex),
      )
      saveLocked(updated)
      updated
    }
  }

  fun appendPromptContextBlocks(session: AgentSession, blocks: List<PromptContextBlock>): AgentSession {
    if (blocks.isEmpty()) return load(session.id) ?: session
    return withSessionLock(session.id) {
      val latest = loadLocked(session.id) ?: session
      val updated = latest.copy(
        updatedAtMillis = System.currentTimeMillis(),
        promptContextBlocks = latest.promptContextBlocks + blocks,
      )
      saveLocked(updated)
      updated
    }
  }

  fun appendContextRecord(session: AgentSession, record: ContextUsageRecord): AgentSession {
    return withSessionLock(session.id) {
      val latest = loadLocked(session.id) ?: session
      val updated = latest.copy(
        updatedAtMillis = System.currentTimeMillis(),
        contextRecords = (latest.contextRecords + record).takeLast(CONTEXT_RECORD_LIMIT),
      )
      saveLocked(updated)
      updated
    }
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
    return withSessionLock(id) {
      val session = loadLocked(id) ?: return@withSessionLock null
      val normalizedCount = messageCount.coerceIn(0, session.messages.size)
      if (normalizedCount == 0) {
        deleteLocked(id)
        return@withSessionLock null
      }
      val updated = session.copy(
        updatedAtMillis = System.currentTimeMillis(),
        messages = session.messages.take(normalizedCount),
        promptContextBlocks = session.promptContextBlocks
          .filter { it.sourceMessageIndex < normalizedCount },
      )
      saveLocked(updated)
      updated
    }
  }

  fun save(session: AgentSession) {
    withSessionLock(session.id) { saveLocked(session) }
  }

  private fun saveLocked(session: AgentSession) {
    val file = fileFor(session.id)
    writeUtf8TextAtomically(file, json.encodeToString(session))
    cache(
      session.id,
      CachedSession(
        modifiedAtMillis = file.lastModified(),
        lengthBytes = file.length(),
        session = session,
      ),
    )
  }

  fun delete(id: String): Boolean {
    return withSessionLock(id) { deleteLocked(id) }
  }

  private fun deleteLocked(id: String): Boolean {
    removeCached(id)
    return fileFor(id).delete()
  }

  fun pruneEmptySessions() {
    if (!root.exists()) return
    root.listFiles { file -> file.extension == "json" }
      ?.forEach { file ->
        val id = file.nameWithoutExtension
        withSessionLock(id) {
          val session = loadLocked(id)
          if (session?.messages?.isEmpty() == true) deleteLocked(id)
        }
      }
  }

  private fun fileFor(id: String): File = File(root, "$id.json")

  private inline fun <T> withSessionLock(id: String, block: () -> T): T {
    val index = (id.hashCode() and Int.MAX_VALUE) % sessionLocks.size
    return synchronized(sessionLocks[index], block)
  }

  private fun cached(id: String): CachedSession? = synchronized(cacheLock) {
    sessionCache[id]
  }

  private fun cache(id: String, session: CachedSession) {
    synchronized(cacheLock) {
      sessionCache[id] = session
    }
  }

  private fun removeCached(id: String) {
    synchronized(cacheLock) {
      sessionCache.remove(id)
    }
  }

  internal fun cachedSessionCountForTest(): Int = synchronized(cacheLock) {
    sessionCache.size
  }

  private data class CachedSession(
    val modifiedAtMillis: Long,
    val lengthBytes: Long,
    val session: AgentSession,
  )

  @Serializable
  private data class AgentSessionSummaryPayload(
    val id: String = "",
    val title: String = "",
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val archivedAtMillis: Long? = null,
    val pinnedAtMillis: Long? = null,
  )

  private fun serializedMessagesArePresent(content: String): Boolean {
    val keyStart = content.indexOf("\"messages\"")
    if (keyStart < 0) return false
    val arrayStart = content.indexOf('[', keyStart)
    if (arrayStart < 0) return false
    var valueStart = arrayStart + 1
    while (valueStart < content.length && content[valueStart].isWhitespace()) valueStart += 1
    return valueStart < content.length && content[valueStart] != ']'
  }

  private fun readSummaryHeader(file: File): SummaryHeader? {
    val prefix = runCatching {
      file.inputStream().buffered().use { input ->
        val bytes = ByteArray(SESSION_SUMMARY_PREFIX_BYTES)
        val count = input.read(bytes)
        if (count <= 0) "" else String(bytes, 0, count, Charsets.UTF_8)
      }
    }.getOrNull() ?: return null
    val messagesKey = prefix.indexOf("\"messages\":")
    if (messagesKey < 0) return null
    val arrayStart = prefix.indexOf('[', messagesKey)
    if (arrayStart < 0) return null
    var valueStart = arrayStart + 1
    while (valueStart < prefix.length && prefix[valueStart].isWhitespace()) valueStart += 1
    if (valueStart >= prefix.length) return null
    val metadata = prefix.substring(0, messagesKey)
      .trimEnd()
      .removeSuffix(",")
      .plus("\n}")
    val summary = runCatching { json.decodeFromString<AgentSessionSummaryPayload>(metadata) }.getOrNull()
      ?: return null
    return SummaryHeader(summary = summary, hasMessages = prefix[valueStart] != ']')
  }

  private fun summarySession(file: File, summary: AgentSessionSummaryPayload): AgentSession {
    return AgentSession(
      id = summary.id.ifBlank { file.nameWithoutExtension },
      title = summary.title,
      createdAtMillis = summary.createdAtMillis,
      updatedAtMillis = summary.updatedAtMillis,
      archivedAtMillis = summary.archivedAtMillis,
      pinnedAtMillis = summary.pinnedAtMillis,
      summaryOnly = true,
    )
  }

  private companion object {
    const val CONTEXT_RECORD_LIMIT = 80
    const val SESSION_CACHE_LIMIT = 2
    const val SESSION_LOCK_STRIPES = 16
    const val SESSION_SUMMARY_PREFIX_BYTES = 64 * 1024

    val sessionSort = compareByDescending<AgentSession> { it.pinnedAtMillis != null }
      .thenByDescending { it.updatedAtMillis }
  }

  private data class SummaryHeader(
    val summary: AgentSessionSummaryPayload,
    val hasMessages: Boolean,
  )
}
