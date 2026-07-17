package com.flovera.app.config

import com.flovera.app.koog.ModelProviderCatalog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

private const val RECENT_HTML_LIMIT = 12

data class ModelSettingsDraft(
  val providerId: String,
  val model: String,
  val apiKey: String,
  val customOpenAIBaseUrl: String = "",
  val customOpenAIChatCompletionsPath: String = "/v1/chat/completions",
  val customOpenAICompatibilityMode: String = "generic",
)

class SettingsController(private val store: SettingsStore) {
  fun load(): AppSettings {
    return loadResult().settings
  }

  fun loadResult(): SettingsLoadResult {
    return store.loadAndUpdate(::normalizeSettings)
  }

  private fun normalizeSettings(settings: AppSettings): AppSettings {
    return normalizeNetworkAndSearchDefaults(
      normalizeReasoningEffort(
        normalizeDeepSeekThinkingEffort(
          normalizeAuthorityMode(
            normalizeRunLimits(
              normalizeAppearance(
                normalizeLanguage(
                  normalizeCustomOpenAIProvider(normalizeProviderAndModel(normalizeHtmlLists(settings))),
                ),
              ),
            ),
          ),
        ),
      ).let { normalizeOpenRouterProvider(it) },
    )
  }

  fun draftFor(settings: AppSettings): ModelSettingsDraft {
    val provider = ModelProviderCatalog.findSelectableProvider(settings.provider) ?: ModelProviderCatalog.defaultProvider
    val model = settings.model.ifBlank { provider.defaultModel }
    return ModelSettingsDraft(
      providerId = provider.id,
      model = model,
      apiKey = settings.apiKeyFor(provider.id),
      customOpenAIBaseUrl = settings.customOpenAIProvider.baseUrl,
      customOpenAIChatCompletionsPath = settings.customOpenAIProvider.chatCompletionsPath,
      customOpenAICompatibilityMode = settings.customOpenAIProvider.compatibilityMode,
    )
  }

  fun draftForProvider(settings: AppSettings, providerId: String): ModelSettingsDraft? {
    val provider = ModelProviderCatalog.findSelectableProvider(providerId) ?: return null
    return ModelSettingsDraft(
      providerId = provider.id,
      model = provider.defaultModel,
      apiKey = settings.apiKeyFor(provider.id),
      customOpenAIBaseUrl = settings.customOpenAIProvider.baseUrl,
      customOpenAIChatCompletionsPath = settings.customOpenAIProvider.chatCompletionsPath,
      customOpenAICompatibilityMode = settings.customOpenAIProvider.compatibilityMode,
    )
  }

  fun saveModelSettings(settings: AppSettings, draft: ModelSettingsDraft): AppSettings {
    return store.update(settings) { latest -> buildModelSettings(latest, draft) }
  }

  fun saveModelSettingsBatch(
    settings: AppSettings,
    draft: ModelSettingsDraft,
    language: String,
    themeMode: String,
    themeColor: String,
    authorityMode: String,
    deepSeekThinkingEffort: String,
    networkEnabled: Boolean,
    webSearchEnabled: Boolean,
    braveSearchApiKey: String,
    backgroundKeepAliveEnabled: Boolean,
    workspaceMemoryEnabled: Boolean,
    inputBarVisible: Boolean,
  ): AppSettings {
    return store.update(settings) { latest ->
      buildModelSettings(latest, draft).copy(
        language = normalizeLanguageId(language),
        themeMode = normalizeThemeMode(themeMode),
        themeColor = normalizeThemeColor(themeColor),
        agentAuthorityMode = normalizeAuthorityModeId(authorityMode),
        deepSeekThinkingEffort = normalizeDeepSeekThinkingEffortId(deepSeekThinkingEffort),
        networkEnabled = networkEnabled,
        networkUserConfigured = true,
        webSearchEnabled = webSearchEnabled,
        webSearchUserConfigured = true,
        braveSearchApiKey = normalizeBraveSearchApiKey(braveSearchApiKey),
        backgroundKeepAliveEnabled = backgroundKeepAliveEnabled,
        workspaceMemoryEnabled = workspaceMemoryEnabled,
        inputBarVisible = inputBarVisible,
      )
    }
  }

