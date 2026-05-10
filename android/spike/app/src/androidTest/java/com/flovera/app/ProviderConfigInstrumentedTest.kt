package com.flovera.app

import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.config.AppSettings
import com.flovera.app.config.ModelSettingsDraft
import com.flovera.app.config.SettingsController
import com.flovera.app.config.SettingsStore
import com.flovera.app.koog.ModelProviderCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigInstrumentedTest {
  @Test
  fun apiKeysAreScopedByProvider() {
    val settings = AppSettings(apiKey = "legacy-key")
      .withApiKey("openai", "openai-key")

    assertEquals("legacy-key", settings.apiKeyFor("deepseek"))
    assertEquals("openai-key", settings.apiKeyFor("openai"))
    assertEquals("", settings.apiKeyFor("anthropic"))
  }

  @Test
  fun providerCatalogHasDefaultModels() {
    assertTrue(ModelProviderCatalog.providers.size >= 2)
    ModelProviderCatalog.providers.forEach { provider ->
      assertTrue(provider.id.isNotBlank())
      assertTrue(provider.defaultModel.isNotBlank())
      assertTrue(provider.suggestedModels.contains(provider.defaultModel))
    }
  }

  @Test
  fun networkToolsDefaultToDisabled() {
    assertFalse(AppSettings().networkEnabled)
    assertTrue(AppSettings(networkEnabled = true).networkEnabled)
  }

  @Test
  fun settingsControllerNormalizesAndPersistsModelSettings() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val original = store.load()
    try {
      store.save(AppSettings(provider = "missing", model = ""))
      val controller = SettingsController(store)

      val normalized = controller.load()
      assertEquals(ModelProviderCatalog.defaultProvider.id, normalized.provider)
      assertEquals(ModelProviderCatalog.defaultProvider.defaultModel, normalized.model)

      val openAiDraft = controller.draftForProvider(normalized, "openai")
      assertEquals("openai", openAiDraft?.providerId)

      val saved = controller.saveModelSettings(
        normalized,
        ModelSettingsDraft(providerId = "openai", model = "  ", apiKey = " openai-key "),
      )
      assertEquals("openai", saved.provider)
      assertEquals(ModelProviderCatalog.findProvider("openai")?.defaultModel, saved.model)
      assertEquals("openai-key", saved.apiKeyFor("openai"))
    } finally {
      store.save(original)
    }
  }
}
