package com.flovera.app.workspace

import android.content.Context
import android.net.Uri
import com.flovera.app.config.AppSettings
import com.flovera.app.koog.ModelContextSpec
import com.flovera.app.koog.ModelProviderCatalog
import com.flovera.app.koog.hookIds
import java.io.File

data class WorkspaceSnapshot(
  val files: String,
  val tree: WorkspaceFileNode,
  val htmlFiles: List<String>,
  val selectedHtmlPath: String,
  val selectedHtmlUrl: String?,
  val workspaceRootUrl: String,
  val snapshots: List<WorkspaceSnapshotRecord>,
  val settingsProposals: List<WorkspaceSettingsProposal>,
  val controlledToolProposals: List<WorkspaceControlledToolProposal>,
)

class WorkspaceController(context: Context, workspaceId: String) {
  private val workspace = WorkspaceManager(context, workspaceId)

  fun ensureSeedFiles() {
    workspace.ensureSeedFiles()
  }

  fun runtimeWorkspace(): WorkspaceManager = workspace

  fun readAgentRules(): String = workspace.readAgentRules()

  fun writeAgentRules(content: String): String = workspace.writeFile("AGENT.md", content)

  fun syncFloveraSettings(settings: AppSettings) {
    val provider = ModelProviderCatalog.findProvider(settings.provider) ?: ModelProviderCatalog.defaultProvider
    val providerProfile = ModelProviderCatalog.runtimeProfileFor(provider, settings)
    val modelContext = ModelProviderCatalog.contextFor(settings)
    workspace.ensureFloveraMetadata(
      FloveraSettingsView(
        provider = provider.id,
        providerApiMode = providerProfile.apiMode.id,
        providerTransport = providerProfile.transport.id,
        providerBaseUrl = providerProfile.baseUrl,
        providerModelsUrl = providerProfile.modelsUrl,
        providerAuthType = providerProfile.authType.id,
        providerDefaultHeaderNames = providerProfile.defaultHeaders.keys.sorted(),
        providerSupportsHealthCheck = providerProfile.supportsHealthCheck,
        model = settings.model,
        activeWorkspaceId = settings.activeWorkspaceId,
        activeSessionId = settings.activeSessionId,
        selectedHtmlPath = settings.selectedHtmlPath,
        pinnedHtmlPaths = settings.pinnedHtmlPaths,
        recentHtmlPaths = settings.recentHtmlPaths,
        maxAgentIterations = settings.maxAgentIterations,
        networkEnabled = settings.networkEnabled,
        webSearchEnabled = settings.webSearchEnabled,
        language = settings.language,
        themeMode = settings.themeMode,
        themeColor = settings.themeColor,
        authorityMode = settings.agentAuthorityMode,
        deepSeekThinkingEffort = settings.deepSeekThinkingEffort,
        reasoningEffort = settings.reasoningEffort,
        customOpenAIBaseUrl = settings.customOpenAIProvider.baseUrl,
        customOpenAIChatCompletionsPath = settings.customOpenAIProvider.chatCompletionsPath,
        customOpenAICompatibilityMode = settings.customOpenAIProvider.compatibilityMode,
        openRouterProviderPreferences = settings.openRouterProvider.providerPreferences,
        openRouterMinCodingScore = settings.openRouterProvider.minCodingScore,
        providerInjectsOllamaNumCtx = providerProfile.requestProfile.injectOllamaNumCtx,
        providerInjectsOpenRouterRouting = providerProfile.requestProfile.injectOpenRouterRouting,
        providerRequestHookIds = providerProfile.requestProfile.hookIds(),
        providerRequestOmittedFields = providerProfile.requestProfile.omittedRequestFields.sorted(),
        providerRequestAddedFields = providerProfile.requestProfile.addedRequestFields.keys.sorted(),
        modelContextWindowTokens = modelContext.contextWindowTokens,
        modelContextSource = modelContext.source,
        modelSupportsReasoning = modelContext.supportsReasoning,
        tokenUsageSource = modelContext.usageSource,
        compressionThresholdPercent = modelContext.compressionThresholdPercent,
        apiKeyRef = if (settings.apiKeyFor(provider.id).isBlank()) "" else "${provider.id}.default",
        braveSearchApiKeyRef = if (settings.braveSearchApiKey.isBlank()) "" else "brave.default",
      ),
      providerProfileCatalog = ModelProviderCatalog.providers.map { provider ->
        val profile = ModelProviderCatalog.runtimeProfileFor(provider, settings)
        FloveraProviderProfileView(
          id = provider.id,
          label = provider.label,
          apiMode = profile.apiMode.id,
          transport = profile.transport.id,
          aliases = provider.aliases.sorted(),
          defaultModel = provider.defaultModel,
          suggestedModels = provider.suggestedModels,
          modelContexts = provider.modelContexts
            .toSortedMap()
            .mapValues { (_, context) -> context.toWorkspaceView() },
          baseUrl = profile.baseUrl,
          modelsUrl = profile.modelsUrl,
          authType = profile.authType.id,
          defaultHeaderNames = profile.defaultHeaders.keys.sorted(),
          supportsHealthCheck = profile.supportsHealthCheck,
          defaultMaxTokens = profile.defaultMaxTokens,
          defaultAuxModel = profile.defaultAuxModel,
          requestCompatibilityModes = if (provider.id == "custom-openai") {
            listOf("generic", "ollama")
          } else {
            listOf(profile.requestProfile.compatibilityMode)
          },
          requestHooks = profile.requestProfile.hookIds(),
          omittedRequestFields = profile.requestProfile.omittedRequestFields.sorted(),
          addedRequestFields = profile.requestProfile.addedRequestFields.keys.sorted(),
          customRequestBody = false,
        )
      },
    )
  }