  private fun buildModelSettings(settings: AppSettings, draft: ModelSettingsDraft): AppSettings {
    val provider = ModelProviderCatalog.findSelectableProvider(draft.providerId) ?: ModelProviderCatalog.defaultProvider
    val model = if (provider.id == draft.providerId.trim().lowercase()) {
      draft.model.trim().ifBlank { provider.defaultModel }
    } else {
      provider.defaultModel
    }
    val updated = settings
      .copy(
        provider = provider.id,
        model = model,
        customOpenAIProvider = CustomOpenAIProviderSettings(
          baseUrl = normalizeCustomOpenAIBaseUrl(draft.customOpenAIBaseUrl),
          chatCompletionsPath = normalizeCustomOpenAIPath(draft.customOpenAIChatCompletionsPath),
          compatibilityMode = normalizeCustomOpenAICompatibilityMode(draft.customOpenAICompatibilityMode),
        ),
      )
      .withApiKey(provider.id, draft.apiKey)
    return updated
  }

  fun setNetworkEnabled(settings: AppSettings, enabled: Boolean): AppSettings {
    return store.update(settings) { it.copy(networkEnabled = enabled, networkUserConfigured = true) }
  }

  fun setWebSearch(settings: AppSettings, enabled: Boolean, braveApiKey: String): AppSettings {
    return store.update(settings) {
      it.copy(
        webSearchEnabled = enabled,
        webSearchUserConfigured = true,
        braveSearchApiKey = normalizeBraveSearchApiKey(braveApiKey),
      )
    }
  }

  fun setBackgroundKeepAlive(settings: AppSettings, enabled: Boolean): AppSettings {
    return store.update(settings) { it.copy(backgroundKeepAliveEnabled = enabled) }
  }

  fun setInputBarVisible(settings: AppSettings, visible: Boolean): AppSettings {
    return store.update(settings) { it.copy(inputBarVisible = visible) }
  }

  fun setWorkspaceMemoryEnabled(settings: AppSettings, enabled: Boolean): AppSettings {
    return store.update(settings) { it.copy(workspaceMemoryEnabled = enabled) }
  }

  fun setLanguage(settings: AppSettings, language: String): AppSettings {
    return store.update(settings) { it.copy(language = normalizeLanguageId(language)) }
  }

  fun setAppearance(settings: AppSettings, themeMode: String, themeColor: String): AppSettings {
    return store.update(settings) {
      it.copy(
        themeMode = normalizeThemeMode(themeMode),
        themeColor = normalizeThemeColor(themeColor),
      )
    }
  }

  fun setAuthorityMode(settings: AppSettings, authorityMode: String): AppSettings {
    return store.update(settings) { it.copy(agentAuthorityMode = normalizeAuthorityModeId(authorityMode)) }
  }

  fun setDeepSeekThinkingEffort(settings: AppSettings, effort: String): AppSettings {
    return store.update(settings) { it.copy(deepSeekThinkingEffort = normalizeDeepSeekThinkingEffortId(effort)) }
  }

  fun saveWorkspaceSecret(
    settings: AppSettings,
    originalName: String,
    name: String,
    label: String,
    description: String,
    value: String,
    agentAllowed: Boolean,
  ): AppSettings {
    val displayName = name.trim().ifBlank { label.trim() }
    if (displayName.isBlank()) return settings
    val normalizedOriginal = normalizeSecretName(originalName)
    return store.update(settings) { latest ->
      val current = normalizeWorkspaceSecrets(latest.workspaceSecrets)
      val normalizedName = normalizedOriginal.ifBlank { nextWorkspaceSecretName(current) }
      val entry = WorkspaceSecret(
        name = normalizedName,
        label = displayName,
        description = "",
        value = value.trim(),
        agentAllowed = agentAllowed,
      )
      latest.copy(
        workspaceSecrets = (current.filterNot { it.normalizedName == normalizedName } + entry)
          .sortedBy { it.normalizedName },
      )
    }
  }

