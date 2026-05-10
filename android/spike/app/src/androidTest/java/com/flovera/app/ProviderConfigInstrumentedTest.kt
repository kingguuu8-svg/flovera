package com.flovera.app

import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.config.AppSettings
import com.flovera.app.config.ModelSettingsDraft
import com.flovera.app.config.SettingsController
import com.flovera.app.config.SettingsStore
import com.flovera.app.koog.ModelProviderCatalog
import java.io.File
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

  @Test
  fun settingsStoreEncryptsSettingsWithoutLeavingAtomicTempFiles() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val settingsFile = File(context.filesDir, "settings.json")
    val original = store.load()
    try {
      store.save(AppSettings(apiKey = "atomic-key", provider = "deepseek", model = "deepseek-chat"))

      assertEquals("atomic-key", store.load().apiKeyFor("deepseek"))
      assertTrue(settingsFile.isFile)
      val stored = settingsFile.readText()
      assertTrue(stored.contains("cipherText"))
      assertFalse(stored.contains("atomic-key"))
      assertFalse(stored.contains("deepseek-chat"))
      assertFalse(File(settingsFile.absolutePath + ".new").exists())
      assertFalse(File(settingsFile.absolutePath + ".bak").exists())
    } finally {
      store.save(original)
    }
  }

  @Test
  fun settingsStoreMigratesLegacyPlaintextSettings() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val settingsFile = File(context.filesDir, "settings.json")
    val original = store.load()
    try {
      settingsFile.writeText(
        """
        {
          "provider": "deepseek",
          "model": "deepseek-chat",
          "apiKey": "legacy-plain-key",
          "providerApiKeys": {},
          "activeWorkspaceId": "default",
          "activeSessionId": null,
          "selectedHtmlPath": "",
          "maxAgentIterations": 20,
          "networkEnabled": false
        }
        """.trimIndent(),
      )

      val migrated = store.load()

      assertEquals("legacy-plain-key", migrated.apiKeyFor("deepseek"))
      val stored = settingsFile.readText()
      assertTrue(stored.contains("cipherText"))
      assertFalse(stored.contains("legacy-plain-key"))
    } finally {
      store.save(original)
    }
  }
}