  fun importSharedFile(uri: Uri): String = workspace.importUriToRoot(uri)

  fun rename(path: String, newName: String): String = workspace.rename(path, newName)

  fun exportableFile(path: String): File? = workspace.exportableFile(path)

  fun mimeType(path: String): String = workspace.mimeType(path)

  fun displayUrl(path: String): String? = workspace.displayUrl(path)

  fun previewTextFile(path: String): String = workspace.readFilePreview(path, maxChars = 128 * 1024)

  fun createSnapshot(name: String, selectedHtmlPath: String): WorkspaceSnapshotRecord {
    return workspace.createManualSnapshot(name, selectedHtmlPath)
  }

  fun restoreSnapshot(id: String): WorkspaceSnapshotRecord? = workspace.restoreSnapshot(id)

  fun deleteSnapshot(id: String): Boolean = workspace.deleteSnapshot(id)

  fun listSettingsProposals(): List<WorkspaceSettingsProposal> = workspace.listSettingsProposals()

  fun deleteSettingsProposal(path: String): Boolean = workspace.deleteSettingsProposal(path)

  fun listControlledToolProposals(): List<WorkspaceControlledToolProposal> = workspace.listControlledToolProposals()

  fun deleteControlledToolProposal(path: String): Boolean = workspace.deleteControlledToolProposal(path)

  fun snapshot(currentSelectedHtmlPath: String): WorkspaceSnapshot {
    val htmlFiles = workspace.listHtmlFiles()
    val selectedHtmlPath = chooseHtmlPath(currentSelectedHtmlPath, htmlFiles)
    return WorkspaceSnapshot(
      files = workspace.listFiles("."),
      tree = workspace.fileTree(),
      htmlFiles = htmlFiles,
      selectedHtmlPath = selectedHtmlPath,
      selectedHtmlUrl = workspace.displayUrl(selectedHtmlPath),
      workspaceRootUrl = workspace.rootUrl(),
      snapshots = workspace.listSnapshots(),
      settingsProposals = workspace.listSettingsProposals(),
      controlledToolProposals = workspace.listControlledToolProposals(),
    )
  }

  private fun chooseHtmlPath(current: String, htmlFiles: List<String>): String {
    return when {
      current in htmlFiles -> current
      else -> ""
    }
  }
}

private fun ModelContextSpec.toWorkspaceView(): FloveraModelContextView {
  return FloveraModelContextView(
    contextWindowTokens = contextWindowTokens,
    source = source,
    usageSource = usageSource,
    compressionThresholdPercent = compressionThresholdPercent,
    supportsReasoning = supportsReasoning,
  )
}
