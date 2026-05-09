package com.example.ailinuxvmspike.session

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AgentSession(
  val id: String,
  val title: String,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
  val messages: List<SessionMessage> = emptyList(),
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

class AgentSessionStore(context: Context) {
  private val root = File(context.filesDir, "sessions")
  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun create(title: String = "New session"): AgentSession {
    val now = System.currentTimeMillis()
    val session = AgentSession(
      id = UUID.randomUUID().toString(),
      title = title,
      createdAtMillis = now,
      updatedAtMillis = now,
    )
    save(session)
    return session
  }

  fun load(id: String): AgentSession? {
    val file = fileFor(id)
    if (!file.exists()) return null
    return runCatching { json.decodeFromString<AgentSession>(file.readText()) }.getOrNull()
  }

  fun list(): List<AgentSession> {
    if (!root.exists()) return emptyList()
    return root.listFiles { file -> file.extension == "json" }
      ?.mapNotNull { runCatching { json.decodeFromString<AgentSession>(it.readText()) }.getOrNull() }
      ?.sortedByDescending { it.updatedAtMillis }
      ?: emptyList()
  }

  fun appendMessage(session: AgentSession, message: SessionMessage): AgentSession {
    val updated = session.copy(
      updatedAtMillis = System.currentTimeMillis(),
      messages = session.messages + message,
    )
    save(updated)
    return updated
  }

  fun save(session: AgentSession) {
    root.mkdirs()
    fileFor(session.id).writeText(json.encodeToString(session))
  }

  private fun fileFor(id: String): File = File(root, "$id.json")
}