  fun deleteWorkspaceSecret(settings: AppSettings, name: String): AppSettings {
    val normalizedName = normalizeSecretName(name)
    return store.update(settings) { latest ->
      latest.copy(
        workspaceSecrets = normalizeWorkspaceSecrets(latest.workspaceSecrets)
          .filterNot { it.normalizedName == normalizedName },
      )
    }
  }

  fun setWorkspaceSecretAgentAllowed(settings: AppSettings, name: String, allowed: Boolean): AppSettings {
    val normalizedName = normalizeSecretName(name)
    return store.update(settings) { latest ->
      latest.copy(
        workspaceSecrets = normalizeWorkspaceSecrets(latest.workspaceSecrets).map { secret ->
          if (secret.normalizedName == normalizedName) secret.copy(agentAllowed = allowed) else secret
        },
      )
    }
  }

  fun applySettingsProposal(settings: AppSettings, changes: SettingsProposalChanges): AppSettings {
    return store.update(settings) { latest ->
      val proposedProviderId = changes.provider?.trim()?.takeIf { it.isNotBlank() }
      val proposedProvider = proposedProviderId?.let { ModelProviderCatalog.findSelectableProvider(it) }
      val provider = proposedProvider
        ?: ModelProviderCatalog.findSelectableProvider(latest.provider)
        ?: ModelProviderCatalog.defaultProvider
      val model = if (proposedProviderId != null && proposedProvider == null) {
        provider.defaultModel
      } else {
        changes.model?.trim()?.takeIf { it.isNotBlank() } ?: latest.model.ifBlank { provider.defaultModel }
      }
      val maxIterations = changes.maxAgentIterations
        ?.let { normalizeMaxAgentIterations(it) }
        ?: latest.maxAgentIterations
      latest.copy(
        provider = provider.id,
        model = model,
        selectedHtmlPath = changes.selectedHtmlPath?.trim() ?: latest.selectedHtmlPath,
        maxAgentIterations = maxIterations,
        networkEnabled = changes.networkEnabled ?: latest.networkEnabled,
        networkUserConfigured = if (changes.networkEnabled != null) true else latest.networkUserConfigured,
        webSearchEnabled = changes.webSearchEnabled ?: latest.webSearchEnabled,
        webSearchUserConfigured = if (changes.webSearchEnabled != null) true else latest.webSearchUserConfigured,
        backgroundKeepAliveEnabled = changes.backgroundKeepAliveEnabled ?: latest.backgroundKeepAliveEnabled,
        pythonRunToolFallbackEnabled = changes.pythonRunToolFallbackEnabled ?: latest.pythonRunToolFallbackEnabled,
        workspaceMemoryEnabled = changes.workspaceMemoryEnabled ?: latest.workspaceMemoryEnabled,
        language = changes.language?.let { normalizeLanguageId(it) } ?: latest.language,
        themeMode = changes.themeMode?.let { normalizeThemeMode(it) } ?: latest.themeMode,
        themeColor = changes.themeColor?.let { normalizeThemeColor(it) } ?: latest.themeColor,
        agentAuthorityMode = changes.agentAuthorityMode?.let { normalizeAuthorityModeId(it) } ?: latest.agentAuthorityMode,
        deepSeekThinkingEffort = changes.deepSeekThinkingEffort?.let { normalizeDeepSeekThinkingEffortId(it) }
          ?: latest.deepSeekThinkingEffort,
        reasoningEffort = changes.reasoningEffort?.let { normalizeReasoningEffortId(it) } ?: latest.reasoningEffort,
        customOpenAIProvider = latest.customOpenAIProvider.copy(
          baseUrl = changes.customOpenAIBaseUrl?.let { normalizeCustomOpenAIBaseUrl(it) }
            ?: latest.customOpenAIProvider.baseUrl,
          chatCompletionsPath = changes.customOpenAIChatCompletionsPath?.let { normalizeCustomOpenAIPath(it) }
            ?: latest.customOpenAIProvider.chatCompletionsPath,
          compatibilityMode = changes.customOpenAICompatibilityMode?.let { normalizeCustomOpenAICompatibilityMode(it) }
            ?: latest.customOpenAIProvider.compatibilityMode,
        ),
        openRouterProvider = latest.openRouterProvider.copy(
          providerPreferences = changes.openRouterProviderPreferences ?: latest.openRouterProvider.providerPreferences,
          minCodingScore = changes.openRouterMinCodingScore?.let { normalizeOpenRouterMinCodingScore(it) }
            ?: latest.openRouterProvider.minCodingScore,
        ),
      ).withMergedModelContextOverride(provider.id, model, changes)
    }
  }

