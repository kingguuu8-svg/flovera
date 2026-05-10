package com.example.ailinuxvmspike

import com.example.ailinuxvmspike.config.AppSettings
import com.example.ailinuxvmspike.koog.ModelProviderCatalog
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
}
