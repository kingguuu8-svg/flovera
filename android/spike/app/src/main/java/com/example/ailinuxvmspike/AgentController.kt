package com.example.ailinuxvmspike

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.ailinuxvmspike.config.AppSettings
import com.example.ailinuxvmspike.config.SettingsStore
import com.example.ailinuxvmspike.koog.KoogAgentRuntime
import com.example.ailinuxvmspike.koog.ModelProviderCatalog
import com.example.ailinuxvmspike.koog.ToolEventRecorder
import com.example.ailinuxvmspike.session.AgentSession
import com.example.ailinuxvmspike.session.AgentSessionStore
import com.example.ailinuxvmspike.session.SessionMessage
import com.example.ailinuxvmspike.workspace.WorkspaceFileNode
import com.example.ailinuxvmspike.workspace.WorkspaceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class AgentScreenState(
  val settings: AppSettings = AppSettings(),
  val session: AgentSession? = null,
  val sessions: List<AgentSession> = emptyList(),
  val archivedSessions: List<AgentSession> = emptyList(),
  val input: String = "",
  val providerDraft: String = AppSettings().provider,
  val modelDraft: String = AppSettings().model,
  val apiKeyDraft: String = "",
  val agentRulesDraft: String = "",
  val workspaceFiles: String = "",
  val workspaceTree: WorkspaceFileNode? = null,
  val htmlFiles: List<String> = emptyList(),
  val selectedHtmlPath: String = "",
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
    val loadedSession = loadedSettings.activeSessionId?.let { sessionStore.load(it) }
    val session = if (loadedSession?.archivedAtMillis == null) {
      loadedSession ?: sessionStore.list().firstOrNull() ?: sessionStore.create("Default")
    } else {
      sessionStore.list().firstOrNull() ?: sessionStore.create("Default")
    }
    val htmlFiles = workspace.listHtmlFiles()
    val selectedHtmlPath = chooseHtmlPath(loadedSettings.selectedHtmlPath, htmlFiles)
    val provider = ModelProviderCatalog.findProvider(loadedSettings.provider) ?: ModelProviderCatalog.defaultProvider
    val model = loadedSettings.model.ifBlank { provider.defaultModel }
    val settings = loadedSettings.copy(
      provider = provider.id,
      model = model,
      activeSessionId = session.id,
      selectedHtmlPath = selectedHtmlPath,
    )
    settingsStore.save(settings)
    _state.value = AgentScreenState(
      settings = settings,
      session = session,
      sessions = sessionStore.list(),
      archivedSessions = sessionStore.listArchived(),
      providerDraft = provider.id,
      modelDraft = model,
      apiKeyDraft = settings.apiKeyFor(provider.id),
      agentRulesDraft = workspace.readAgentRules(),
      workspaceFiles = workspace.listFiles("."),
      workspaceTree = workspace.fileTree(),
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

  fun updateProvider(value: String) {
    val provider = ModelProviderCatalog.findProvider(value) ?: return
    _state.update {
      it.copy(
        providerDraft = provider.id,
        modelDraft = provider.defaultModel,
        apiKeyDraft = it.settings.apiKeyFor(provider.id),
      )
    }
  }

  fun updateModel(value: String) {
    _state.update { it.copy(modelDraft = value) }
  }

  fun updateAgentRules(value: String) {
    _state.update { it.copy(agentRulesDraft = value) }
  }

  fun saveModelSettings() {
    val current = _state.value
    val provider = ModelProviderCatalog.findProvider(current.providerDraft) ?: ModelProviderCatalog.defaultProvider
    val model = current.modelDraft.trim().ifBlank { provider.defaultModel }
    val settings = current.settings
      .copy(provider = provider.id, model = model)
      .withApiKey(provider.id, current.apiKeyDraft)
    settingsStore.save(settings)
    _state.update {
      it.copy(
        settings = settings,
        providerDraft = provider.id,
        modelDraft = model,
        apiKeyDraft = settings.apiKeyFor(provider.id),
        status = "Settings saved",
      )
    }
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

  fun refreshWorkspaceFiles() {
    refreshWorkspaceState(status = "Workspace refreshed")
  }

  fun renameWorkspacePath(path: String, newName: String) {
    val status = workspace.rename(path, newName)
    refreshWorkspaceState(status = status)
  }

  fun workspaceFileUri(path: String): Uri? {
    val file = workspace.exportableFile(path) ?: return null
    return FileProvider.getUriForFile(appContext, "${appContext.packageName}.workspacefiles", file)
  }

  fun workspaceMimeType(path: String): String = workspace.mimeType(path)

  fun newSession() {
    val session = sessionStore.create("Session ${sessionStore.list().size + 1}")
    val settings = _state.value.settings.copy(activeSessionId = session.id)
    settingsStore.save(settings)
    _state.update {
      it.copy(
        settings = settings,
        session = session,
        sessions = sessionStore.list(),
        archivedSessions = sessionStore.listArchived(),
        agentRulesDraft = workspace.readAgentRules(),
        input = "",
        status = "New session created",
      )
    }
  }

  fun openSession(sessionId: String) {
    val session = sessionStore.load(sessionId) ?: return
    if (session.archivedAtMillis != null) return
    val settings = _state.value.settings.copy(activeSessionId = session.id)
    settingsStore.save(settings)
    _state.update {
      it.copy(
        settings = settings,
        session = session,
        sessions = sessionStore.list(),
        archivedSessions = sessionStore.listArchived(),
        agentRulesDraft = workspace.readAgentRules(),
        status = "Session loaded",
      )
    }
  }

  fun renameSession(sessionId: String, title: String) {
    val renamed = sessionStore.rename(sessionId, title) ?: return
    val active = if (_state.value.session?.id == renamed.id) renamed else _state.value.session
    _state.update {
      it.copy(
        session = active,
        sessions = sessionStore.list(),
        archivedSessions = sessionStore.listArchived(),
        status = "Session renamed",
      )
    }
  }

  fun duplicateSession(sessionId: String) {
    val copy = sessionStore.duplicate(sessionId) ?: return
    activateSession(copy, "Session copied")
  }

  fun archiveSession(sessionId: String) {
    sessionStore.archive(sessionId) ?: return
    val current = _state.value
    if (current.session?.id == sessionId) {
      val next = sessionStore.list().firstOrNull() ?: sessionStore.create("Default")
      activateSession(next, "Session archived")
    } else {
      _state.update {
        it.copy(
          sessions = sessionStore.list(),
          archivedSessions = sessionStore.listArchived(),
          status = "Session archived",
        )
      }
    }
  }

  fun restoreSession(sessionId: String) {
    val restored = sessionStore.restore(sessionId) ?: return
    activateSession(restored, "Session restored")
  }

  fun setSessionPinned(sessionId: String, pinned: Boolean) {
    val updated = sessionStore.setPinned(sessionId, pinned) ?: return
    val active = if (_state.value.session?.id == updated.id) updated else _state.value.session
    _state.update {
      it.copy(
        session = active,
        sessions = sessionStore.list(),
        archivedSessions = sessionStore.listArchived(),
        status = if (pinned) "Session pinned" else "Session unpinned",
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
        archivedSessions = sessionStore.listArchived(),
        workspaceFiles = workspace.listFiles("."),
        workspaceTree = workspace.fileTree(),
        htmlFiles = htmlFiles,
        selectedHtmlPath = selectedHtmlPath,
        selectedHtmlUrl = workspace.displayUrl(selectedHtmlPath),
        isRunning = isRunning,
        status = status,
      )
    }
  }

  private fun activateSession(session: AgentSession, status: String) {
    val settings = _state.value.settings.copy(activeSessionId = session.id)
    settingsStore.save(settings)
    refreshWorkspaceState(settings = settings, session = session, isRunning = false, status = status)
  }

  private fun chooseHtmlPath(current: String, htmlFiles: List<String>): String {
    return when {
      current in htmlFiles -> current
      else -> ""
    }
  }
}