  fun setActiveSession(settings: AppSettings, sessionId: String?): AppSettings {
    return store.update(settings) { it.copy(activeSessionId = sessionId) }
  }

  fun setSelectedHtml(settings: AppSettings, path: String): AppSettings {
    return store.update(settings) { latest -> withSelectedHtml(latest, path) }
  }

  fun withSelectedHtml(settings: AppSettings, path: String): AppSettings {
    val normalized = path.trim()
    return settings.copy(
      selectedHtmlPath = normalized,
      recentHtmlPaths = promoteRecentHtmlPath(settings.recentHtmlPaths, normalized),
    )
  }

  fun setPinnedHtmlPath(settings: AppSettings, path: String, pinned: Boolean): AppSettings {
    val normalized = path.trim()
    return store.update(settings) { latest ->
      val current = normalizeHtmlPathList(latest.pinnedHtmlPaths).filterNot { it == normalized }
      val updatedPins = if (pinned && normalized.isNotBlank()) {
        listOf(normalized) + current
      } else {
        current
      }
      latest.copy(pinnedHtmlPaths = updatedPins.distinct())
    }
  }

  fun normalizeSelectedHtml(settings: AppSettings, selectedHtmlPath: String): AppSettings {
    return store.update(settings) { latest ->
      latest.copy(
        selectedHtmlPath = selectedHtmlPath,
        recentHtmlPaths = normalizeHtmlPathList(latest.recentHtmlPaths).take(RECENT_HTML_LIMIT),
      )
    }
  }

  private fun normalizeProviderAndModel(settings: AppSettings): AppSettings {
    val provider = ModelProviderCatalog.findSelectableProvider(settings.provider) ?: ModelProviderCatalog.defaultProvider
    val model = settings.model.ifBlank { provider.defaultModel }
    return settings.copy(provider = provider.id, model = model)
  }

  private fun normalizeCustomOpenAIProvider(settings: AppSettings): AppSettings {
    return settings.copy(
      customOpenAIProvider = settings.customOpenAIProvider.copy(
        baseUrl = normalizeCustomOpenAIBaseUrl(settings.customOpenAIProvider.baseUrl),
        chatCompletionsPath = normalizeCustomOpenAIPath(settings.customOpenAIProvider.chatCompletionsPath),
        compatibilityMode = normalizeCustomOpenAICompatibilityMode(settings.customOpenAIProvider.compatibilityMode),
      ),
    )
  }

  private fun normalizeLanguage(settings: AppSettings): AppSettings {
    return settings.copy(language = normalizeLanguageId(settings.language))
  }

  private fun normalizeAppearance(settings: AppSettings): AppSettings {
    return settings.copy(
      themeMode = normalizeThemeMode(settings.themeMode),
      themeColor = normalizeThemeColor(settings.themeColor),
    )
  }

