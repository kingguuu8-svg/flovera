package com.example.ailinuxvmspike

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.ailinuxvmspike.agent.AgentRunController
import com.example.ailinuxvmspike.config.AppSettings
import com.example.ailinuxvmspike.config.SettingsStore
import com.example.ailinuxvmspike.koog.ModelProviderCatalog
import com.example.ailinuxvmspike.session.AgentSession
import com.example.ailinuxvmspike.session.AgentSessionStore
import com.example.ailinuxvmspike.session.SessionController
import com.example.ailinuxvmspike.session.SessionMessage
import com.example.ailinuxvmspike.workspace.WorkspaceController
import com.example.ailinuxvmspike.workspace.WorkspaceFileNode
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
  val assistantDraft: SessionMessage? = null,
)

class AgentController(context: Context) {
  private val appContext = context.applicationContext
  private val settingsStore = SettingsStore(appContext)
  private val sessionController = SessionController(AgentSessionStore(appContext))
  private val agentRunController = AgentRunController()
  private var workspaceController: WorkspaceController

  private val _state = MutableStateFlow(AgentScreenState())
  val state: StateFlow<AgentScreenState> = _state

  init {
    val loadedSettings = settingsStore.load()
    workspaceController = WorkspaceController(appContext, loadedSettings.activeWorkspaceId).also { it.ensureSeedFiles() }
    val session = sessionController.initialSession(loadedSettings.activeSessionId)
    val workspaceSnapshot = workspaceController.snapshot(loadedSettings.selectedHtmlPath)
    val provider = ModelProviderCatalog.findProvider(loadedSettings.provider) ?: ModelProviderCatalog.defaultProvider
    val model = loadedSettings.model.ifBlank { provider.defaultModel }
    val settings = loadedSettings.copy(
      provider = provider.id,
      model = model,
      activeSessionId = session.id,
      selectedHtmlPath = workspaceSnapshot.selectedHtmlPath,
    )
    settingsStore.save(settings)
    _state.value = AgentScreenState(
      settings = settings,
      session = session,
      sessions = sessionController.listActive(),
      archivedSessions = sessionController.listArchived(),
      providerDraft = provider.id,
      modelDraft = model,
      apiKeyDraft = settings.apiKeyFor(provider.id),
      agentRulesDraft = workspaceController.readAgentRules(),
      workspaceFiles = workspaceSnapshot.files,
      workspaceTree = workspaceSnapshot.tree,
      htmlFiles = workspaceSnapshot.htmlFiles,
      selectedHtmlPath = workspaceSnapshot.selectedHtmlPath,
      selectedHtmlUrl = workspaceSnapshot.selectedHtmlUrl,
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

  fun setNetworkEnabled(enabled: Boolean) {
    val settings = _state.value.settings.copy(networkEnabled = enabled)
    settingsStore.save(settings)
    _state.update {
      it.copy(
        settings = settings,
        status = if (enabled) "Network tools enabled" else "Network tools disabled",
      )
    }
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
    workspaceController.writeAgentRules(current.agentRulesDraft)
    refreshWorkspaceState(status = "AGENT.md saved")
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
    val status = workspaceController.rename(path, newName)
    refreshWorkspaceState(status = status)
  }

  fun workspaceFileUri(path: String): Uri? {
    val file = workspaceController.exportableFile(path) ?: return null
    return FileProvider.getUriForFile(appContext, "${appContext.packageName}.workspacefiles", file)
  }

  fun workspaceMimeType(path: String): String = workspaceController.mimeType(path)

  fun newSession() {
    val session = sessionController.createSession()
    val settings = _state.value.settings.copy(activeSessionId = session.id)
    settingsStore.save(settings)
    _state.update {
      it.copy(
        settings = settings,
        session = session,
        sessions = sessionController.listActive(),
        archivedSessions = sessionController.listArchived(),
        agentRulesDraft = workspaceController.readAgentRules(),
        input = "",
        status = "New session created",
      )
    }
  }

  fun openSession(sessionId: String) {
    val session = sessionController.openSession(sessionId) ?: return
    val settings = _state.value.settings.copy(activeSessionId = session.id)
    settingsStore.save(settings)
    _state.update {
      it.copy(
        settings = settings,
        session = session,
        sessions = sessionController.listActive(),
        archivedSessions = sessionController.listArchived(),
        agentRulesDraft = workspaceController.readAgentRules(),
        status = "Session loaded",
      )
    }
  }

  fun renameSession(sessionId: String, title: String) {
    val renamed = sessionController.renameSession(sessionId, title) ?: return
    val active = if (_state.value.session?.id == renamed.id) renamed else _state.value.session
    _state.update {
      it.copy(
        session = active,
        sessions = sessionController.listActive(),
        archivedSessions = sessionController.listArchived(),
        status = "Session renamed",
      )
    }
  }

  fun duplicateSession(sessionId: String) {
    val copy = sessionController.duplicateSession(sessionId) ?: return
    activateSession(copy, "Session copied")
  }

  fun archiveSession(sessionId: String) {
    sessionController.archiveSession(sessionId) ?: return
    val current = _state.value
    if (current.session?.id == sessionId) {
      val next = sessionController.nextUsableSession()
      activateSession(next, "Session archived")
    } else {
      _state.update {
        it.copy(
          sessions = sessionController.listActive(),
          archivedSessions = sessionController.listArchived(),
          status = "Session archived",
        )
      }
    }
  }

  fun restoreSession(sessionId: String) {
    val restored = sessionController.restoreSession(sessionId) ?: return
    activateSession(restored, "Session restored")
  }

  fun setSessionPinned(sessionId: String, pinned: Boolean) {
    val updated = sessionController.setSessionPinned(sessionId, pinned) ?: return
    val active = if (_state.value.session?.id == updated.id) updated else _state.value.session
    _state.update {
      it.copy(
        session = active,
        sessions = sessionController.listActive(),
        archivedSessions = sessionController.listArchived(),
        status = if (pinned) "Session pinned" else "Session unpinned",
      )
    }
  }

  fun revertSessionToMessage(messageIndex: Int) {
    val current = _state.value
    if (current.isRunning) return
    val session = current.session ?: return
    val restored = sessionController.revertToBeforeMessage(session.id, messageIndex) ?: return
    refreshWorkspaceState(
      session = restored,
      isRunning = false,
      status = "Conversation reverted",
    )
  }

  fun submit() {
    val current = _state.value
    val session = current.session ?: return
    if (current.isRunning) return

    agentRunController.submit(
      input = current.input,
      settings = current.settings,
      session = session,
      workspace = workspaceController.runtimeWorkspace(),
      appendUserPrompt = sessionController::appendUserPrompt,
      appendMessage = sessionController::appendMessage,
      onStarted = { withUser, draft ->
        _state.update {
          it.copy(
            input = "",
            isRunning = true,
            status = "Running agent loop...",
            assistantDraft = draft,
            session = withUser,
            sessions = sessionController.listActive(),
          )
        }
      },
      onDraft = { draft ->
        _state.update {
          it.copy(assistantDraft = draft)
        }
      },
      onFinished = { updated, succeeded ->
        val status = if (succeeded) "Agent loop completed" else "Agent loop failed"
        refreshWorkspaceState(
          session = updated,
          isRunning = false,
          status = status,
        )
      },
    )
  }

  private fun refreshWorkspaceState(
    settings: AppSettings = _state.value.settings,
    session: AgentSession? = _state.value.session,
    isRunning: Boolean = _state.value.isRunning,
    status: String = _state.value.status,
  ) {
    val workspaceSnapshot = workspaceController.snapshot(settings.selectedHtmlPath)
    val normalizedSettings = settings.copy(selectedHtmlPath = workspaceSnapshot.selectedHtmlPath)
    if (normalizedSettings != settings) settingsStore.save(normalizedSettings)
    _state.update {
      it.copy(
        settings = normalizedSettings,
        session = session,
        sessions = sessionController.listActive(),
        archivedSessions = sessionController.listArchived(),
        workspaceFiles = workspaceSnapshot.files,
        workspaceTree = workspaceSnapshot.tree,
        htmlFiles = workspaceSnapshot.htmlFiles,
        selectedHtmlPath = workspaceSnapshot.selectedHtmlPath,
        selectedHtmlUrl = workspaceSnapshot.selectedHtmlUrl,
        isRunning = isRunning,
        assistantDraft = if (isRunning) it.assistantDraft else null,
        status = status,
      )
    }
  }

  private fun activateSession(session: AgentSession, status: String) {
    val settings = _state.value.settings.copy(activeSessionId = session.id)
    settingsStore.save(settings)
    refreshWorkspaceState(settings = settings, session = session, isRunning = false, status = status)
  }

}
