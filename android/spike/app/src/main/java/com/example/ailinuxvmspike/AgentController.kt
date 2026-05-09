package com.example.ailinuxvmspike

import android.content.Context
import com.example.ailinuxvmspike.config.AppSettings
import com.example.ailinuxvmspike.config.SettingsStore
import com.example.ailinuxvmspike.koog.KoogAgentRuntime
import com.example.ailinuxvmspike.koog.ToolEventRecorder
import com.example.ailinuxvmspike.session.AgentSession
import com.example.ailinuxvmspike.session.AgentSessionStore
import com.example.ailinuxvmspike.session.SessionMessage
import com.example.ailinuxvmspike.workspace.WorkspaceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class AgentScreenState(
  val settings: AppSettings = AppSettings(),
  val session: AgentSession? = null,
  val sessions: List<AgentSession> = emptyList(),
  val input: String = "",
  val apiKeyDraft: String = "",
  val agentRulesDraft: String = "",
  val workspaceFiles: String = "",
  val htmlFiles: List<String> = emptyList(),
  val selectedHtmlPath: String = "index.html",
  val selectedHtmlUrl: String? = null,
  val status: String = "Idle",
  val isRunning: Boolean = false,
)

class AgentController(context: Context) {
  private val appContext = context.applicationContext
  private val settingsStore = SettingsStore(appContext)
  private val sessionStore = AgentSessionStore(appContext)
  private val runtime = KoogAgentRuntime()
  private var workspace: WorkspaceManager

  private val _state = MutableStateFlow(AgentScreenState())
  val state: StateFlow<AgentScreenState> = _state

  init {
    val loadedSettings = settingsStore.load()
    workspace = WorkspaceManager(appContext, loadedSettings.activeWorkspaceId).also { it.ensureSeedFiles() }
    val session = loadedSettings.activeSessionId?.let { sessionStore.load(it) } ?: sessionStore.create("Default")
    val htmlFiles = workspace.listHtmlFiles()
    val selectedHtmlPath = chooseHtmlPath(loadedSettings.selectedHtmlPath, htmlFiles)
    val settings = loadedSettings.copy(activeSessionId = session.id, selectedHtmlPath = selectedHtmlPath)
    settingsStore.save(settings)
    _state.value = AgentScreenState(
      settings = settings,
      session = session,
      sessions = sessionStore.list(),
      apiKeyDraft = settings.apiKey,
      agentRulesDraft = workspace.readAgentRules(),
      workspaceFiles = workspace.listFiles("."),
      htmlFiles = htmlFiles,
      selectedHtmlPath = selectedHtmlPath,
      selectedHtmlUrl = workspace.displayUrl(selectedHtmlPath),
      status = "Ready",
    )
  }

  fun updateInput(value: String) {
    _state.update { it.copy(input = value) }
  }

  fun updateApiKey(value: String) {
    _state.update { it.copy(apiKeyDraft = value) }
  }

  fun updateAgentRules(value: String) {
    _state.update { it.copy(agentRulesDraft = value) }
  }

  fun saveApiKey() {
    val current = _state.value
    val settings = current.settings.copy(apiKey = current.apiKeyDraft.trim())
    settingsStore.save(settings)
    _state.update { it.copy(settings = settings, status = "Settings saved") }
  }

  fun saveAgentRules() {
    val current = _state.value
    workspace.writeFile("AGENT.md", current.agentRulesDraft)
    _state.update {
      it.copy(
        workspaceFiles = workspace.listFiles("."),
        status = "AGENT.md saved",
      )
    }
  }

  fun selectHtmlFile(path: String) {
    val current = _state.value
    val settings = current.settings.copy(selectedHtmlPath = path)
    settingsStore.save(settings)
    refreshWorkspaceState(settings = settings, status = "Displaying $path")
  }

  fun newSession() {
    val session = sessionStore.create("Session ${sessionStore.list().size + 1}")
    val settings = _state.value.settings.copy(activeSessionId = session.id)
    settingsStore.save(settings)
    _state.update {
      it.copy(
        settings = settings,
        session = session,
        sessions = sessionStore.list(),
        agentRulesDraft = workspace.readAgentRules(),
        input = "",
        status = "New session created",
      )
    }
  }

  fun openSession(sessionId: String) {
    val session = sessionStore.load(sessionId) ?: return
    val settings = _state.value.settings.copy(activeSessionId = session.id)
    settingsStore.save(settings)
    _state.update {
      it.copy(
        settings = settings,
        session = session,
        sessions = sessionStore.list(),
        agentRulesDraft = workspace.readAgentRules(),
        status = "Session loaded",
      )
    }
  }

  suspend fun submit() {
    val current = _state.value
    val session = current.session ?: return
    val input = current.input.trim()
    if (input.isBlank() || current.isRunning) return

    _state.update { it.copy(input = "", isRunning = true, status = "Running agent loop...") }
    val withUser = sessionStore.appendMessage(session, SessionMessage(role = "user", content = input))
    _state.update { it.copy(session = withUser, sessions = sessionStore.list()) }

    val recorder = ToolEventRecorder()
    val result = runCatching {
      runtime.run(
        input = input,
        settings = current.settings,
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
    val updated = sessionStore.appendMessage(withUser, assistantMessage)
    val status = if (result.isSuccess) "Agent loop completed" else "Agent loop failed"
    refreshWorkspaceState(session = updated, isRunning = false, status = status)
  }

  private fun refreshWorkspaceState(
    settings: AppSettings = _state.value.settings,
    session: AgentSession? = _state.value.session,
    isRunning: Boolean = _state.value.isRunning,
    status: String = _state.value.status,
  ) {
    val htmlFiles = workspace.listHtmlFiles()
    val selectedHtmlPath = chooseHtmlPath(settings.selectedHtmlPath, htmlFiles)
    val normalizedSettings = settings.copy(selectedHtmlPath = selectedHtmlPath)
    if (normalizedSettings != settings) settingsStore.save(normalizedSettings)
    _state.update {
      it.copy(
        settings = normalizedSettings,
        session = session,
        sessions = sessionStore.list(),
        workspaceFiles = workspace.listFiles("."),
        htmlFiles = htmlFiles,
        selectedHtmlPath = selectedHtmlPath,
        selectedHtmlUrl = workspace.displayUrl(selectedHtmlPath),
        isRunning = isRunning,
        status = status,
      )
    }
  }

  private fun chooseHtmlPath(current: String, htmlFiles: List<String>): String {
    return when {
      current in htmlFiles -> current
      "index.html" in htmlFiles -> "index.html"
      else -> htmlFiles.firstOrNull().orEmpty()
    }
  }
}