  private fun normalizeRunLimits(settings: AppSettings): AppSettings {
    return settings.copy(maxAgentIterations = normalizeMaxAgentIterations(settings.maxAgentIterations))
  }

  private fun normalizeHtmlLists(settings: AppSettings): AppSettings {
    return settings.copy(
      pinnedHtmlPaths = normalizeHtmlPathList(settings.pinnedHtmlPaths),
      recentHtmlPaths = normalizeHtmlPathList(settings.recentHtmlPaths).take(RECENT_HTML_LIMIT),
    )
  }

  private fun normalizeAuthorityMode(settings: AppSettings): AppSettings {
    return settings.copy(agentAuthorityMode = normalizeAuthorityModeId(settings.agentAuthorityMode))
  }

  private fun normalizeDeepSeekThinkingEffort(settings: AppSettings): AppSettings {
    return settings.copy(deepSeekThinkingEffort = normalizeDeepSeekThinkingEffortId(settings.deepSeekThinkingEffort))
  }

  private fun normalizeReasoningEffort(settings: AppSettings): AppSettings {
    return settings.copy(reasoningEffort = normalizeReasoningEffortId(settings.reasoningEffort))
  }

  private fun normalizeOpenRouterProvider(settings: AppSettings): AppSettings {
    return settings.copy(
      openRouterProvider = settings.openRouterProvider.copy(
        minCodingScore = settings.openRouterProvider.minCodingScore?.let { normalizeOpenRouterMinCodingScore(it) },
      ),
      workspaceSecrets = normalizeWorkspaceSecrets(settings.workspaceSecrets),
    )
  }

  private fun normalizeNetworkAndSearchDefaults(settings: AppSettings): AppSettings {
    val networkDefaulted = if (!settings.networkUserConfigured && !settings.networkEnabled) {
      settings.copy(networkEnabled = true)
    } else {
      settings
    }
    return if (!networkDefaulted.webSearchUserConfigured &&
      networkDefaulted.braveSearchApiKey.isNotBlank() &&
      !networkDefaulted.webSearchEnabled
    ) {
      networkDefaulted.copy(webSearchEnabled = true)
    } else {
      networkDefaulted
    }
  }

  private fun AppSettings.withMergedModelContextOverride(
    providerId: String,
    modelId: String,
    changes: SettingsProposalChanges,
  ): AppSettings {
    val hasContextChange = changes.modelContextWindowTokens != null || changes.modelCompressionThresholdPercent != null
    if (!hasContextChange) return this
    val current = modelContextOverrideFor(providerId, modelId) ?: ModelContextOverride()
    val updated = current.copy(
      contextWindowTokens = changes.modelContextWindowTokens?.takeIf { it > 0 } ?: current.contextWindowTokens,
      compressionThresholdPercent = changes.modelCompressionThresholdPercent?.coerceIn(1, 100)
        ?: current.compressionThresholdPercent,
    )
    return withModelContextOverride(providerId, modelId, updated)
  }

  private fun normalizeLanguageId(language: String): String {
    return when (language) {
      "zh" -> "zh"
      else -> "en"
    }
  }

  private fun normalizeThemeMode(themeMode: String): String {
    return when (themeMode) {
      "light" -> "light"
      else -> "dark"
    }
  }

  private fun normalizeThemeColor(themeColor: String): String {
    val candidate = themeColor.trim().uppercase()
    val normalized = if (candidate.startsWith("#")) candidate else "#$candidate"
    val valid = Regex("^#[0-9A-F]{6}$").matches(normalized)
    return if (valid) normalized else AppSettings().themeColor
  }

  private fun normalizeAuthorityModeId(authorityMode: String): String {
    return when (authorityMode) {
      "assisted" -> "assisted"
      "full" -> "full"
      else -> "safe"
    }
  }

  private fun normalizeDeepSeekThinkingEffortId(effort: String): String {
    return when (effort) {
      "off" -> "off"
      "low" -> "low"
      "max" -> "max"
      else -> "high"
    }
  }

