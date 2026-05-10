package com.example.ailinuxvmspike.agent

import com.example.ailinuxvmspike.config.AppSettings
import com.example.ailinuxvmspike.koog.AgentRuntime
import com.example.ailinuxvmspike.koog.KoogAgentRuntime
import com.example.ailinuxvmspike.koog.ToolEventRecorder
import com.example.ailinuxvmspike.session.AgentSession
import com.example.ailinuxvmspike.session.SessionMessage
import com.example.ailinuxvmspike.workspace.WorkspaceManager
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
    appendMessage: (AgentSession, SessionMessage) -> AgentSession,
    onStarted: (AgentSession, SessionMessage) -> Unit,
    onDraft: (SessionMessage) -> Unit,
    onFinished: (AgentSession, Boolean) -> Unit,
  ): Job? {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return null

    val withUser = appendUserPrompt(session, trimmed)
    onStarted(withUser, SessionMessage(role = "assistant", content = "Working..."))

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
          session = withUser,
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
      val updated = appendMessage(withUser, assistantMessage)
      onFinished(updated, result.isSuccess)
    }
  }
}
