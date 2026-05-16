package com.flovera.app.config

import com.flovera.app.koog.ModelProviderCatalog
import kotlinx.serialization.Serializable

data class ModelSettingsDraft(
  val providerId: String,
  val model: String,
  val apiKey: String,
  val customOpenAIBaseUrl: String = "",
  val customOpenAIChatCompletionsPath: String = "/v1/chat/completions",
)

class SettingsController(private val store: SettingsStore) {
  fun load(): AppSettings {
    return loadResult().settings
  }

  fun loadResult(): SettingsLoadResult {
    val result = store.loadResult()
    val loaded = result.settings
    val normalized = normalizeDeepSeekThinkingEffort(
      normalizeAuthorityMode(normalizeAppearance(normalizeLanguage(normalizeProviderAndModel(loaded)))),
    )
    if (normalized != loaded) store.save(normalized)
    return result.copy(settings = normalized)
  }

  fun draftFor(settings: AppSettings): ModelSettingsDraft {
    val provider = ModelProviderCatalog.findProvider(settings.provider) ?: ModelProviderCatalog.defaultProvider
    val model = settings.model.ifBlank { provider.defaultModel }
    return ModelSettingsDraft(
      providerId = provider.id,
      model = model,
      apiKey = settings.apiKeyFor(provider.id),
      customOpenAIBaseUrl = settings.customOpenAIProvider.baseUrl,
      customOpenAIChatCompletionsPath = settings.customOpenAIProvider.chatCompletionsPath,
    )
  }

  fun draftForProvider(settings: AppSettings, providerId: String): ModelSettingsDraft? {
    val provider = ModelProviderCatalog.findProvider(providerId) ?: return null
    return ModelSettingsDraft(
      providerId = provider.id,
      model = provider.defaultModel,
      apiKey = settings.apiKeyFor(provider.id),
      customOpenAIBaseUrl = settings.customOpenAIProvider.baseUrl,
      customOpenAIChatCompletionsPath = settings.customOpenAIProvider.chatCompletionsPath,
    )
  }

  fun saveModelSettings(settings: AppSettings, draft: ModelSettingsDraft): AppSettings {
    val provider = ModelProviderCatalog.findProvider(draft.providerId) ?: ModelProviderCatalog.defaultProvider
    val model = draft.model.trim().ifBlank { provider.defaultModel }
    val updated = settings
      .copy(
        provider = provider.id,
        model = model,
        customOpenAIProvider = CustomOpenAIProviderSettings(
          baseUrl = normalizeCustomOpenAIBaseUrl(draft.customOpenAIBaseUrl),
          chatCompletionsPath = normalizeCustomOpenAIPath(draft.customOpenAIChatCompletionsPath),
        ),
      )
      .withApiKey(provider.id, draft.apiKey)
    store.save(updated)
    return updated
  }

  fun setNetworkEnabled(settings: AppSettings, enabled: Boolean): AppSettings {
    val updated = settings.copy(networkEnabled = enabled)
    store.save(updated)
    return updated
  }

  fun setWebSearch(settings: AppSettings, enabled: Boolean, braveApiKey: String): AppSettings {
    val updated = settings.copy(
      webSearchEnabled = enabled,
      braveSearchApiKey = normalizeBraveSearchApiKey(braveApiKey),
    )
    store.save(updated)
    return updated
  }

  fun setLanguage(settings: AppSettings, language: String): AppSettings {
    val updated = settings.copy(language = normalizeLanguageId(language))
    store.save(updated)
    return updated
  }

  fun setAppearance(settings: AppSettings, themeMode: String, themeColor: String): AppSettings {
    val updated = settings.copy(
      themeMode = normalizeThemeMode(themeMode),
      themeColor = normalizeThemeColor(themeColor),
    )
    store.save(updated)
    return updated
  }

  fun setAuthorityMode(settings: AppSettings, authorityMode: String): AppSettings {
    val updated = settings.copy(agentAuthorityMode = normalizeAuthorityModeId(authorityMode))
    store.save(updated)
    return updated
  }

  fun setDeepSeekThinkingEffort(settings: AppSettings, effort: String): AppSettings {
    val updated = settings.copy(deepSeekThinkingEffort = normalizeDeepSeekThinkingEffortId(effort))
    store.save(updated)
    return updated
  }