  private fun normalizeReasoningEffortId(effort: String): String {
    return when (effort.trim().lowercase()) {
      "" -> ""
      "none" -> "none"
      "minimal" -> "minimal"
      "low" -> "low"
      "medium" -> "medium"
      "high" -> "high"
      "xhigh" -> "xhigh"
      else -> ""
    }
  }

  private fun normalizeCustomOpenAIBaseUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    if (trimmed.isBlank()) return ""
    val valid = Regex("^https?://[^\\s/$.?#].[^\\s]*$", RegexOption.IGNORE_CASE).matches(trimmed)
    return if (valid) trimmed else ""
  }

  private fun normalizeCustomOpenAIPath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank()) return CustomOpenAIProviderSettings().chatCompletionsPath
    val withSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    return if (withSlash.contains(Regex("\\s"))) CustomOpenAIProviderSettings().chatCompletionsPath else withSlash
  }

  private fun normalizeCustomOpenAICompatibilityMode(mode: String): String {
    return when (mode.trim().lowercase()) {
      "ollama" -> "ollama"
      else -> "generic"
    }
  }

  private fun normalizeOpenRouterMinCodingScore(score: Double): Double? {
    return score.takeIf { it in 0.0..1.0 }
  }

  private fun normalizeMaxAgentIterations(value: Int): Int {
    return AGENT_ITERATIONS_UNLIMITED
  }

  private fun normalizeHtmlPathList(paths: List<String>): List<String> {
    return paths.map { it.trim() }
      .filter { it.isNotBlank() }
      .distinct()
  }

  private fun normalizeWorkspaceSecrets(secrets: List<WorkspaceSecret>): List<WorkspaceSecret> {
    return secrets
      .mapNotNull { secret ->
        val name = normalizeSecretName(secret.name)
        if (name.isBlank()) {
          null
        } else {
          secret.copy(
            name = name,
            label = secret.label.trim(),
            description = secret.description.trim(),
            value = secret.value.trim(),
          )
        }
      }
      .filter { it.value.isNotBlank() }
      .distinctBy { it.normalizedName }
      .sortedBy { it.normalizedName }
  }

  private fun nextWorkspaceSecretName(secrets: List<WorkspaceSecret>): String {
    val used = secrets.map { it.normalizedName }.toSet()
    var index = 1
    while ("FLOVERA_SECRET_$index" in used) index += 1
    return "FLOVERA_SECRET_$index"
  }

  private fun promoteRecentHtmlPath(current: List<String>, path: String): List<String> {
    if (path.isBlank()) return normalizeHtmlPathList(current).take(RECENT_HTML_LIMIT)
    return (listOf(path) + normalizeHtmlPathList(current).filterNot { it == path })
      .take(RECENT_HTML_LIMIT)
  }
}

@Serializable
data class SettingsProposalChanges(
  val provider: String? = null,
  val model: String? = null,
  val selectedHtmlPath: String? = null,
  val maxAgentIterations: Int? = null,
  val networkEnabled: Boolean? = null,
  val webSearchEnabled: Boolean? = null,
  val backgroundKeepAliveEnabled: Boolean? = null,
  val pythonRunToolFallbackEnabled: Boolean? = null,
  val workspaceMemoryEnabled: Boolean? = null,
  val language: String? = null,
  val themeMode: String? = null,
  val themeColor: String? = null,
  val agentAuthorityMode: String? = null,
  val deepSeekThinkingEffort: String? = null,
  val reasoningEffort: String? = null,
  val customOpenAIBaseUrl: String? = null,
  val customOpenAIChatCompletionsPath: String? = null,
  val customOpenAICompatibilityMode: String? = null,
  val openRouterProviderPreferences: JsonObject? = null,
  val openRouterMinCodingScore: Double? = null,
  val modelContextWindowTokens: Int? = null,
  val modelCompressionThresholdPercent: Int? = null,
)
