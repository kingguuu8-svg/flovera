package com.flovera.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.flovera.app.agent.AgentRunStatusNotifier
import com.flovera.app.agent.AgentRunController
import com.flovera.app.agent.AgentRunEventType
import com.flovera.app.agent.AgentRunForegroundService
import com.flovera.app.agent.AndroidAgentRunStatusNotifier
import com.flovera.app.config.AppSettings
import com.flovera.app.config.ModelSettingsDraft
import com.flovera.app.config.SettingsController
import com.flovera.app.config.SettingsStore
import com.flovera.app.koog.FloveraPythonRuntime
import com.flovera.app.koog.ModelProviderCatalog
import com.flovera.app.performance.FloveraDispatchers
import com.flovera.app.performance.FloveraPerformance
import com.flovera.app.session.AgentSession
import com.flovera.app.session.AgentRunTimelineEvent
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.session.ConversationTranscriptEvent
import com.flovera.app.session.SessionController
import com.flovera.app.session.SessionMessage
import com.flovera.app.workspace.WorkspaceArtifact
import com.flovera.app.workspace.WorkspaceArtifactActionTarget
import com.flovera.app.workspace.WorkspaceArtifactJob
import com.flovera.app.workspace.WorkspaceController
import com.flovera.app.workspace.WorkspaceControlledToolProposal
import com.flovera.app.workspace.WorkspaceFileNode
import com.flovera.app.workspace.FloveraSkillConsoleEntry
import com.flovera.app.workspace.WorkspaceLocalAppServer
import com.flovera.app.workspace.WorkspacePythonHttpRuntime
import com.flovera.app.workspace.WorkspacePythonHttpRuntimeStatus
import com.flovera.app.workspace.WorkspaceSettingsProposal
import com.flovera.app.workspace.WorkspaceSnapshot
import com.flovera.app.workspace.WorkspaceSnapshotRecord
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

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
  val workspaceArtifacts: List<WorkspaceArtifact> = emptyList(),
  val workspaceArtifactJobs: List<WorkspaceArtifactJob> = emptyList(),
  val workspaceArtifactServerStatuses: List<WorkspacePythonHttpRuntimeStatus> = emptyList(),
  val selectedHtmlPath: String = "",
  val selectedHtmlUrl: String? = null,
  val selectedHtmlLoading: Boolean = false,
  val selectedHtmlError: String = "",
  val selectedPreviewPath: String = "",
  val selectedPreviewContent: String = "",
  val selectedPreviewMimeType: String = "",
  val selectedPreviewUri: String = "",
  val workspaceRootUrl: String = "",
  val workspaceSnapshots: List<WorkspaceSnapshotRecord> = emptyList(),
  val settingsProposals: List<WorkspaceSettingsProposal> = emptyList(),
  val controlledToolProposals: List<WorkspaceControlledToolProposal> = emptyList(),
  val floveraSkills: List<FloveraSkillConsoleEntry> = emptyList(),
  val status: String = "Idle",
  val isRunning: Boolean = false,
  val assistantDraft: SessionMessage? = null,
  val queuedInputs: List<QueuedAgentInput> = emptyList(),
)

data class QueuedAgentInput(
  val content: String,
  val mode: String = QUEUED_INPUT_REQUEST,
)

private data class AgentRunInput(
  val modelInput: String,
  val visibleInput: String = modelInput,
)

private data class FullAuthoritySettingsApplyResult(
  val settings: AppSettings,
  val appliedCount: Int = 0,
)

const val QUEUED_INPUT_REQUEST = "request"
const val QUEUED_INPUT_GUIDANCE = "guidance"

private const val RUN_NOTIFICATION_MIN_INTERVAL_MS = 1_500L
private const val ASSISTANT_DRAFT_UI_UPDATE_INTERVAL_MS = 80L
private const val WORKSPACE_ARTIFACT_ACTION_PYTHON_JOB = "python_job"
private const val WORKSPACE_ARTIFACT_PREVIEW_LOCAL_HTTP = "local_http"

private fun QueuedAgentInput.toRunInput(): AgentRunInput {
  if (mode != QUEUED_INPUT_GUIDANCE) return AgentRunInput(modelInput = content)
  val modelInput = """
    Guidance while the previous agent run was active:
    $content

    Continue the current task using this guidance. If the task was already completed, revise or continue only when useful.
  """.trimIndent()
  return AgentRunInput(modelInput = modelInput, visibleInput = content)
}

private fun SessionMessage.withMergedTranscriptEvents(
  extraEvents: List<ConversationTranscriptEvent>,
): SessionMessage {
  if (extraEvents.isEmpty()) return this
  return copy(
    transcriptEvents = (transcriptEvents + extraEvents)
      .distinctBy { it.transcriptIdentityKey() }
      .sortedWith(conversationTranscriptEventComparator()),
  )
}

private fun ConversationTranscriptEvent.transcriptIdentityKey(): String {
  return listOf(type, role, content, title, detail, timestampMillis.toString(), status).joinToString("|")
}