  fun applySettingsProposal(settings: AppSettings, changes: SettingsProposalChanges): AppSettings {
    val provider = changes.provider
      ?.let { ModelProviderCatalog.findProvider(it.trim()) }
      ?: ModelProviderCatalog.findProvider(settings.provider)
      ?: ModelProviderCatalog.defaultProvider
    val model = changes.model?.trim()?.takeIf { it.isNotBlank() } ?: settings.model.ifBlank { provider.defaultModel }
    val maxIterations = changes.maxAgentIterations?.coerceIn(1, 80) ?: settings.maxAgentIterations
    val updated = settings.copy(
      provider = provider.id,
      model = model,
      selectedHtmlPath = changes.selectedHtmlPath?.trim() ?: settings.selectedHtmlPath,
      maxAgentIterations = maxIterations,
      networkEnabled = changes.networkEnabled ?: settings.networkEnabled,
      webSearchEnabled = changes.webSearchEnabled ?: settings.webSearchEnabled,
      language = changes.language?.let { normalizeLanguageId(it) } ?: settings.language,
      themeMode = changes.themeMode?.let { normalizeThemeMode(it) } ?: settings.themeMode,
      themeColor = changes.themeColor?.let { normalizeThemeColor(it) } ?: settings.themeColor,
      agentAuthorityMode = changes.agentAuthorityMode?.let { normalizeAuthorityModeId(it) } ?: settings.agentAuthorityMode,
      deepSeekThinkingEffort = changes.deepSeekThinkingEffort?.let { normalizeDeepSeekThinkingEffortId(it) }
        ?: settings.deepSeekThinkingEffort,
      customOpenAIProvider = settings.customOpenAIProvider.copy(
        baseUrl = changes.customOpenAIBaseUrl?.let { normalizeCustomOpenAIBaseUrl(it) }
          ?: settings.customOpenAIProvider.baseUrl,
        chatCompletionsPath = changes.customOpenAIChatCompletionsPath?.let { normalizeCustomOpenAIPath(it) }
          ?: settings.customOpenAIProvider.chatCompletionsPath,
      ),
    ).withMergedModelContextOverride(provider.id, model, changes)
    store.save(updated)
    return updated
  }

  fun setActiveSession(settings: AppSettings, sessionId: String?): AppSettings {
    val updated = settings.copy(activeSessionId = sessionId)
    store.save(updated)
    return updated
  }

  fun setSelectedHtml(settings: AppSettings, path: String): AppSettings {
    val updated = settings.copy(selectedHtmlPath = path)
    store.save(updated)
    return updated
  }

  fun setPinnedHtmlPath(settings: AppSettings, path: String, pinned: Boolean): AppSettings {
    val normalized = path.trim()
    val current = settings.pinnedHtmlPaths.filter { it.isNotBlank() && it != normalized }
    val updatedPins = if (pinned && normalized.isNotBlank()) {
      listOf(normalized) + current
    } else {
      current
    }
    val updated = settings.copy(pinnedHtmlPaths = updatedPins.distinct())
    store.save(updated)
    return updated
  }

  fun normalizeSelectedHtml(settings: AppSettings, selectedHtmlPath: String): AppSettings {
    val updated = settings.copy(selectedHtmlPath = selectedHtmlPath)
    if (updated != settings) store.save(updated)
    return updated
  }

  private fun normalizeProviderAndModel(settings: AppSettings): AppSettings {
    val provider = ModelProviderCatalog.findProvider(settings.provider) ?: ModelProviderCatalog.defaultProvider
    val model = settings.model.ifBlank { provider.defaultModel }
    return settings.copy(provider = provider.id, model = model)
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

  private fun normalizeAuthorityMode(settings: AppSettings): AppSettings {
    return settings.copy(agentAuthorityMode = normalizeAuthorityModeId(settings.agentAuthorityMode))
  }

  private fun normalizeDeepSeekThinkingEffort(settings: AppSettings): AppSettings {
    return settings.copy(deepSeekThinkingEffort = normalizeDeepSeekThinkingEffortId(settings.deepSeekThinkingEffort))
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
      else -> "safe"
    }
  }

  private fun normalizeDeepSeekThinkingEffortId(effort: String): String {
    return when (effort) {
      "off" -> "off"
      "max" -> "max"
      else -> "high"
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
}

@Serializable
data class SettingsProposalChanges(
  val provider: String? = null,
  val model: String? = null,
  val selectedHtmlPath: String? = null,
  val maxAgentIterations: Int? = null,
  val networkEnabled: Boolean? = null,
  val webSearchEnabled: Boolean? = null,
  val language: String? = null,
  val themeMode: String? = null,
  val themeColor: String? = null,
  val agentAuthorityMode: String? = null,
  val deepSeekThinkingEffort: String? = null,
  val customOpenAIBaseUrl: String? = null,
  val customOpenAIChatCompletionsPath: String? = null,
  val modelContextWindowTokens: Int? = null,
  val modelCompressionThresholdPercent: Int? = null,
)
