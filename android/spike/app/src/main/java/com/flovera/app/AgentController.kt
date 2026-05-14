package com.flovera.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.flovera.app.agent.AgentRunController
import com.flovera.app.config.AppSettings
import com.flovera.app.config.ModelSettingsDraft
import com.flovera.app.config.SettingsController
import com.flovera.app.config.SettingsStore
import com.flovera.app.session.AgentSession
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.session.SessionController
import com.flovera.app.session.SessionMessage
import com.flovera.app.workspace.WorkspaceController
import com.flovera.app.workspace.WorkspaceFileNode
import com.flovera.app.workspace.WorkspaceSnapshotRecord
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
  val workspaceRootUrl: String = "",
  val workspaceSnapshots: List<WorkspaceSnapshotRecord> = emptyList(),
  val status: String = "Idle",
  val isRunning: Boolean = false,
  val assistantDraft: SessionMessage? = null,
)

class AgentController(context: Context) {
  private val appContext = context.applicationContext
  private val settingsController = SettingsController(SettingsStore(appContext))
  private val sessionController = SessionController(AgentSessionStore(appContext))
  private val agentRunController = AgentRunController()
  private var workspaceController: WorkspaceController

  private val _state = MutableStateFlow(AgentScreenState())
  val state: StateFlow<AgentScreenState> = _state

  init {
    val settingsLoad = settingsController.loadResult()
    val loadedSettings = settingsLoad.settings
    workspaceController = WorkspaceController(appContext, loadedSettings.activeWorkspaceId).also { it.ensureSeedFiles() }
    val session = sessionController.initialSession(loadedSettings.activeSessionId)
    workspaceController.syncFloveraSettings(loadedSettings)
    var workspaceSnapshot = workspaceController.snapshot(loadedSettings.selectedHtmlPath)
    val settings = settingsController.normalizeSelectedHtml(
      settingsController.setActiveSession(loadedSettings, session?.id),
      workspaceSnapshot.selectedHtmlPath,
    )
    if (settings != loadedSettings) {
      workspaceController.syncFloveraSettings(settings)
      workspaceSnapshot = workspaceController.snapshot(settings.selectedHtmlPath)
    }
    val modelDraft = settingsController.draftFor(settings)
    _state.value = AgentScreenState(
      settings = settings,
      session = session,
      sessions = sessionController.listActive(),
      archivedSessions = sessionController.listArchived(),
      providerDraft = modelDraft.providerId,
      modelDraft = modelDraft.model,
      apiKeyDraft = modelDraft.apiKey,
      agentRulesDraft = workspaceController.readAgentRules(),
      workspaceFiles = workspaceSnapshot.files,
      workspaceTree = workspaceSnapshot.tree,
      htmlFiles = workspaceSnapshot.htmlFiles,
      selectedHtmlPath = workspaceSnapshot.selectedHtmlPath,
      selectedHtmlUrl = workspaceSnapshot.selectedHtmlUrl,
      workspaceRootUrl = workspaceSnapshot.workspaceRootUrl,
      workspaceSnapshots = workspaceSnapshot.snapshots,
      status = settingsLoad.warning ?: "Ready",
    )
  }

  fun updateInput(value: String) {
    _state.update { it.copy(input = value) }
  }

  fun updateApiKey(value: String) {
    _state.update { it.copy(apiKeyDraft = value) }
  }

