package com.flovera.app.session

import android.content.Context
import com.flovera.app.storage.readUtf8Text
import com.flovera.app.storage.writeUtf8TextAtomically
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
  private val sessionCache = ConcurrentHashMap<String, CachedSession>()

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
    if (!file.exists()) {
      sessionCache.remove(id)
      return null
    }
    val modifiedAtMillis = file.lastModified()
    val lengthBytes = file.length()
    sessionCache[id]?.takeIf {
      it.modifiedAtMillis == modifiedAtMillis && it.lengthBytes == lengthBytes
    }?.let { return it.session }
    val session = runCatching { json.decodeFromString<AgentSession>(readUtf8Text(file)) }.getOrNull()
    if (session != null) {
      sessionCache[id] = CachedSession(
        modifiedAtMillis = modifiedAtMillis,
        lengthBytes = lengthBytes,
        session = session,
      )
    }
    return session
  }

  fun list(includeArchived: Boolean = false): List<AgentSession> {
    if (!root.exists()) return emptyList()
    return root.listFiles { file -> file.extension == "json" }
      ?.mapNotNull { file ->
        val session = load(file.nameWithoutExtension) ?: return@mapNotNull null
        if (session.messages.isEmpty()) {
          delete(session.id)
          return@mapNotNull null
        }
        session
      }
      ?.filter { includeArchived || it.archivedAtMillis == null }
      ?.sortedWith(sessionSort)
      ?: emptyList()
  }

  fun listSummaries(includeArchived: Boolean = false): List<AgentSession> {
    if (!root.exists()) return emptyList()
    return root.listFiles { file -> file.extension == "json" }
      ?.mapNotNull { file ->
        val cached = sessionCache[file.nameWithoutExtension]
        if (cached != null &&
          cached.modifiedAtMillis == file.lastModified() &&
          cached.lengthBytes == file.length()
        ) {
          if (cached.session.messages.isEmpty()) {
            delete(cached.session.id)
            return@mapNotNull null
          }
          return@mapNotNull cached.session.copy(
            messages = emptyList(),
            contextRecords = emptyList(),
            promptContextBlocks = emptyList(),
            summaryOnly = true,
          )
        }
        readSummaryHeader(file)?.let { header ->
          if (!header.hasMessages) {
            delete(header.summary.id.ifBlank { file.nameWithoutExtension })
            return@mapNotNull null
          }
          return@mapNotNull summarySession(file, header.summary)
        }
        val content = runCatching { readUtf8Text(file) }.getOrNull() ?: return@mapNotNull null
        if (!serializedMessagesArePresent(content)) {
          sessionCache.remove(file.nameWithoutExtension)
          file.delete()
          return@mapNotNull null
        }
        val summary = runCatching { json.decodeFromString<AgentSessionSummaryPayload>(content) }.getOrNull()
          ?: return@mapNotNull null
        summarySession(file, summary)
      }
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
    val latest = load(session.id) ?: session
    val backfilledBlocks = PromptContextLedger.withBackfilledBlocks(latest)
    val messageIndex = latest.messages.size
    val runIndex = PromptContextLedger.runIndexForMessage(latest.messages, message)
    val updated = latest.copy(
      updatedAtMillis = System.currentTimeMillis(),
      messages = latest.messages + message,
      promptContextBlocks = backfilledBlocks +
        PromptContextLedger.blocksForMessage(message, messageIndex, runIndex),
    )
    save(updated)
    return updated
  }

  fun appendPromptContextBlocks(session: AgentSession, blocks: List<PromptContextBlock>): AgentSession {
    if (blocks.isEmpty()) return load(session.id) ?: session
    val latest = load(session.id) ?: session
    val updated = latest.copy(
      updatedAtMillis = System.currentTimeMillis(),
      promptContextBlocks = latest.promptContextBlocks + blocks,
    )
    save(updated)
    return updated
  }

  fun appendContextRecord(session: AgentSession, record: ContextUsageRecord): AgentSession {
    val latest = load(session.id) ?: session
    val updated = latest.copy(
      updatedAtMillis = System.currentTimeMillis(),
      contextRecords = (latest.contextRecords + record).takeLast(CONTEXT_RECORD_LIMIT),
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
      promptContextBlocks = session.promptContextBlocks
        .filter { it.sourceMessageIndex < normalizedCount },
    )
    save(updated)
    return updated
  }

  fun save(session: AgentSession) {
    val file = fileFor(session.id)
    writeUtf8TextAtomically(file, json.encodeToString(session))
    sessionCache[session.id] = CachedSession(
      modifiedAtMillis = file.lastModified(),
      lengthBytes = file.length(),
      session = session,
    )
  }

  fun delete(id: String): Boolean {
    sessionCache.remove(id)
    return fileFor(id).delete()
  }

  fun pruneEmptySessions() {
    if (!root.exists()) return
    root.listFiles { file -> file.extension == "json" }
      ?.forEach { file ->
        val session = load(file.nameWithoutExtension)
        if (session?.messages?.isEmpty() == true) delete(session.id)
      }
  }

  private fun fileFor(id: String): File = File(root, "$id.json")

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
    const val SESSION_SUMMARY_PREFIX_BYTES = 64 * 1024

    val sessionSort = compareByDescending<AgentSession> { it.pinnedAtMillis != null }
      .thenByDescending { it.updatedAtMillis }
  }

  private data class SummaryHeader(
    val summary: AgentSessionSummaryPayload,
    val hasMessages: Boolean,
  )
}
