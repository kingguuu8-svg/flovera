package com.flovera.app.agent

import com.flovera.app.config.AppSettings
import com.flovera.app.koog.AgentRuntime
import com.flovera.app.koog.KoogAgentRuntime
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.session.AgentSession
import com.flovera.app.session.ContextUsageRecord
import com.flovera.app.session.SessionMessage
import com.flovera.app.workspace.WorkspaceManager
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AgentRunController(
  private val runtime: AgentRuntime = KoogAgentRuntime(),
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
  fun submit(
    input: String,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
    appendUserPrompt: (AgentSession, String) -> AgentSession,
    appendContextRecord: (AgentSession, ContextUsageRecord) -> AgentSession,
    appendMessage: (AgentSession, SessionMessage) -> AgentSession,
    onStarted: (AgentSession, SessionMessage) -> Unit,
    onDraft: (SessionMessage) -> Unit,
    onFinished: (AgentSession, Boolean) -> Unit,
  ): Job? {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return null

    val withUser = appendUserPrompt(session, trimmed)
    val withContext = appendContextRecord(withUser, estimateContextUsage(trimmed, withUser, workspace))
    onStarted(withContext, SessionMessage(role = "assistant", content = "Working..."))

    return scope.launch {
      val recorder = ToolEventRecorder { events ->
        onDraft(
          SessionMessage(
            role = "assistant",
            content = "Running tools...",
            toolEvents = events,
          ),
        )
      }
      val result = runCatching {
        runtime.run(
          input = trimmed,
          settings = settings,
          session = withContext,
          workspace = workspace,
          recorder = recorder,
        )
      }

      val assistantMessage = result.fold(
        onSuccess = { output ->
          SessionMessage(role = "assistant", content = output, toolEvents = recorder.snapshot())
        },
        onFailure = { error ->
          SessionMessage(role = "error", content = error.message ?: error.toString(), toolEvents = recorder.snapshot())
        },
      )
      val updated = appendMessage(withContext, assistantMessage)
      onFinished(updated, result.isSuccess)
    }
  }

  private fun estimateContextUsage(
    input: String,
    session: AgentSession,
    workspace: WorkspaceManager,
  ): ContextUsageRecord {
    val recentHistory = session.messages.takeLast(12)
    val historyChars = recentHistory.sumOf { message ->
      message.role.length + message.content.take(1_500).length + 2
    }
    val rulesChars = workspace.readAgentRules().length
    val workspaceListingChars = workspace.listFiles(".").length
    val totalChars = input.length + historyChars + rulesChars + workspaceListingChars
    return ContextUsageRecord(
      id = UUID.randomUUID().toString(),
      source = "agent_run",
      messageCount = session.messages.size,
      inputChars = input.length,
      historyChars = historyChars,
      rulesChars = rulesChars,
      workspaceListingChars = workspaceListingChars,
      approximateTokens = approximateTokens(totalChars),
      compressed = false,
      summary = "No compression was applied for this run.",
    )
  }

  private fun approximateTokens(chars: Int): Int {
    return ((chars + 3) / 4).coerceAtLeast(1)
  }
}