  fun updateProvider(value: String) {
    val draft = settingsController.draftForProvider(_state.value.settings, value) ?: return
    _state.update {
      it.copy(
        providerDraft = draft.providerId,
        modelDraft = draft.model,
        apiKeyDraft = draft.apiKey,
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
    val settings = settingsController.setNetworkEnabled(_state.value.settings, enabled)
    _state.update {
      it.copy(
        settings = settings,
        status = if (enabled) "Network tools enabled" else "Network tools disabled",
      )
    }
  }

  fun saveModelSettings(
    providerId: String = _state.value.providerDraft,
    model: String = _state.value.modelDraft,
    apiKey: String = _state.value.apiKeyDraft,
    language: String = _state.value.settings.language,
    themeMode: String = _state.value.settings.themeMode,
    themeColor: String = _state.value.settings.themeColor,
  ) {
    val current = _state.value
    val modelSettings = settingsController.saveModelSettings(
      current.settings,
      ModelSettingsDraft(
        providerId = providerId,
        model = model,
        apiKey = apiKey,
      ),
    )
    val settings = settingsController.setAppearance(
      settingsController.setLanguage(modelSettings, language),
      themeMode,
      themeColor,
    )
    val draft = settingsController.draftFor(settings)
    _state.update {
      it.copy(
        settings = settings,
        providerDraft = draft.providerId,
        modelDraft = draft.model,
        apiKeyDraft = draft.apiKey,
        status = "Settings saved",
      )
    }
  }

  fun saveAgentRules(content: String = _state.value.agentRulesDraft) {
    workspaceController.writeAgentRules(content)
    refreshWorkspaceState(status = "AGENT.md saved")
    _state.update { it.copy(agentRulesDraft = content) }
  }

  fun selectHtmlFile(path: String) {
    val current = _state.value
    val settings = settingsController.setSelectedHtml(current.settings, path)
    refreshWorkspaceState(settings = settings, status = "Displaying $path")
  }

  fun refreshWorkspaceFiles() {
    refreshWorkspaceState(status = "Workspace refreshed")
  }

  fun reportStatus(status: String) {
    _state.update { it.copy(status = status) }
  }

  fun renameWorkspacePath(path: String, newName: String) {
    val status = workspaceController.rename(path, newName)
    refreshWorkspaceState(status = status)
  }

  fun createWorkspaceSnapshot(name: String) {
    val current = _state.value
    val snapshot = workspaceController.createSnapshot(name, current.selectedHtmlPath)
    refreshWorkspaceState(status = "Snapshot created: ${snapshot.name}")
  }

  fun restoreWorkspaceSnapshot(snapshotId: String) {
    val restored = workspaceController.restoreSnapshot(snapshotId) ?: return
    val settings = if (restored.selectedHtmlPath.isBlank()) {
      _state.value.settings
    } else {
      settingsController.setSelectedHtml(_state.value.settings, restored.selectedHtmlPath)
    }
    refreshWorkspaceState(settings = settings, status = "Snapshot restored: ${restored.name}")
  }

  fun deleteWorkspaceSnapshot(snapshotId: String) {
    val deleted = workspaceController.deleteSnapshot(snapshotId)
    refreshWorkspaceState(status = if (deleted) "Snapshot deleted" else "Snapshot could not be deleted")
  }

  fun workspaceFileUri(path: String): Uri? {
    val file = workspaceController.exportableFile(path) ?: return null
    return FileProvider.getUriForFile(appContext, "${appContext.packageName}.workspacefiles", file)
  }

  fun workspaceMimeType(path: String): String = workspaceController.mimeType(path)

  fun importSharedIntent(intent: Intent?): Boolean {
    val uris = sharedUris(intent)
    if (uris.isEmpty()) return false
    val results = uris.map { uri -> workspaceController.importSharedFile(uri) }
    refreshWorkspaceState(status = results.joinToString("; "))
    return true
  }

  fun newSession() {
    val session = sessionController.createSession()
    _state.update {
      it.copy(
        session = session,
        sessions = sessionController.listActive(),
        archivedSessions = sessionController.listArchived(),
        agentRulesDraft = workspaceController.readAgentRules(),
        input = "",
        status = "New draft session",
      )
    }
  }

  fun discardEmptyDraftSession() {
    val current = _state.value
    val session = current.session ?: return
    if (session.messages.isNotEmpty()) return
    val fallback = sessionController.nextUsableSession()
    val settings = settingsController.setActiveSession(current.settings, fallback?.id)
    refreshWorkspaceState(
      settings = settings,
      session = fallback,
      isRunning = false,
      status = if (fallback == null) "No active session" else "Session loaded",
    )
  }

  fun openSession(sessionId: String) {
    val session = sessionController.openSession(sessionId) ?: return
    val settings = settingsController.setActiveSession(_state.value.settings, session.id)
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
      if (next == null) {
        val settings = settingsController.setActiveSession(current.settings, null)
        refreshWorkspaceState(settings = settings, session = null, isRunning = false, status = "Session archived")
      } else {
        activateSession(next, "Session archived")
      }
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
    val selectedMessage = session.messages.getOrNull(messageIndex) ?: return
    if (selectedMessage.role != "user") return
    val restored = sessionController.revertToBeforeMessage(session.id, messageIndex)
    if (restored == null && messageIndex == 0) {
      val settings = settingsController.setActiveSession(current.settings, null)
      refreshWorkspaceState(
        settings = settings,
        session = sessionController.createSession(),
        input = selectedMessage.content,
        isRunning = false,
        status = "Conversation reverted",
      )
      return
    }
    if (restored == null) return
    refreshWorkspaceState(
      session = restored,
      input = selectedMessage.content,
      isRunning = false,
      status = "Conversation reverted",
    )
  }

  fun submit() {
    val current = _state.value
    val session = current.session ?: sessionController.createSession()
    if (current.isRunning) return

    agentRunController.submit(
      input = current.input,
      settings = current.settings,
      session = session,
      workspace = workspaceController.runtimeWorkspace(),
      appendUserPrompt = sessionController::appendUserPrompt,
      appendMessage = sessionController::appendMessage,
      onStarted = { withUser, draft ->
        val settings = settingsController.setActiveSession(current.settings, withUser.id)
        _state.update {
          it.copy(
            settings = settings,
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
    input: String = _state.value.input,
    isRunning: Boolean = _state.value.isRunning,
    status: String = _state.value.status,
  ) {
    workspaceController.syncFloveraSettings(settings)
    var workspaceSnapshot = workspaceController.snapshot(settings.selectedHtmlPath)
    val normalizedSettings = settingsController.normalizeSelectedHtml(settings, workspaceSnapshot.selectedHtmlPath)
    if (normalizedSettings != settings) {
      workspaceController.syncFloveraSettings(normalizedSettings)
      workspaceSnapshot = workspaceController.snapshot(normalizedSettings.selectedHtmlPath)
    }
    _state.update {
      it.copy(
        settings = normalizedSettings,
        session = session,
        input = input,
        sessions = sessionController.listActive(),
        archivedSessions = sessionController.listArchived(),
        workspaceFiles = workspaceSnapshot.files,
        workspaceTree = workspaceSnapshot.tree,
        htmlFiles = workspaceSnapshot.htmlFiles,
        selectedHtmlPath = workspaceSnapshot.selectedHtmlPath,
        selectedHtmlUrl = workspaceSnapshot.selectedHtmlUrl,
        workspaceRootUrl = workspaceSnapshot.workspaceRootUrl,
        workspaceSnapshots = workspaceSnapshot.snapshots,
        isRunning = isRunning,
        assistantDraft = if (isRunning) it.assistantDraft else null,
        status = status,
      )
    }
  }

  private fun activateSession(session: AgentSession, status: String) {
    val settings = settingsController.setActiveSession(_state.value.settings, session.id)
    refreshWorkspaceState(settings = settings, session = session, isRunning = false, status = status)
  }

  @Suppress("DEPRECATION")
  private fun sharedUris(intent: Intent?): List<Uri> {
    if (intent == null) return emptyList()
    return when (intent.action) {
      Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
      Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
      else -> emptyList()
    }
  }

}
