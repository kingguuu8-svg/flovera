package com.flovera.app.agent

import com.flovera.app.config.AppSettings
import com.flovera.app.session.AgentSession
import com.flovera.app.session.ContextUsageRecord
import com.flovera.app.workspace.WorkspaceManager

const val HANDOFF_SOURCE_LLM = "llm_handoff"
const val HANDOFF_SOURCE_LOCAL_FALLBACK = "local_fallback"

data class SessionHandoffCompression(
  val summary: String,
  val source: String,
  val error: String = "",
)

interface SessionHandoffCompressor {
  suspend fun compress(
    settings: AppSettings,
    session: AgentSession,
    record: ContextUsageRecord,
    workspace: WorkspaceManager,
  ): SessionHandoffCompression
}
