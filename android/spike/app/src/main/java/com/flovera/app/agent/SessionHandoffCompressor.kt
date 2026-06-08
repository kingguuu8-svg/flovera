package com.flovera.app.agent

import com.flovera.app.config.AppSettings
import com.flovera.app.session.AgentSession
import com.flovera.app.session.AgentRunTimelineEvent
import com.flovera.app.session.ContextUsageRecord
import com.flovera.app.session.ConversationTranscriptEvent
import com.flovera.app.session.ToolEvent
import com.flovera.app.workspace.WorkspaceManager

const val HANDOFF_SOURCE_LLM = "llm_handoff"
const val HANDOFF_SOURCE_LOCAL_FALLBACK = "local_fallback"

data class SessionHandoffCompression(
  val summary: String,
  val source: String,
  val error: String = "",
)

data class InterruptedRunHandoff(
  val originalInput: String,
  val assistantDraft: String,
  val toolEvents: List<ToolEvent>,
  val runEvents: List<AgentRunTimelineEvent>,
  val transcriptEvents: List<ConversationTranscriptEvent>,
  val failureStage: String,
  val providerError: String,
  val recoveryInstruction: String,
)

interface SessionHandoffCompressor {
  suspend fun compress(
    settings: AppSettings,
    session: AgentSession,
    record: ContextUsageRecord,
    workspace: WorkspaceManager,
    interruptedRun: InterruptedRunHandoff? = null,
  ): SessionHandoffCompression

  suspend fun summarizeAssistantFinal(
    settings: AppSettings,
    userContent: String,
    assistantContent: String,
    runContext: String = "",
  ): String
}

data class ContextOverflowDetection(
  val isOverflow: Boolean,
  val source: String,
  val reason: String,
)

interface ContextOverflowDetector {
  suspend fun detect(settings: AppSettings, error: Throwable): ContextOverflowDetection
}