private fun conversationTranscriptEventComparator(): Comparator<ConversationTranscriptEvent> {
  return compareBy<ConversationTranscriptEvent> { it.timestampMillis }
    .thenBy { event ->
      when (event.type) {
        "assistant_text",
        "error_text",
        "user_guidance",
        "user_text",
        "guidance" -> 0
        "tool_call" -> 2
        else -> 3
      }
    }
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
  private val uiStateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val artifactJobScope = CoroutineScope(SupervisorJob() + FloveraDispatchers.runtimeDispatcher)
  private val workspaceMutationScope = CoroutineScope(SupervisorJob() + FloveraDispatchers.workspaceMutationDispatcher)
  private val workspaceQueryScope = CoroutineScope(SupervisorJob() + FloveraDispatchers.workspaceQueryDispatcher)
  private val artifactRunJobs = ConcurrentHashMap<String, Job>()
  private var selectedHtmlLoadJob: Job? = null
  @Volatile private var selectedHtmlLoadGeneration: Long = 0
  @Volatile private var selectedPreviewLoadGeneration: Long = 0
  private var pendingAssistantDraft: SessionMessage? = null
  private var pendingAssistantDraftFlushJob: Job? = null
  private var lastAssistantDraftFlushAtMillis: Long = 0
  private var activeRunTranscriptEvents: MutableList<ConversationTranscriptEvent>? = null
  private val activeRunGuidanceLock = Any()
  private val activeRunPendingGuidance = mutableListOf<String>()
  private var lastRunNotificationAtMillis: Long = 0
  private var lastRunNotificationBody: String = ""
  private var workspaceController: WorkspaceController
  private var workspaceLocalAppServer: WorkspaceLocalAppServer
  private var workspacePythonHttpRuntime: WorkspacePythonHttpRuntime

  private val _state = MutableStateFlow(AgentScreenState())
  val state: StateFlow<AgentScreenState> = _state

  init {
    val settingsLoad = settingsController.loadResult()
    val loadedSettings = settingsLoad.settings
    workspaceController = WorkspaceController(appContext, loadedSettings.activeWorkspaceId).also { it.ensureSeedFiles() }
    workspaceLocalAppServer = WorkspaceLocalAppServer(workspaceController.runtimeWorkspace()) { _state.value.settings }
    workspacePythonHttpRuntime = WorkspacePythonHttpRuntime(workspaceController.runtimeWorkspace())
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
    val initialSelectedHtmlTarget = selectedHtmlTarget(workspaceSnapshot)
    val initialWorkspaceRootUrl = workspaceRootUrl(initialSelectedHtmlTarget.url, workspaceSnapshot)
    val initialArtifactServerStatuses = workspacePythonHttpRuntime.statusesFor(workspaceSnapshot.workspaceArtifacts)
    val initialHtmlLoading = initialSelectedHtmlTarget.requiresBackend &&
      initialSelectedHtmlTarget.url == null &&
      initialSelectedHtmlTarget.error.isBlank()
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
      workspaceArtifacts = workspaceSnapshot.workspaceArtifacts,
      workspaceArtifactJobs = workspaceSnapshot.workspaceArtifactJobs,
      workspaceArtifactServerStatuses = initialArtifactServerStatuses,
      selectedHtmlPath = workspaceSnapshot.selectedHtmlPath,
      selectedHtmlUrl = initialSelectedHtmlTarget.url,
      selectedHtmlLoading = initialHtmlLoading,
      selectedHtmlError = initialSelectedHtmlTarget.error,
      selectedPreviewPath = workspaceSnapshot.selectedHtmlPath,
      selectedPreviewMimeType = if (workspaceSnapshot.selectedHtmlPath.isBlank()) "" else "text/html",
      selectedPreviewUri = "",
      workspaceRootUrl = initialWorkspaceRootUrl,
      workspaceSnapshots = workspaceSnapshot.snapshots,
      settingsProposals = workspaceSnapshot.settingsProposals,
      controlledToolProposals = workspaceSnapshot.controlledToolProposals,
      floveraSkills = workspaceSnapshot.floveraSkills,
      status = settingsLoad.warning ?: "Ready",
    )
    if (initialHtmlLoading) {
      startSelectedHtmlBackend(workspaceSnapshot.selectedHtmlPath)
    }
    if (settings.backgroundKeepAliveEnabled) {
      startBackgroundKeepAliveService()
    }
  }

  fun updateInput(value: String) {
    _state.update { it.copy(input = value) }
  }

  fun updateApiKey(value: String) {
    if (rejectMutationWhileRunning("API settings")) return
    _state.update { it.copy(apiKeyDraft = value) }
  }

  fun updateProvider(value: String) {
    if (rejectMutationWhileRunning("Provider settings")) return
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
    if (rejectMutationWhileRunning("Model settings")) return
    _state.update { it.copy(modelDraft = value) }
  }

  fun updateAgentRules(value: String) {
    if (rejectMutationWhileRunning("Rule editing")) return
    _state.update { it.copy(agentRulesDraft = value) }
  }

  fun setNetworkEnabled(enabled: Boolean) {
    if (rejectMutationWhileRunning("Network settings")) return
    val current = _state.value
    val optimistic = current.settings.copy(networkEnabled = enabled, networkUserConfigured = true)
    val status = if (enabled) "Network tools enabled" else "Network tools disabled"
    _state.update {
      it.copy(
        settings = optimistic,
        status = status,
      )
    }
    launchWorkspaceMutation("Network settings", "setNetworkEnabled") {
      val settings = settingsController.setNetworkEnabled(current.settings, enabled)
      refreshWorkspaceState(settings = settings, status = status)
    }
  }

  fun setBackgroundKeepAliveEnabled(enabled: Boolean) {
    if (rejectMutationWhileRunning("Background settings")) return
    val current = _state.value
    val optimistic = current.settings.copy(backgroundKeepAliveEnabled = enabled)
    val status = if (enabled) "Background keep-alive enabled" else "Background keep-alive disabled"
    if (enabled) {
      if (!current.isRunning) startBackgroundKeepAliveService()
    } else if (!current.isRunning) {
      stopBackgroundKeepAliveService()
    }
    _state.update {
      it.copy(
        settings = optimistic,
        status = status,
      )
    }
    launchWorkspaceMutation("Background settings", "setBackgroundKeepAliveEnabled") {
      val settings = settingsController.setBackgroundKeepAlive(current.settings, enabled)
      refreshWorkspaceState(settings = settings, status = status)
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
    networkEnabled: Boolean = _state.value.settings.networkEnabled,
    webSearchEnabled: Boolean = _state.value.settings.webSearchEnabled,
    braveSearchApiKey: String = _state.value.settings.braveSearchApiKey,
    backgroundKeepAliveEnabled: Boolean = _state.value.settings.backgroundKeepAliveEnabled,
  ) {
    if (rejectMutationWhileRunning("Settings")) return
    val current = _state.value
    _state.update { it.copy(status = "Saving settings...") }
    launchWorkspaceMutation("Settings save", "saveModelSettings") {
      saveModelSettingsBlocking(
        current = current,
        providerId = providerId,
        model = model,
        apiKey = apiKey,
        customOpenAIBaseUrl = customOpenAIBaseUrl,
        customOpenAIChatCompletionsPath = customOpenAIChatCompletionsPath,
        customOpenAICompatibilityMode = customOpenAICompatibilityMode,
        language = language,
        themeMode = themeMode,
        themeColor = themeColor,
        authorityMode = authorityMode,
        deepSeekThinkingEffort = deepSeekThinkingEffort,
        networkEnabled = networkEnabled,
        webSearchEnabled = webSearchEnabled,
        braveSearchApiKey = braveSearchApiKey,
        backgroundKeepAliveEnabled = backgroundKeepAliveEnabled,
      )
    }
  }

  private fun saveModelSettingsBlocking(
    current: AgentScreenState,
    providerId: String,
    model: String,
    apiKey: String,
    customOpenAIBaseUrl: String,
    customOpenAIChatCompletionsPath: String,
    customOpenAICompatibilityMode: String,
    language: String,
    themeMode: String,
    themeColor: String,
    authorityMode: String,
    deepSeekThinkingEffort: String,
    networkEnabled: Boolean,
    webSearchEnabled: Boolean,
    braveSearchApiKey: String,
    backgroundKeepAliveEnabled: Boolean,
  ) {
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
    val settingsWithNetwork = settingsController.setNetworkEnabled(settingsWithThinking, networkEnabled)
    val settingsWithSearch = settingsController.setWebSearch(settingsWithNetwork, webSearchEnabled, braveSearchApiKey)
    val settingsWithBackground = settingsController.setBackgroundKeepAlive(settingsWithSearch, backgroundKeepAliveEnabled)
    if (backgroundKeepAliveEnabled) {
      if (!current.isRunning) startBackgroundKeepAliveService()
    } else if (!current.isRunning) {
      stopBackgroundKeepAliveService()
    }
    val draft = settingsController.draftFor(settingsWithBackground)
    workspaceController.syncFloveraSettings(settingsWithBackground)
    val workspaceSnapshot = workspaceController.snapshot(settingsWithBackground.selectedHtmlPath)
    val selectedHtmlTarget = selectedHtmlTarget(workspaceSnapshot)
    val workspaceRootUrl = workspaceRootUrl(selectedHtmlTarget.url, workspaceSnapshot)
    val artifactServerStatuses = workspacePythonHttpRuntime.statusesFor(workspaceSnapshot.workspaceArtifacts)
    val selectedHtmlLoading = selectedHtmlTarget.requiresBackend &&
      selectedHtmlTarget.url == null &&
      selectedHtmlTarget.error.isBlank() &&
      current.selectedHtmlLoading
    _state.update {
      it.copy(
        settings = settingsWithBackground,
        providerDraft = draft.providerId,
        modelDraft = draft.model,
        apiKeyDraft = draft.apiKey,
        customOpenAIBaseUrlDraft = draft.customOpenAIBaseUrl,
        customOpenAIChatCompletionsPathDraft = draft.customOpenAIChatCompletionsPath,
        customOpenAICompatibilityModeDraft = draft.customOpenAICompatibilityMode,
        workspaceFiles = workspaceSnapshot.files,
        workspaceTree = workspaceSnapshot.tree,
        htmlFiles = workspaceSnapshot.htmlFiles,
        workspaceArtifacts = workspaceSnapshot.workspaceArtifacts,
        workspaceArtifactJobs = workspaceSnapshot.workspaceArtifactJobs,
        workspaceArtifactServerStatuses = artifactServerStatuses,
        selectedHtmlPath = workspaceSnapshot.selectedHtmlPath,
        selectedHtmlUrl = selectedHtmlTarget.url,
        selectedHtmlLoading = selectedHtmlLoading,
        selectedHtmlError = selectedHtmlTarget.error,
        selectedPreviewPath = workspaceSnapshot.selectedHtmlPath,
        selectedPreviewContent = "",
        selectedPreviewMimeType = if (workspaceSnapshot.selectedHtmlPath.isBlank()) "" else "text/html",
        selectedPreviewUri = "",
        workspaceRootUrl = workspaceRootUrl,
        workspaceSnapshots = workspaceSnapshot.snapshots,
        settingsProposals = workspaceSnapshot.settingsProposals,
        controlledToolProposals = workspaceSnapshot.controlledToolProposals,
        status = "Settings saved",
      )
    }
  }

  fun saveAgentRules(content: String = _state.value.agentRulesDraft) {
    if (rejectMutationWhileRunning("Rule editing")) return
    _state.update { it.copy(agentRulesDraft = content, status = "Saving rule...") }
    launchWorkspaceMutation("Rule save", "saveAgentRules") {
      workspaceController.writeAgentRules(content)
      refreshWorkspaceState(status = "Rule saved")
      _state.update { it.copy(agentRulesDraft = content) }
    }
  }

  fun selectHtmlFile(path: String) {
    selectedPreviewLoadGeneration += 1
    val current = _state.value
    _state.update {
      it.copy(
        selectedHtmlPath = path,
        selectedPreviewPath = path,
        selectedPreviewContent = "",
        selectedPreviewMimeType = if (path.isBlank()) "" else "text/html",
        selectedPreviewUri = "",
        selectedHtmlLoading = path.isNotBlank(),
        status = "Displaying $path",
      )
    }
    launchWorkspaceMutation("Preview selection", "selectHtmlFile") {
      val settings = settingsController.setSelectedHtml(current.settings, path)
      refreshWorkspaceState(
        settings = settings,
        status = "Displaying $path",
        resetPreviewToSelectedHtml = true,
        startSelectedHtmlBackend = true,
      )
    }
  }

  fun clearWorkspacePreview(status: String = "Preview closed") {
    selectedPreviewLoadGeneration += 1
    val current = _state.value
    _state.update {
      it.copy(
        selectedHtmlPath = "",
        selectedHtmlUrl = null,
        selectedHtmlLoading = false,
        selectedHtmlError = "",
        selectedPreviewPath = "",
        selectedPreviewContent = "",
        selectedPreviewMimeType = "",
        selectedPreviewUri = "",
        workspaceRootUrl = workspaceController.runtimeWorkspace().rootUrl(),
      )
    }
    launchWorkspaceMutation("Preview selection", "clearWorkspacePreview") {
      val settings = settingsController.setSelectedHtml(current.settings, "")
      refreshWorkspaceState(settings = settings, status = status)
    }
  }

  fun selectWorkspacePreview(path: String) {
    if (path.endsWith(".html", ignoreCase = true) || path.endsWith(".htm", ignoreCase = true)) {
      selectHtmlFile(path)
      return
    }
    selectedPreviewLoadGeneration += 1
    val generation = selectedPreviewLoadGeneration
    _state.update {
      it.copy(
        selectedPreviewPath = path,
        selectedPreviewContent = "",
        selectedPreviewMimeType = "",
        selectedPreviewUri = "",
        status = "Loading preview $path",
      )
    }
    workspaceQueryScope.launch {
      runCatching {
        FloveraPerformance.trace("workspace-query", "selectWorkspacePreview") {
          workspacePreviewSelection(path)
        }
      }.onSuccess { preview ->
        _state.update {
          if (generation != selectedPreviewLoadGeneration) {
            it
          } else {
            it.copy(
              selectedPreviewPath = preview.path,
              selectedPreviewContent = preview.content,
              selectedPreviewMimeType = preview.mimeType,
              selectedPreviewUri = preview.uri,
              status = preview.status,
            )
          }
        }
      }.onFailure { throwable ->
        val reason = throwable.message?.takeIf { it.isNotBlank() } ?: throwable::class.java.simpleName
        _state.update {
          if (generation != selectedPreviewLoadGeneration) {
            it
          } else {
            it.copy(status = "Preview failed: $reason")
          }
        }
      }
    }
  }

  fun setHtmlPinned(path: String, pinned: Boolean) {
    if (rejectMutationWhileRunning("Preview pinning")) return
    val status = if (pinned) "HTML pinned" else "HTML unpinned"
    _state.update { it.copy(status = status) }
    val current = _state.value
    launchWorkspaceMutation("Preview pinning", "setHtmlPinned") {
      val settings = settingsController.setPinnedHtmlPath(current.settings, path, pinned)
      refreshWorkspaceState(settings = settings, status = status)
    }
  }

  fun refreshWorkspaceFiles() {
    _state.update { it.copy(status = "Refreshing workspace...") }
    launchWorkspaceMutation("Workspace refresh", "refreshWorkspaceFiles") {
      refreshWorkspaceState(status = "Workspace refreshed")
    }
  }

  fun reportStatus(status: String) {
    _state.update { it.copy(status = status) }
  }

  private fun launchWorkspaceMutation(surface: String, taskName: String, block: () -> Unit) {
    workspaceMutationScope.launch {
      runCatching {
        FloveraPerformance.trace("workspace-mutation", taskName, block)
      }.onFailure { throwable ->
        reportBackgroundMutationFailure(surface, throwable)
      }
    }
  }

  private fun reportBackgroundMutationFailure(surface: String, throwable: Throwable) {
    val reason = throwable.message?.takeIf { it.isNotBlank() } ?: throwable::class.java.simpleName
    _state.update { it.copy(status = "$surface failed: $reason") }
  }

  private fun rejectMutationWhileRunning(surface: String): Boolean {
    if (!_state.value.isRunning) return false
    _state.update {
      it.copy(status = "$surface is locked while the agent is running")
    }
    return true
  }

  fun runWorkspaceArtifactAction(actionId: String, inputJson: String): String {
    if (rejectMutationWhileRunning("Artifact actions")) {
      return artifactBridgeError("artifact actions are unavailable while the agent is running")
    }
    val trimmedActionId = actionId.trim()
    if (trimmedActionId.isBlank()) return artifactBridgeError("missing action id")
    val previewPath = _state.value.selectedPreviewPath.ifBlank { _state.value.selectedHtmlPath }
    val target = workspaceController.resolveWorkspaceArtifactAction(previewPath, trimmedActionId)
      ?: return artifactBridgeError("artifact action not found or ambiguous: $trimmedActionId")
    return startWorkspaceArtifactAction(target, inputJson)
  }

  private fun startWorkspaceArtifactAction(target: WorkspaceArtifactActionTarget, inputJson: String): String {
    val inputPath = target.action.inputPath
    val job = workspaceController.createWorkspaceArtifactJob(target, inputPath)
    val runJob = artifactJobScope.launch {
      executeWorkspaceArtifactJob(job.id, target, inputJson)
    }
    artifactRunJobs[job.id] = runJob
    launchWorkspaceMutation("Artifact job refresh", "startWorkspaceArtifactAction") {
      refreshWorkspaceState(status = "Artifact job started: ${target.action.id}")
    }
    return workspaceController.workspaceArtifactJobJson(job.id)
  }

  fun getWorkspaceArtifactJob(jobId: String): String {
    return workspaceController.workspaceArtifactJobJson(jobId.trim())
  }

  fun cancelWorkspaceArtifactJob(jobId: String): String {
    val id = jobId.trim()
    val running = artifactRunJobs.remove(id)
    running?.cancel()
    val current = workspaceController.readWorkspaceArtifactJob(id) ?: return artifactBridgeError("artifact job not found: $id")
    val canceled = workspaceController.updateWorkspaceArtifactJob(
      current.copy(
        status = "cancelled",
        error = "Cancellation requested by WebView.",
      ),
    )
    launchWorkspaceMutation("Artifact job refresh", "cancelWorkspaceArtifactJob") {
      refreshWorkspaceState(status = "Artifact job cancelled: ${canceled.actionId}")
    }
    return workspaceController.workspaceArtifactJobJson(id)
  }

  fun rerunWorkspaceArtifactJob(jobId: String) {
    if (rejectMutationWhileRunning("Artifact actions")) return
    _state.update { it.copy(status = "Rerunning artifact job...") }
    launchWorkspaceMutation("Artifact rerun", "rerunWorkspaceArtifactJob") {
      val job = workspaceController.readWorkspaceArtifactJob(jobId) ?: return@launchWorkspaceMutation reportStatus("Artifact job not found")
      val target = workspaceController.resolveWorkspaceArtifactActionByManifest(job.artifactManifestPath, job.actionId)
        ?: return@launchWorkspaceMutation reportStatus("Artifact action not found: ${job.actionId}")
      val inputJson = job.inputPath.takeIf { it.isNotBlank() }?.let { path ->
        workspaceController.previewTextFile(path).takeUnless { it.startsWith("File does not exist:") }
      }.orEmpty()
      startWorkspaceArtifactAction(target, inputJson)
      refreshWorkspaceState(status = "Artifact job rerun started: ${job.actionId}")
    }
  }

  fun stopWorkspaceArtifactServer(manifestPath: String) {
    if (rejectMutationWhileRunning("Artifact server changes")) return
    _state.update { it.copy(status = "Stopping artifact server...") }
    launchWorkspaceMutation("Artifact server stop", "stopWorkspaceArtifactServer") {
      val stopped = workspacePythonHttpRuntime.stopManifest(manifestPath)
      _state.update {
        it.copy(
          workspaceArtifactServerStatuses = workspacePythonHttpRuntime.statusesFor(it.workspaceArtifacts),
          status = if (stopped) "Artifact server stopped" else "Artifact server was not running",
        )
      }
    }
  }

  fun renameWorkspacePath(path: String, newName: String) {
    if (rejectMutationWhileRunning("Workspace changes")) return
    _state.update { it.copy(status = "Renaming workspace path...") }
    launchWorkspaceMutation("Workspace rename", "renameWorkspacePath") {
      val status = workspaceController.rename(path, newName)
      refreshWorkspaceState(status = status)
    }
  }

  fun deleteWorkspacePath(path: String) {
    if (rejectMutationWhileRunning("Workspace changes")) return
    val resetPreview = _state.value.selectedPreviewPath == path
    _state.update { it.copy(status = "Deleting workspace path...") }
    launchWorkspaceMutation("Workspace delete", "deleteWorkspacePath") {
      val status = workspaceController.deletePath(path)
      refreshWorkspaceState(
        status = status,
        resetPreviewToSelectedHtml = resetPreview,
      )
    }
  }

  fun createWorkspaceSnapshot(name: String) {
    if (rejectMutationWhileRunning("Snapshot changes")) return
    val current = _state.value
    _state.update { it.copy(status = "Creating snapshot...") }
    launchWorkspaceMutation("Snapshot create", "createWorkspaceSnapshot") {
      val snapshot = workspaceController.createSnapshot(name, current.selectedHtmlPath)
      refreshWorkspaceState(status = "Snapshot created: ${snapshot.name}")
    }
  }

  fun restoreWorkspaceSnapshot(snapshotId: String) {
    if (rejectMutationWhileRunning("Snapshot restore")) return
    val current = _state.value
    _state.update { it.copy(status = "Restoring snapshot...") }
    launchWorkspaceMutation("Snapshot restore", "restoreWorkspaceSnapshot") {
      val restored = workspaceController.restoreSnapshot(snapshotId)
        ?: return@launchWorkspaceMutation reportStatus("Snapshot not found")
      val settings = if (restored.selectedHtmlPath.isBlank()) {
        current.settings
      } else {
        settingsController.setSelectedHtml(current.settings, restored.selectedHtmlPath)
      }
      refreshWorkspaceState(settings = settings, status = "Snapshot restored: ${restored.name}")
    }
  }

  fun deleteWorkspaceSnapshot(snapshotId: String) {
    if (rejectMutationWhileRunning("Snapshot changes")) return
    _state.update { it.copy(status = "Deleting snapshot...") }
    launchWorkspaceMutation("Snapshot delete", "deleteWorkspaceSnapshot") {
      val deleted = workspaceController.deleteSnapshot(snapshotId)
      refreshWorkspaceState(status = if (deleted) "Snapshot deleted" else "Snapshot could not be deleted")
    }
  }

  fun approveSettingsProposal(path: String) {
    if (rejectMutationWhileRunning("Settings proposals")) return
    val current = _state.value
    _state.update { it.copy(status = "Applying settings proposal...") }
    launchWorkspaceMutation("Settings proposal apply", "approveSettingsProposal") {
      val proposal = workspaceController.listSettingsProposals().firstOrNull { it.path == path }
      if (proposal == null) {
        _state.update { it.copy(status = "Settings proposal not found") }
      } else {
        val settings = settingsController.applySettingsProposal(current.settings, proposal.changes)
        workspaceController.deleteSettingsProposal(path)
        refreshWorkspaceState(settings = settings, status = "Settings proposal applied: ${proposal.title}")
      }
    }
  }

  fun rejectSettingsProposal(path: String) {
    if (rejectMutationWhileRunning("Settings proposals")) return
    _state.update { it.copy(status = "Rejecting settings proposal...") }
    launchWorkspaceMutation("Settings proposal reject", "rejectSettingsProposal") {
      val deleted = workspaceController.deleteSettingsProposal(path)
      refreshWorkspaceState(status = if (deleted) "Settings proposal rejected" else "Settings proposal not found")
    }
  }

  fun dismissControlledToolProposal(path: String) {
    if (rejectMutationWhileRunning("Tool proposals")) return
    _state.update { it.copy(status = "Dismissing tool proposal...") }
    launchWorkspaceMutation("Tool proposal dismiss", "dismissControlledToolProposal") {
      val deleted = workspaceController.deleteControlledToolProposal(path)
      refreshWorkspaceState(status = if (deleted) "Tool proposal dismissed" else "Tool proposal not found")
    }
  }

  fun setFloveraSkillEnabled(id: String, enabled: Boolean) {
    if (rejectMutationWhileRunning("Skill settings")) return
    val status = "Skill ${if (enabled) "enabled" else "disabled"}: $id"
    _state.update { it.copy(status = status) }
    launchWorkspaceMutation("Skill settings", "setFloveraSkillEnabled") {
      val updated = workspaceController.setFloveraSkillEnabled(id, enabled)
      refreshWorkspaceState(status = if (updated) status else "Skill not found: $id")
    }
  }

  fun saveWorkspaceSecret(
    originalName: String,
    name: String,
    label: String,
    description: String,
    value: String,
    agentAllowed: Boolean,
  ) {
    if (rejectMutationWhileRunning("Secret settings")) return
    val current = _state.value
    _state.update { it.copy(status = "Saving secret...") }
    launchWorkspaceMutation("Secret save", "saveWorkspaceSecret") {
      val settings = settingsController.saveWorkspaceSecret(
        settings = current.settings,
        originalName = originalName,
        name = name,
        label = label,
        description = description,
        value = value,
        agentAllowed = agentAllowed,
      )
      refreshWorkspaceState(settings = settings, status = "Secret saved: ${name.trim()}")
    }
  }

  fun deleteWorkspaceSecret(name: String) {
    if (rejectMutationWhileRunning("Secret settings")) return
    val current = _state.value
    _state.update { it.copy(status = "Deleting secret...") }
    launchWorkspaceMutation("Secret delete", "deleteWorkspaceSecret") {
      val settings = settingsController.deleteWorkspaceSecret(current.settings, name)
      refreshWorkspaceState(settings = settings, status = "Secret deleted: ${name.trim()}")
    }
  }

  fun setWorkspaceSecretAgentAllowed(name: String, allowed: Boolean) {
    if (rejectMutationWhileRunning("Secret settings")) return
    val current = _state.value
    val status = "Secret ${if (allowed) "enabled" else "disabled"} for agent: ${name.trim()}"
    _state.update { it.copy(status = status) }
    launchWorkspaceMutation("Secret settings", "setWorkspaceSecretAgentAllowed") {
      val settings = settingsController.setWorkspaceSecretAgentAllowed(current.settings, name, allowed)
      refreshWorkspaceState(settings = settings, status = status)
    }
  }

  fun workspaceFileUri(path: String): Uri? {
    val file = workspaceController.exportableFile(path) ?: return null
    return FileProvider.getUriForFile(appContext, "${appContext.packageName}.workspacefiles", file)
  }

  fun workspaceMimeType(path: String): String = workspaceController.mimeType(path)

  fun importSharedIntent(intent: Intent?): Boolean {
    if (rejectMutationWhileRunning("File import")) return false
    val uris = sharedUris(intent)
    if (uris.isEmpty()) return false
    _state.update { it.copy(status = "Importing shared file...") }
    launchWorkspaceMutation("File import", "importSharedIntent") {
      val results = uris.map { uri -> workspaceController.importSharedFile(uri) }
      refreshWorkspaceState(status = results.joinToString("; "))
    }
    return true
  }

  fun importConversationAttachments(uris: List<Uri>) {
    if (uris.isEmpty()) return
    if (rejectMutationWhileRunning("File import")) return
    _state.update { it.copy(status = "Importing attachment...") }
    launchWorkspaceMutation("File import", "importConversationAttachments") {
      val results = uris.map { uri -> workspaceController.importSharedFile(uri) }
      val importedPaths = results.mapNotNull { result ->
        result.removePrefix("Imported ").takeIf { it != result && it.isNotBlank() }
      }
      val attachmentText = if (importedPaths.isNotEmpty()) {
        if (_state.value.settings.language == "zh") {
        "已导入 workspace 文件：\n" + importedPaths.joinToString("\n") { "- $it" }
        } else {
          "Imported workspace file(s):\n" + importedPaths.joinToString("\n") { "- $it" }
        }
      } else {
        results.joinToString("\n")
      }
      refreshWorkspaceState(
        input = appendInputText(_state.value.input, attachmentText),
        status = results.joinToString("; "),
      )
    }
  }

  fun appendInputText(text: String) {
    val normalized = text.trim()
    if (normalized.isBlank()) return
    _state.update {
      it.copy(
        input = appendInputText(it.input, normalized),
        status = if (it.settings.language == "zh") "已添加到输入框" else "Added to input",
      )
    }
  }

  fun newSession() {
    if (rejectMutationWhileRunning("Session changes")) return
    _state.update { it.copy(status = "Creating session...") }
    launchWorkspaceMutation("Session create", "newSession") {
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
  }

  fun discardEmptyDraftSession() {
    if (rejectMutationWhileRunning("Session changes")) return
    val current = _state.value
    val session = current.session ?: return
    if (session.messages.isNotEmpty()) return
    _state.update { it.copy(status = "Closing draft session...") }
    launchWorkspaceMutation("Session discard", "discardEmptyDraftSession") {
      val fallback = sessionController.nextUsableSession()
      val settings = settingsController.setActiveSession(current.settings, fallback?.id)
      refreshWorkspaceState(
        settings = settings,
        session = fallback,
        isRunning = false,
        status = if (fallback == null) "No active session" else "Session loaded",
      )
    }
  }

  fun openSession(sessionId: String) {
    if (rejectMutationWhileRunning("Session switching")) return
    val current = _state.value
    _state.update { it.copy(status = "Loading session...") }
    launchWorkspaceMutation("Session switching", "openSession") {
      val session = sessionController.openSession(sessionId)
        ?: return@launchWorkspaceMutation reportStatus("Session not found")
      val settings = settingsController.setActiveSession(current.settings, session.id)
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
  }

  fun renameSession(sessionId: String, title: String) {
    if (rejectMutationWhileRunning("Session changes")) return
    val currentSession = _state.value.session
    _state.update { it.copy(status = "Renaming session...") }
    launchWorkspaceMutation("Session rename", "renameSession") {
      val renamed = sessionController.renameSession(sessionId, title)
        ?: return@launchWorkspaceMutation reportStatus("Session not found")
      val active = if (currentSession?.id == renamed.id) renamed else currentSession
      _state.update {
        it.copy(
          session = active,
          sessions = sessionController.listActive(),
          archivedSessions = sessionController.listArchived(),
          status = "Session renamed",
        )
      }
    }
  }

  fun duplicateSession(sessionId: String) {
    if (rejectMutationWhileRunning("Session changes")) return
    _state.update { it.copy(status = "Copying session...") }
    launchWorkspaceMutation("Session duplicate", "duplicateSession") {
      val copy = sessionController.duplicateSession(sessionId)
        ?: return@launchWorkspaceMutation reportStatus("Session not found")
      activateSession(copy, "Session copied")
    }
  }

  fun archiveSession(sessionId: String) {
    if (rejectMutationWhileRunning("Session changes")) return
    val current = _state.value
    _state.update { it.copy(status = "Archiving session...") }
    launchWorkspaceMutation("Session archive", "archiveSession") {
      sessionController.archiveSession(sessionId)
        ?: return@launchWorkspaceMutation reportStatus("Session not found")
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
  }

  fun restoreSession(sessionId: String) {
    if (rejectMutationWhileRunning("Session changes")) return
    _state.update { it.copy(status = "Restoring session...") }
    launchWorkspaceMutation("Session restore", "restoreSession") {
      val restored = sessionController.restoreSession(sessionId)
        ?: return@launchWorkspaceMutation reportStatus("Session not found")
      activateSession(restored, "Session restored")
    }
  }

  fun setSessionPinned(sessionId: String, pinned: Boolean) {
    if (rejectMutationWhileRunning("Session changes")) return
    val currentSession = _state.value.session
    _state.update { it.copy(status = if (pinned) "Pinning session..." else "Unpinning session...") }
    launchWorkspaceMutation("Session pin", "setSessionPinned") {
      val updated = sessionController.setSessionPinned(sessionId, pinned)
        ?: return@launchWorkspaceMutation reportStatus("Session not found")
      val active = if (currentSession?.id == updated.id) updated else currentSession
      _state.update {
        it.copy(
          session = active,
          sessions = sessionController.listActive(),
          archivedSessions = sessionController.listArchived(),
          status = if (pinned) "Session pinned" else "Session unpinned",
        )
      }
    }
  }

  fun revertSessionToMessage(messageIndex: Int) {
    val current = _state.value
    if (current.isRunning) return
    val session = current.session ?: return
    val selectedMessage = session.messages.getOrNull(messageIndex) ?: return
    if (selectedMessage.role != "user") return
    _state.update { it.copy(status = "Reverting conversation...") }
    launchWorkspaceMutation("Session revert", "revertSessionToMessage") {
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
        return@launchWorkspaceMutation
      }
      if (restored == null) {
        reportStatus("Conversation could not be reverted")
        return@launchWorkspaceMutation
      }
      refreshWorkspaceState(
        session = restored,
        input = selectedMessage.content,
        isRunning = false,
        status = "Conversation reverted",
      )
    }
  }

  fun submit() {
    val current = _state.value
    val trimmed = current.input.trim()
    if (trimmed.isBlank()) return
    if (current.isRunning) {
      enqueueInput(trimmed, QUEUED_INPUT_REQUEST, "Message queued")
      return
    }
    startAgentRun(AgentRunInput(modelInput = trimmed), current.session ?: sessionController.createSession())
  }

  fun submitInNewSession(input: String) {
    val trimmed = input.trim()
    if (trimmed.isBlank() || _state.value.isRunning) return
    startAgentRun(AgentRunInput(modelInput = trimmed), sessionController.createSession())
  }

  fun guideAgentRun() {
    val current = _state.value
    val trimmed = current.input.trim()
    if (!current.isRunning || trimmed.isBlank()) return
    queueGuidanceForActiveRun(trimmed)
    agentRunStatusNotifier.running("Guidance queued; waiting for the next tool result.")
    _state.update {
      it.copy(
        input = "",
        status = "Guidance waiting for next tool result",
      )
    }
  }

  fun markQueuedInputAsGuidance(index: Int) {
    val guidance = _state.value.queuedInputs.getOrNull(index)?.content ?: return
    if (_state.value.isRunning) {
      queueGuidanceForActiveRun(guidance)
      agentRunStatusNotifier.running("Guidance queued; waiting for the next tool result.")
      _state.update {
        it.copy(
          queuedInputs = it.queuedInputs.filterIndexed { itemIndex, _ -> itemIndex != index },
          status = "Guidance waiting for next tool result",
        )
      }
      return
    }
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

  private fun startAgentRun(input: AgentRunInput, session: AgentSession) {
    val current = _state.value
    clearPendingActiveRunGuidance()
    val runTranscriptEvents = mutableListOf<ConversationTranscriptEvent>()
    activeRunTranscriptEvents = runTranscriptEvents
    activeRunJob = agentRunController.submit(
      input = input.modelInput,
      visibleInput = input.visibleInput,
      settings = current.settings,
      session = session,
      workspace = workspaceController.runtimeWorkspace(),
      appendUserPrompt = sessionController::appendUserPrompt,
      appendContextRecord = sessionController::appendContextRecord,
      appendCompressionDivider = sessionController::appendCompressionDivider,
      appendPromptContextBlocks = sessionController::appendPromptContextBlocks,
      appendMessage = sessionController::appendMessage,
      additionalTranscriptEvents = { runTranscriptEvents.toList() },
      guidanceProvider = { consumeGuidanceForActiveRun() },
      onStarted = { withUser, draft ->
        val settings = settingsController.setActiveSession(current.settings, withUser.id)
        notifyAgentRunRunning(draft.content, force = true)
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
        notifyAgentRunRunning(draft.content.lineSequence().firstOrNull().orEmpty().ifBlank { "Working..." })
        publishAssistantDraftThrottled(draft)
      },
      onSessionUpdated = { updatedSession, draft ->
        flushPendingAssistantDraftNow()
        notifyAgentRunRunning(draft.content)
        _state.update {
          it.copy(
            session = updatedSession,
            sessions = sessionController.listActive(),
            assistantDraft = draft,
          )
        }
      },
      onFinished = { updated, succeeded ->
        flushPendingAssistantDraftNow()
        activeRunJob = null
        val unappliedGuidance = drainPendingActiveRunGuidance()
        if (activeRunTranscriptEvents === runTranscriptEvents) {
          activeRunTranscriptEvents = null
        }
        val status = if (succeeded) "Agent loop completed" else "Agent loop failed"
        val queuedInputs = _state.value.queuedInputs
        val nextInput = if (unappliedGuidance.isNotEmpty()) {
          QueuedAgentInput(content = unappliedGuidance.joinToString("\n\n"), mode = QUEUED_INPUT_GUIDANCE)
        } else {
          queuedInputs.firstOrNull()
        }
        if (nextInput == null) {
          resetAgentRunNotificationThrottle()
          agentRunStatusNotifier.finished(succeeded)
          if (_state.value.settings.backgroundKeepAliveEnabled) {
            startBackgroundKeepAliveService()
          }
          refreshWorkspaceState(
            session = updated,
            isRunning = false,
            status = status,
          )
        } else {
          _state.update {
            it.copy(
              queuedInputs = if (unappliedGuidance.isNotEmpty()) it.queuedInputs else it.queuedInputs.drop(1),
            )
          }
          notifyAgentRunRunning("Running queued message...", force = true)
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

  private fun queueGuidanceForActiveRun(guidance: String) {
    synchronized(activeRunGuidanceLock) {
      activeRunPendingGuidance += guidance
    }
    recordGuidanceQueuedForActiveRun()
  }

  private fun notifyAgentRunRunning(message: String, force: Boolean = false) {
    val body = message.ifBlank { "Working..." }
    val now = System.currentTimeMillis()
    if (!force &&
      body == lastRunNotificationBody &&
      now - lastRunNotificationAtMillis < RUN_NOTIFICATION_MIN_INTERVAL_MS
    ) {
      return
    }
    if (!force && now - lastRunNotificationAtMillis < RUN_NOTIFICATION_MIN_INTERVAL_MS) {
      return
    }
    lastRunNotificationBody = body
    lastRunNotificationAtMillis = now
    agentRunStatusNotifier.running(body)
  }

  private fun publishAssistantDraftThrottled(draft: SessionMessage) {
    pendingAssistantDraft = draft
    val now = System.currentTimeMillis()
    val elapsed = now - lastAssistantDraftFlushAtMillis
    if (elapsed >= ASSISTANT_DRAFT_UI_UPDATE_INTERVAL_MS && pendingAssistantDraftFlushJob?.isActive != true) {
      flushPendingAssistantDraftNow()
      return
    }
    if (pendingAssistantDraftFlushJob?.isActive == true) return
    val delayMillis = (ASSISTANT_DRAFT_UI_UPDATE_INTERVAL_MS - elapsed).coerceAtLeast(16L)
    pendingAssistantDraftFlushJob = uiStateScope.launch {
      delay(delayMillis)
      flushPendingAssistantDraftNow()
    }
  }

  private fun flushPendingAssistantDraftNow() {
    pendingAssistantDraftFlushJob?.cancel()
    pendingAssistantDraftFlushJob = null
    val draft = pendingAssistantDraft ?: return
    pendingAssistantDraft = null
    lastAssistantDraftFlushAtMillis = System.currentTimeMillis()
    _state.update {
      it.copy(assistantDraft = draft)
    }
  }

  private fun resetAgentRunNotificationThrottle() {
    lastRunNotificationAtMillis = 0
    lastRunNotificationBody = ""
  }

  private fun startBackgroundKeepAliveService() {
    runCatching {
      ContextCompat.startForegroundService(
        appContext,
        AgentRunForegroundService.keepAliveIntent(appContext),
      )
    }
  }

  private fun stopBackgroundKeepAliveService() {
    runCatching {
      appContext.startService(AgentRunForegroundService.stopKeepAliveIntent(appContext))
    }
  }

  private fun reconcileBackgroundKeepAlive(settings: AppSettings, isRunning: Boolean) {
    if (settings.backgroundKeepAliveEnabled) {
      if (!isRunning) startBackgroundKeepAliveService()
    } else if (!isRunning) {
      stopBackgroundKeepAliveService()
    }
  }

  private fun clearPendingActiveRunGuidance() {
    synchronized(activeRunGuidanceLock) {
      activeRunPendingGuidance.clear()
    }
  }

  private fun drainPendingActiveRunGuidance(): List<String> {
    return synchronized(activeRunGuidanceLock) {
      val pending = activeRunPendingGuidance.toList()
      activeRunPendingGuidance.clear()
      pending
    }
  }

  private fun consumeGuidanceForActiveRun(): List<String> {
    val guidance = drainPendingActiveRunGuidance()
    if (guidance.isNotEmpty()) {
      recordGuidanceAppliedForActiveRun(guidance)
    }
    return guidance
  }

  private fun recordGuidanceAppliedForActiveRun(guidanceItems: List<String>) {
    flushPendingAssistantDraftNow()
    val events = activeRunTranscriptEvents ?: return
    val now = System.currentTimeMillis()
    guidanceItems.forEach { guidance ->
      events += ConversationTranscriptEvent(
        type = "user_guidance",
        role = "user",
        content = guidance,
        timestampMillis = now,
      )
      events += ConversationTranscriptEvent(
        type = "guidance",
        title = "Guidance applied",
        detail = "Inserted after a completed tool result and before the next model request.",
        timestampMillis = now,
        status = "applied",
      )
    }
    _state.update {
      it.copy(
        assistantDraft = it.assistantDraft?.withMergedTranscriptEvents(events),
        status = "Guidance applied",
      )
    }
  }

  private fun recordGuidanceQueuedForActiveRun() {
    flushPendingAssistantDraftNow()
    val events = activeRunTranscriptEvents ?: return
    val now = System.currentTimeMillis()
    events += ConversationTranscriptEvent(
      type = "guidance",
      title = "Guidance waiting",
      detail = "Will be inserted after the next completed tool result.",
      timestampMillis = now,
      status = "queued",
      compact = true,
    )
    _state.update {
      it.copy(
        assistantDraft = it.assistantDraft?.withMergedTranscriptEvents(events),
        status = "Guidance waiting for next tool result",
      )
    }
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
    flushPendingAssistantDraftNow()
    val current = _state.value
    if (!current.isRunning) return
    activeRunJob?.cancel()
    activeRunJob = null
    clearPendingActiveRunGuidance()
    resetAgentRunNotificationThrottle()
    val now = System.currentTimeMillis()
    val interruptTimelineEvent = AgentRunTimelineEvent(
      type = AgentRunEventType.RUN_INTERRUPTED,
      title = "Run interrupted",
      detail = "The active agent run was cancelled by the user; partial transcript and tool history were saved.",
      status = "interrupted",
      compact = false,
    )
    val interruptTranscriptEvent = ConversationTranscriptEvent(
      type = AgentRunEventType.RUN_INTERRUPTED,
      title = "Run interrupted",
      detail = "The active agent run was cancelled by the user; partial transcript and tool history were saved.",
      timestampMillis = now,
      status = "interrupted",
      compact = false,
    )
    val activeTranscriptEvents = activeRunTranscriptEvents?.toList().orEmpty()
    activeRunTranscriptEvents = null
    val interruptedTranscriptEvents = (current.assistantDraft?.transcriptEvents.orEmpty() +
      activeTranscriptEvents +
      interruptTranscriptEvent)
      .distinctBy { it.transcriptIdentityKey() }
      .sortedWith(conversationTranscriptEventComparator())
    val interrupted = current.session?.let { session ->
      sessionController.appendMessage(
        session,
        SessionMessage(
          role = "assistant",
          content = "",
          toolEvents = current.assistantDraft?.toolEvents.orEmpty(),
          runEvents = current.assistantDraft?.runEvents.orEmpty() + interruptTimelineEvent,
          transcriptEvents = interruptedTranscriptEvents.ifEmpty { listOf(interruptTranscriptEvent) },
        ),
      )
    }
    agentRunStatusNotifier.interrupted()
    if (_state.value.settings.backgroundKeepAliveEnabled) {
      startBackgroundKeepAliveService()
    }
    refreshWorkspaceState(
      session = interrupted ?: current.session,
      isRunning = false,
      status = "Agent loop interrupted",
    )
  }

  private suspend fun executeWorkspaceArtifactJob(jobId: String, target: WorkspaceArtifactActionTarget, inputJson: String) {
    val started = workspaceController.readWorkspaceArtifactJob(jobId) ?: return
    workspaceController.updateWorkspaceArtifactJob(started.copy(status = "running", error = ""))
    val result = runCatching {
      require(target.action.kind == WORKSPACE_ARTIFACT_ACTION_PYTHON_JOB) {
        "Unsupported artifact action kind: ${target.action.kind}"
      }
      val tokens = splitWorkspaceArtifactCommand(target.action.command)
      require(tokens.size >= 2) { "python_job command must look like: python path/to/script.py [args...]" }
      require(tokens.first() == "python" || tokens.first() == "python3") {
        "Unsupported python_job command launcher '${tokens.first()}'. Use python or python3."
      }
      val scriptPath = tokens[1]
      val argv = tokens.drop(2)
      val inputPath = declaredInputPath(target, inputJson, argv)
      if (inputPath.isNotBlank()) {
        val writtenInputPath = workspaceController.writeWorkspaceArtifactInput(jobId, target.artifact.rootPath, inputPath, inputJson)
        workspaceController.readWorkspaceArtifactJob(jobId)?.let { currentJob ->
          workspaceController.updateWorkspaceArtifactJob(currentJob.copy(inputPath = writtenInputPath))
        }
      }
      ensureWorkspaceArtifactOutputDirectories(target)
      FloveraPythonRuntime(
        workspaceController.runtimeWorkspace(),
        networkEnabled = target.action.networkEnabled,
      ).runScript(
        scriptPath = scriptPath,
        argv = argv,
        cwd = target.action.cwd,
        timeoutMs = target.action.timeoutMs,
        sessionId = "artifact-$jobId",
        environment = workspaceArtifactActionEnvironment(target),
      )
    }
    val current = workspaceController.readWorkspaceArtifactJob(jobId) ?: return
    val finished = result.fold(
      onSuccess = { pythonResult ->
        current.copy(
          status = if (pythonResult.exitCode == 0) "succeeded" else pythonResult.status.ifBlank { "failed" },
          stdout = pythonResult.stdout,
          stderr = pythonResult.stderr,
          stdoutTruncated = pythonResult.stdoutTruncated,
          stderrTruncated = pythonResult.stderrTruncated,
          exitCode = pythonResult.exitCode,
          elapsedMs = pythonResult.elapsedMs,
          error = if (pythonResult.exitCode == 0) "" else pythonResult.stderr.take(500),
        )
      },
      onFailure = { error ->
        current.copy(
          status = "failed",
          exitCode = 1,
          error = error.message ?: error::class.java.simpleName,
        )
      },
    )
    workspaceController.updateWorkspaceArtifactJob(finished)
    artifactRunJobs.remove(jobId)
    refreshWorkspaceState(status = "Artifact job ${finished.status}: ${target.action.id}")
  }

  private fun declaredInputPath(
    target: WorkspaceArtifactActionTarget,
    inputJson: String,
    argv: List<String> = splitWorkspaceArtifactCommand(target.action.command).drop(2),
  ): String {
    if (inputJson.isBlank()) return ""
    if (target.action.inputPath.isNotBlank()) return target.action.inputPath
    val inputFlagIndex = argv.indexOf("--input")
    return if (inputFlagIndex >= 0 && inputFlagIndex + 1 < argv.size) argv[inputFlagIndex + 1] else ""
  }

  private fun ensureWorkspaceArtifactOutputDirectories(target: WorkspaceArtifactActionTarget) {
    val workspaceRoot = workspaceController.runtimeWorkspace().root.canonicalFile
    target.action.outputs.forEach { outputPath ->
      val outputFile = File(workspaceRoot, outputPath).canonicalFile
      if (outputFile.path == workspaceRoot.path || outputFile.path.startsWith(workspaceRoot.path + File.separator)) {
        outputFile.parentFile?.mkdirs()
      }
    }
  }

  private fun workspaceArtifactActionEnvironment(target: WorkspaceArtifactActionTarget): Map<String, String> {
    val settings = _state.value.settings
    return target.action.environment.mapValues { (_, ref) ->
      when {
        ref.startsWith("provider:") -> providerEnvironmentValue(ref.removePrefix("provider:"), settings)
        ref == "settings:model" -> settings.model
        ref.startsWith("literal:") -> ref.removePrefix("literal:")
        else -> ref
      }
    }.filterKeys { key -> key.isNotBlank() }
  }

  private fun providerEnvironmentValue(ref: String, settings: AppSettings): String {
    val providerId = ref.substringBefore('.', missingDelimiterValue = settings.provider).ifBlank { settings.provider }
    val field = ref.substringAfter('.', missingDelimiterValue = "apiKey")
    val provider = ModelProviderCatalog.findProvider(providerId) ?: return ""
    val profile = ModelProviderCatalog.runtimeProfileFor(provider, settings)
    return when (field) {
      "apiKey" -> settings.apiKeyFor(providerId)
      "baseUrl" -> profile.baseUrl
      "model" -> if (settings.provider == providerId) settings.model else provider.defaultModel
      else -> ""
    }
  }

  private fun splitWorkspaceArtifactCommand(command: String): List<String> {
    require(command.none { it == '|' || it == '<' || it == '>' || it == ';' }) {
      "python_job command does not support shell operators."
    }
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaping = false
    for (char in command.trim()) {
      when {
        escaping -> {
          current.append(char)
          escaping = false
        }
        char == '\\' -> escaping = true
        quote != null && char == quote -> quote = null
        quote != null -> current.append(char)
        char == '\'' || char == '"' -> quote = char
        char.isWhitespace() -> {
          if (current.isNotEmpty()) {
            tokens += current.toString()
            current.clear()
          }
        }
        else -> current.append(char)
      }
    }
    require(quote == null) { "python_job command has an unterminated quote." }
    if (current.isNotEmpty()) tokens += current.toString()
    return tokens
  }

  private fun artifactBridgeError(message: String): String {
    return JSONObject()
      .put("status", "error")
      .put("error", message)
      .toString()
  }

  private fun refreshWorkspaceState(
    settings: AppSettings = _state.value.settings,
    session: AgentSession? = _state.value.session,
    input: String = _state.value.input,
    isRunning: Boolean = _state.value.isRunning,
    status: String = _state.value.status,
    resetPreviewToSelectedHtml: Boolean = false,
    startSelectedHtmlBackend: Boolean = false,
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
    reconcileBackgroundKeepAlive(normalizedSettings, isRunning)
    val selectedHtmlTarget = selectedHtmlTarget(workspaceSnapshot)
    val workspaceRootUrl = workspaceRootUrl(selectedHtmlTarget.url, workspaceSnapshot)
    val artifactServerStatuses = workspacePythonHttpRuntime.statusesFor(workspaceSnapshot.workspaceArtifacts)
    val shouldStartSelectedHtmlBackend = startSelectedHtmlBackend &&
      selectedHtmlTarget.requiresBackend &&
      selectedHtmlTarget.url == null
    _state.update {
      val sameSelectedHtml = it.selectedHtmlPath == workspaceSnapshot.selectedHtmlPath
      val selectedHtmlLoading = when {
        shouldStartSelectedHtmlBackend -> true
        selectedHtmlTarget.url != null || selectedHtmlTarget.error.isNotBlank() -> false
        sameSelectedHtml -> it.selectedHtmlLoading
        else -> false
      }
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
        workspaceArtifacts = workspaceSnapshot.workspaceArtifacts,
        workspaceArtifactJobs = workspaceSnapshot.workspaceArtifactJobs,
        workspaceArtifactServerStatuses = artifactServerStatuses,
        selectedHtmlPath = workspaceSnapshot.selectedHtmlPath,
        selectedHtmlUrl = selectedHtmlTarget.url,
        selectedHtmlLoading = selectedHtmlLoading,
        selectedHtmlError = if (shouldStartSelectedHtmlBackend) "" else selectedHtmlTarget.error,
        selectedPreviewPath = previewPath,
        selectedPreviewContent = previewContent,
        selectedPreviewMimeType = previewMimeType,
        selectedPreviewUri = previewUri,
        workspaceRootUrl = workspaceRootUrl,
        workspaceSnapshots = workspaceSnapshot.snapshots,
        settingsProposals = workspaceSnapshot.settingsProposals,
        controlledToolProposals = workspaceSnapshot.controlledToolProposals,
        floveraSkills = workspaceSnapshot.floveraSkills,
        isRunning = isRunning,
        assistantDraft = if (isRunning) it.assistantDraft else null,
        status = statusAfterAuthority,
      )
    }
    if (shouldStartSelectedHtmlBackend) {
      startSelectedHtmlBackend(workspaceSnapshot.selectedHtmlPath)
    }
  }

  private fun startSelectedHtmlBackend(path: String) {
    val selectedPath = path.trim()
    if (selectedPath.isBlank()) return
    val generation = selectedHtmlLoadGeneration + 1
    selectedHtmlLoadGeneration = generation
    selectedHtmlLoadJob?.cancel()
    selectedHtmlLoadJob = artifactJobScope.launch {
      val snapshot = workspaceController.snapshot(selectedPath)
      val artifact = selectedHtmlArtifact(snapshot, selectedPath)
      if (artifact?.preview?.command.isNullOrBlank()) {
        _state.update { current ->
          if (current.selectedHtmlPath == selectedPath && selectedHtmlLoadGeneration == generation) {
            current.copy(selectedHtmlLoading = false)
          } else {
            current
          }
        }
        return@launch
      }
      val result = runCatching { workspacePythonHttpRuntime.previewUrl(artifact) }
      val refreshedSnapshot = workspaceController.snapshot(selectedPath)
      val statuses = workspacePythonHttpRuntime.statusesFor(refreshedSnapshot.workspaceArtifacts)
      _state.update { current ->
        if (current.selectedHtmlPath != selectedPath || selectedHtmlLoadGeneration != generation) {
          current
        } else {
          val url = result.getOrNull()
          val error = result.exceptionOrNull()?.let {
            "Artifact backend failed to start: ${it.message ?: it::class.java.simpleName}"
          }.orEmpty()
          current.copy(
            workspaceArtifacts = refreshedSnapshot.workspaceArtifacts,
            workspaceArtifactServerStatuses = statuses,
            selectedHtmlUrl = url,
            selectedHtmlLoading = false,
            selectedHtmlError = error,
            workspaceRootUrl = workspaceRootUrl(url, refreshedSnapshot),
            status = if (error.isBlank()) "Displaying $selectedPath" else error,
          )
        }
      }
    }
  }

  private fun selectedHtmlTarget(snapshot: WorkspaceSnapshot): SelectedHtmlTarget {
    val selectedPath = snapshot.selectedHtmlPath
    if (selectedPath.isBlank()) return SelectedHtmlTarget()
    val localHttpArtifact = selectedHtmlArtifact(snapshot, selectedPath)
    if (localHttpArtifact?.preview?.command?.isNotBlank() == true) {
      return when (val status = workspacePythonHttpRuntime.statusFor(localHttpArtifact)) {
        null -> SelectedHtmlTarget(requiresBackend = true)
        else -> when (status.state) {
          "running" -> SelectedHtmlTarget(url = status.url, requiresBackend = true)
          "error" -> SelectedHtmlTarget(
            error = "Artifact backend failed to start: ${status.detail}",
            requiresBackend = true,
          )
          else -> SelectedHtmlTarget(requiresBackend = true)
        }
      }
    }
    if (localHttpArtifact != null) return SelectedHtmlTarget(url = workspaceLocalAppServer.workspaceFileUrl(selectedPath))
    return SelectedHtmlTarget(url = snapshot.selectedHtmlUrl)
  }

  private fun selectedHtmlArtifact(snapshot: WorkspaceSnapshot, selectedPath: String): WorkspaceArtifact? {
    return snapshot.workspaceArtifacts.firstOrNull { artifact ->
      artifact.valid &&
        artifact.preview?.path == selectedPath &&
        artifact.preview.kind == WORKSPACE_ARTIFACT_PREVIEW_LOCAL_HTTP
    }
  }

  private data class SelectedHtmlTarget(
    val url: String? = null,
    val error: String = "",
    val requiresBackend: Boolean = false,
  )

  private data class WorkspacePreviewSelection(
    val path: String,
    val content: String,
    val mimeType: String,
    val uri: String,
    val status: String,
  )

  private fun workspacePreviewSelection(path: String): WorkspacePreviewSelection {
    val mimeType = workspaceController.mimeType(path)
    val isImage = mimeType.startsWith("image/")
    val isPdf = mimeType == "application/pdf" || path.endsWith(".pdf", ignoreCase = true)
    val isOfficeDocument = isOfficeDocumentPreview(path)
    val canPreviewAsText = canPreviewAsText(path, mimeType)
    val content = when {
      isImage -> ""
      isPdf -> ""
      isOfficeDocument -> ""
      canPreviewAsText -> workspaceController.previewTextFile(path)
      else -> "No built-in preview for $mimeType. Use Open with or Share from the file menu."
    }
    val uri = if (isImage || isPdf || isOfficeDocument) {
      workspaceFileUri(path)?.toString().orEmpty()
    } else {
      ""
    }
    return WorkspacePreviewSelection(
      path = path,
      content = content,
      mimeType = mimeType,
      uri = uri,
      status = "Previewing $path",
    )
  }

  private fun workspaceRootUrl(selectedHtmlUrl: String?, snapshot: WorkspaceSnapshot): String {
    return if (selectedHtmlUrl?.startsWith("http://127.0.0.1:") == true) {
      val uri = Uri.parse(selectedHtmlUrl)
      "${uri.scheme}://${uri.encodedAuthority}/"
    } else {
      snapshot.workspaceRootUrl
    }.let { root -> if (root.endsWith("/")) root else "$root/" }
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
      extension in setOf(
        "md", "markdown", "json", "js", "mjs", "cjs", "ts", "tsx", "jsx", "css", "csv",
        "xml", "kt", "kts", "java", "py", "sql", "sh", "ps1", "rb", "go", "rs", "c", "cpp", "h", "hpp",
      )
  }

  private fun isOfficeDocumentPreview(path: String): Boolean {
    return path.substringAfterLast('.', missingDelimiterValue = "").lowercase() in setOf("docx", "pptx", "xlsx")
  }

  private fun activateSession(session: AgentSession, status: String) {
    val settings = settingsController.setActiveSession(_state.value.settings, session.id)
    refreshWorkspaceState(settings = settings, session = session, isRunning = false, status = status)
  }

  private fun appendInputText(current: String, addition: String): String {
    val trimmedAddition = addition.trim()
    if (trimmedAddition.isBlank()) return current
    return if (current.isBlank()) trimmedAddition else current.trimEnd() + "\n" + trimmedAddition
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
