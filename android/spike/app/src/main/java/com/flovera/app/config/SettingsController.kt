package com.flovera.app.config

import com.flovera.app.koog.ModelProviderCatalog

data class ModelSettingsDraft(
  val providerId: String,
  val model: String,
  val apiKey: String,
)

class SettingsController(private val store: SettingsStore) {
  fun load(): AppSettings {
    val loaded = store.load()
    val normalized = normalizeLanguage(normalizeProviderAndModel(loaded))
    if (normalized != loaded) store.save(normalized)
    return normalized
  }

  fun draftFor(settings: AppSettings): ModelSettingsDraft {
    val provider = ModelProviderCatalog.findProvider(settings.provider) ?: ModelProviderCatalog.defaultProvider
    val model = settings.model.ifBlank { provider.defaultModel }
    return ModelSettingsDraft(
      providerId = provider.id,
      model = model,
      apiKey = settings.apiKeyFor(provider.id),
    )
  }

  fun draftForProvider(settings: AppSettings, providerId: String): ModelSettingsDraft? {
    val provider = ModelProviderCatalog.findProvider(providerId) ?: return null
    return ModelSettingsDraft(
      providerId = provider.id,
      model = provider.defaultModel,
      apiKey = settings.apiKeyFor(provider.id),
    )
  }

  fun saveModelSettings(settings: AppSettings, draft: ModelSettingsDraft): AppSettings {
    val provider = ModelProviderCatalog.findProvider(draft.providerId) ?: ModelProviderCatalog.defaultProvider
    val model = draft.model.trim().ifBlank { provider.defaultModel }
    val updated = settings
      .copy(provider = provider.id, model = model)
      .withApiKey(provider.id, draft.apiKey)
    store.save(updated)
    return updated
  }

  fun setNetworkEnabled(settings: AppSettings, enabled: Boolean): AppSettings {
    val updated = settings.copy(networkEnabled = enabled)
    store.save(updated)
    return updated
  }

  fun setLanguage(settings: AppSettings, language: String): AppSettings {
    val updated = settings.copy(language = normalizeLanguageId(language))
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

  private fun normalizeLanguageId(language: String): String {
    return when (language) {
      "zh" -> "zh"
      else -> "en"
    }
  }
}
