package com.flovera.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.flovera.app.agent.AgentRunStatusNotifier
import com.flovera.app.agent.AgentRunController
import com.flovera.app.agent.AndroidAgentRunStatusNotifier
import com.flovera.app.config.AppSettings
import com.flovera.app.config.ModelSettingsDraft
import com.flovera.app.config.SettingsController
import com.flovera.app.config.SettingsStore
import com.flovera.app.session.AgentSession
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.session.SessionController
import com.flovera.app.session.SessionMessage
import com.flovera.app.workspace.WorkspaceController
import com.flovera.app.workspace.WorkspaceControlledToolProposal
import com.flovera.app.workspace.WorkspaceFileNode
import com.flovera.app.workspace.WorkspaceSettingsProposal
import com.flovera.app.workspace.WorkspaceSnapshotRecord
import kotlinx.coroutines.Job
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
  val customOpenAIBaseUrlDraft: String = "",
  val customOpenAIChatCompletionsPathDraft: String = "/v1/chat/completions",
  val customOpenAICompatibilityModeDraft: String = "generic",
  val agentRulesDraft: String = "",
  val workspaceFiles: String = "",
  val workspaceTree: WorkspaceFileNode? = null,
  val htmlFiles: List<String> = emptyList(),
  val selectedHtmlPath: String = "",
  val selectedHtmlUrl: String? = null,
  val selectedPreviewPath: String = "",
  val selectedPreviewContent: String = "",
  val selectedPreviewMimeType: String = "",
  val selectedPreviewUri: String = "",
  val workspaceRootUrl: String = "",
  val workspaceSnapshots: List<WorkspaceSnapshotRecord> = emptyList(),
  val settingsProposals: List<WorkspaceSettingsProposal> = emptyList(),
  val controlledToolProposals: List<WorkspaceControlledToolProposal> = emptyList(),
  val status: String = "Idle",
  val isRunning: Boolean = false,
  val assistantDraft: SessionMessage? = null,
  val queuedInputs: List<QueuedAgentInput> = emptyList(),
)

data class QueuedAgentInput(
  val content: String,
  val mode: String = QUEUED_INPUT_REQUEST,
)

private data class FullAuthoritySettingsApplyResult(
  val settings: AppSettings,
  val appliedCount: Int = 0,
)

const val QUEUED_INPUT_REQUEST = "request"
const val QUEUED_INPUT_GUIDANCE = "guidance"

private fun QueuedAgentInput.toRunInput(): String {
  if (mode != QUEUED_INPUT_GUIDANCE) return content
  return """
    Guidance while the previous agent run was active:
    $content

    Continue the current task using this guidance. If the task was already completed, revise or continue only when useful.
  """.trimIndent()
}

class AgentController(
  context: Context,
  settingsStore: SettingsStore = SettingsStore(context.applicationContext),
  sessionStore: AgentSessionStore = AgentSessionStore(context.applicationContext),
  private val agentRunController: AgentRunController = AgentRunController(),
  private val agentRunStatusNotifier: AgentRunStatusNotifier = AndroidAgentRunStatusNotifier(context.applicationContext),
) {
  private val appContext = context.applicationContext
  private val settingsController = SettingsController(settingsStore)
  private val sessionController = SessionController(sessionStore)
  private var activeRunJob: Job? = null
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
      customOpenAIBaseUrlDraft = modelDraft.customOpenAIBaseUrl,
      customOpenAIChatCompletionsPathDraft = modelDraft.customOpenAIChatCompletionsPath,
      customOpenAICompatibilityModeDraft = modelDraft.customOpenAICompatibilityMode,
      agentRulesDraft = workspaceController.readAgentRules(),
      workspaceFiles = workspaceSnapshot.files,
      workspaceTree = workspaceSnapshot.tree,
      htmlFiles = workspaceSnapshot.htmlFiles,
      selectedHtmlPath = workspaceSnapshot.selectedHtmlPath,
      selectedHtmlUrl = workspaceSnapshot.selectedHtmlUrl,
      selectedPreviewPath = workspaceSnapshot.selectedHtmlPath,
      selectedPreviewMimeType = if (workspaceSnapshot.selectedHtmlPath.isBlank()) "" else "text/html",
      selectedPreviewUri = "",
      workspaceRootUrl = workspaceSnapshot.workspaceRootUrl,
      workspaceSnapshots = workspaceSnapshot.snapshots,
      settingsProposals = workspaceSnapshot.settingsProposals,
      controlledToolProposals = workspaceSnapshot.controlledToolProposals,
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
        customOpenAIBaseUrlDraft = draft.customOpenAIBaseUrl,
        customOpenAIChatCompletionsPathDraft = draft.customOpenAIChatCompletionsPath,
        customOpenAICompatibilityModeDraft = draft.customOpenAICompatibilityMode,
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
    customOpenAIBaseUrl: String = _state.value.customOpenAIBaseUrlDraft,
    customOpenAIChatCompletionsPath: String = _state.value.customOpenAIChatCompletionsPathDraft,
    customOpenAICompatibilityMode: String = _state.value.customOpenAICompatibilityModeDraft,
    language: String = _state.value.settings.language,
    themeMode: String = _state.value.settings.themeMode,
    themeColor: String = _state.value.settings.themeColor,
    authorityMode: String = _state.value.settings.agentAuthorityMode,
    deepSeekThinkingEffort: String = _state.value.settings.deepSeekThinkingEffort,
    webSearchEnabled: Boolean = _state.value.settings.webSearchEnabled,
    braveSearchApiKey: String = _state.value.settings.braveSearchApiKey,
  ) {
    val current = _state.value
    val modelSettings = settingsController.saveModelSettings(
      current.settings,
      ModelSettingsDraft(
        providerId = providerId,
        model = model,
        apiKey = apiKey,
        customOpenAIBaseUrl = customOpenAIBaseUrl,
        customOpenAIChatCompletionsPath = customOpenAIChatCompletionsPath,
        customOpenAICompatibilityMode = customOpenAICompatibilityMode,
      ),
    )
    val settings = settingsController.setAppearance(
      settingsController.setLanguage(modelSettings, language),
      themeMode,
      themeColor,
    )
    val settingsWithAuthority = settingsController.setAuthorityMode(settings, authorityMode)
    val settingsWithThinking = settingsController.setDeepSeekThinkingEffort(settingsWithAuthority, deepSeekThinkingEffort)
    val settingsWithSearch = settingsController.setWebSearch(settingsWithThinking, webSearchEnabled, braveSearchApiKey)
    val draft = settingsController.draftFor(settingsWithSearch)
    workspaceController.syncFloveraSettings(settingsWithSearch)
    val workspaceSnapshot = workspaceController.snapshot(settingsWithSearch.selectedHtmlPath)
    _state.update {
      it.copy(
        settings = settingsWithSearch,
        providerDraft = draft.providerId,
        modelDraft = draft.model,
        apiKeyDraft = draft.apiKey,
        customOpenAIBaseUrlDraft = draft.customOpenAIBaseUrl,
        customOpenAIChatCompletionsPathDraft = draft.customOpenAIChatCompletionsPath,
        customOpenAICompatibilityModeDraft = draft.customOpenAICompatibilityMode,
        workspaceFiles = workspaceSnapshot.files,
        workspaceTree = workspaceSnapshot.tree,
        htmlFiles = workspaceSnapshot.htmlFiles,
        selectedHtmlPath = workspaceSnapshot.selectedHtmlPath,
        selectedHtmlUrl = workspaceSnapshot.selectedHtmlUrl,
        selectedPreviewPath = workspaceSnapshot.selectedHtmlPath,
        selectedPreviewContent = "",
        selectedPreviewMimeType = if (workspaceSnapshot.selectedHtmlPath.isBlank()) "" else "text/html",
        selectedPreviewUri = "",
        workspaceRootUrl = workspaceSnapshot.workspaceRootUrl,
        workspaceSnapshots = workspaceSnapshot.snapshots,
        settingsProposals = workspaceSnapshot.settingsProposals,
        controlledToolProposals = workspaceSnapshot.controlledToolProposals,
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
    refreshWorkspaceState(settings = settings, status = "Displaying $path", resetPreviewToSelectedHtml = true)
  }

  fun selectWorkspacePreview(path: String) {
    if (path.endsWith(".html", ignoreCase = true) || path.endsWith(".htm", ignoreCase = true)) {
      selectHtmlFile(path)
      return
    }
    val mimeType = workspaceController.mimeType(path)
    val isImage = mimeType.startsWith("image/")
    val isPdf = mimeType == "application/pdf" || path.endsWith(".pdf", ignoreCase = true)
    val canPreviewAsText = canPreviewAsText(path, mimeType)
    val content = when {
      isImage -> ""
      isPdf -> ""
      canPreviewAsText -> workspaceController.previewTextFile(path)
      else -> "No built-in preview for $mimeType. Use Open with or Share from the file menu."
    }
    _state.update {
      it.copy(
        selectedPreviewPath = path,
        selectedPreviewContent = content,
        selectedPreviewMimeType = mimeType,
        selectedPreviewUri = if (isImage || isPdf) workspaceFileUri(path)?.toString().orEmpty() else "",
        status = "Previewing $path",
      )
    }
  }

  fun setHtmlPinned(path: String, pinned: Boolean) {
    val settings = settingsController.setPinnedHtmlPath(_state.value.settings, path, pinned)
    refreshWorkspaceState(settings = settings, status = if (pinned) "HTML pinned" else "HTML unpinned")
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

  fun approveSettingsProposal(path: String) {
    val proposal = workspaceController.listSettingsProposals().firstOrNull { it.path == path } ?: return
    val settings = settingsController.applySettingsProposal(_state.value.settings, proposal.changes)
    workspaceController.deleteSettingsProposal(path)
    refreshWorkspaceState(settings = settings, status = "Settings proposal applied: ${proposal.title}")
  }

  fun rejectSettingsProposal(path: String) {
    val deleted = workspaceController.deleteSettingsProposal(path)
    refreshWorkspaceState(status = if (deleted) "Settings proposal rejected" else "Settings proposal not found")
  }

  fun dismissControlledToolProposal(path: String) {
    val deleted = workspaceController.deleteControlledToolProposal(path)
    refreshWorkspaceState(status = if (deleted) "Tool proposal dismissed" else "Tool proposal not found")
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
    val trimmed = current.input.trim()
    if (trimmed.isBlank()) return
    if (current.isRunning) {
      enqueueInput(trimmed, QUEUED_INPUT_REQUEST, "Message queued")
      return
    }
    startAgentRun(trimmed, current.session ?: sessionController.createSession())
  }

  fun guideAgentRun() {
    val current = _state.value
    val trimmed = current.input.trim()
    if (!current.isRunning || trimmed.isBlank()) return
    enqueueInput(trimmed, QUEUED_INPUT_GUIDANCE, "Guidance queued")
  }

  fun markQueuedInputAsGuidance(index: Int) {
    _state.update {
      val updated = it.queuedInputs.mapIndexed { itemIndex, input ->
        if (itemIndex == index) input.copy(mode = QUEUED_INPUT_GUIDANCE) else input
      }
      it.copy(queuedInputs = updated, status = "Guidance queued")
    }
  }

  fun removeQueuedInput(index: Int) {
    _state.update {
      it.copy(
        queuedInputs = it.queuedInputs.filterIndexed { itemIndex, _ -> itemIndex != index },
        status = "Queued message removed",
      )
    }
  }

  private fun enqueueInput(input: String, mode: String, status: String) {
    _state.update {
      it.copy(
        input = "",
        queuedInputs = it.queuedInputs + QueuedAgentInput(content = input, mode = mode),
        status = status,
      )
    }
  }

  private fun startAgentRun(input: String, session: AgentSession) {
    val current = _state.value
    activeRunJob = agentRunController.submit(
      input = input,
      settings = current.settings,
      session = session,
      workspace = workspaceController.runtimeWorkspace(),
      appendUserPrompt = sessionController::appendUserPrompt,
      appendContextRecord = sessionController::appendContextRecord,
      appendCompressionDivider = sessionController::appendCompressionDivider,
      appendMessage = sessionController::appendMessage,
      onStarted = { withUser, draft ->
        val settings = settingsController.setActiveSession(current.settings, withUser.id)
        agentRunStatusNotifier.running(draft.content)
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
      onSessionUpdated = { updatedSession, draft ->
        agentRunStatusNotifier.running(draft.content)
        _state.update {
          it.copy(
            session = updatedSession,
            sessions = sessionController.listActive(),
            assistantDraft = draft,
          )
        }
      },
      onFinished = { updated, succeeded ->
        activeRunJob = null
        val status = if (succeeded) "Agent loop completed" else "Agent loop failed"
        val nextInput = _state.value.queuedInputs.firstOrNull()
        if (nextInput == null) {
          agentRunStatusNotifier.finished(succeeded)
          refreshWorkspaceState(
            session = updated,
            isRunning = false,
            status = status,
          )
        } else {
          _state.update { it.copy(queuedInputs = it.queuedInputs.drop(1)) }
          agentRunStatusNotifier.running("Running queued message...")
          refreshWorkspaceState(
            session = updated,
            isRunning = false,
            status = "Running queued message...",
          )
          startAgentRun(nextInput.toRunInput(), updated)
        }
      },
    )
  }

  fun clearQueuedInputs() {
    _state.update {
      it.copy(
        queuedInputs = emptyList(),
        status = "Queued messages cleared",
      )
    }
  }

  fun interruptAgentRun() {
    val current = _state.value
    if (!current.isRunning) return
    activeRunJob?.cancel()
    activeRunJob = null
    val interrupted = current.session?.let { session ->
      sessionController.appendMessage(session, SessionMessage(role = "assistant", content = "Run interrupted by user."))
    }
    agentRunStatusNotifier.interrupted()
    refreshWorkspaceState(
      session = interrupted ?: current.session,
      isRunning = false,
      status = "Agent loop interrupted",
    )
  }

  private fun refreshWorkspaceState(
    settings: AppSettings = _state.value.settings,
    session: AgentSession? = _state.value.session,
    input: String = _state.value.input,
    isRunning: Boolean = _state.value.isRunning,
    status: String = _state.value.status,
    resetPreviewToSelectedHtml: Boolean = false,
  ) {
    val fullAuthorityResult = applyFullAuthoritySettingsProposals(settings)
    val settingsAfterAuthority = fullAuthorityResult.settings
    val statusAfterAuthority = if (fullAuthorityResult.appliedCount > 0) {
      "Full Authority applied ${fullAuthorityResult.appliedCount} settings proposal(s)"
    } else {
      status
    }
    workspaceController.syncFloveraSettings(settingsAfterAuthority)
    var workspaceSnapshot = workspaceController.snapshot(settingsAfterAuthority.selectedHtmlPath)
    val normalizedSettings = settingsController.normalizeSelectedHtml(settingsAfterAuthority, workspaceSnapshot.selectedHtmlPath)
    if (normalizedSettings != settingsAfterAuthority) {
      workspaceController.syncFloveraSettings(normalizedSettings)
      workspaceSnapshot = workspaceController.snapshot(normalizedSettings.selectedHtmlPath)
    }
    _state.update {
      val previewPath = when {
        resetPreviewToSelectedHtml -> workspaceSnapshot.selectedHtmlPath
        it.selectedPreviewPath.isBlank() -> workspaceSnapshot.selectedHtmlPath
        else -> it.selectedPreviewPath
      }
      val previewContent = when {
        resetPreviewToSelectedHtml -> ""
        it.selectedPreviewPath.isBlank() -> ""
        else -> it.selectedPreviewContent
      }
      val previewMimeType = when {
        resetPreviewToSelectedHtml && workspaceSnapshot.selectedHtmlPath.isNotBlank() -> "text/html"
        resetPreviewToSelectedHtml -> ""
        it.selectedPreviewMimeType.isBlank() && workspaceSnapshot.selectedHtmlPath.isNotBlank() -> "text/html"
        else -> it.selectedPreviewMimeType
      }
      val previewUri = when {
        resetPreviewToSelectedHtml -> ""
        it.selectedPreviewPath.isBlank() -> ""
        else -> it.selectedPreviewUri
      }
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
        selectedPreviewPath = previewPath,
        selectedPreviewContent = previewContent,
        selectedPreviewMimeType = previewMimeType,
        selectedPreviewUri = previewUri,
        workspaceRootUrl = workspaceSnapshot.workspaceRootUrl,
        workspaceSnapshots = workspaceSnapshot.snapshots,
        settingsProposals = workspaceSnapshot.settingsProposals,
        controlledToolProposals = workspaceSnapshot.controlledToolProposals,
        isRunning = isRunning,
        assistantDraft = if (isRunning) it.assistantDraft else null,
        status = statusAfterAuthority,
      )
    }
  }

  private fun applyFullAuthoritySettingsProposals(settings: AppSettings): FullAuthoritySettingsApplyResult {
    if (settings.agentAuthorityMode != "full") return FullAuthoritySettingsApplyResult(settings)
    val proposals = workspaceController.listSettingsProposals().sortedBy { it.createdAtMillis }
    if (proposals.isEmpty()) return FullAuthoritySettingsApplyResult(settings)
    workspaceController.runtimeWorkspace().createAutomaticSnapshot("full_authority_settings")
    var updatedSettings = settings
    var appliedCount = 0
    proposals.forEach { proposal ->
      workspaceController.runtimeWorkspace().appendFullAuthorityAudit(
        action = "settings_proposal_auto_apply",
        targetPath = proposal.path,
        title = proposal.title,
        reason = proposal.reason,
        changes = proposal.changes,
      )
      updatedSettings = settingsController.applySettingsProposal(updatedSettings, proposal.changes)
      if (workspaceController.deleteSettingsProposal(proposal.path)) {
        appliedCount += 1
      }
    }
    return FullAuthoritySettingsApplyResult(updatedSettings, appliedCount)
  }

  private fun canPreviewAsText(path: String, mimeType: String): Boolean {
    val extension = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return mimeType.startsWith("text/") ||
      mimeType == "application/json" ||
      extension in setOf("md", "markdown", "json", "js", "css", "csv", "xml", "kt", "java", "py")
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
