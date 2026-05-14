package com.flovera.app

import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.config.AppSettings
import com.flovera.app.config.ModelSettingsDraft
import com.flovera.app.config.SettingsProposalChanges
import com.flovera.app.config.SettingsController
import com.flovera.app.config.SettingsStore
import com.flovera.app.koog.ModelProviderCatalog
import com.flovera.app.workspace.WorkspaceManager
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
  fun fullAuthorityIsNotEnabledInCurrentSettings() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val original = store.load()
    try {
      val controller = SettingsController(store)

      val normalized = controller.setAuthorityMode(AppSettings(), "full")

      assertEquals("safe", normalized.agentAuthorityMode)
    } finally {
      store.save(original)
    }
  }

  @Test
  fun settingsControllerAppliesAssistedProposalChanges() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val original = store.load()
    try {
      val controller = SettingsController(store)

      val updated = controller.applySettingsProposal(
        AppSettings(),
        SettingsProposalChanges(
          themeMode = "light",
          themeColor = "#c989b8",
          networkEnabled = true,
          language = "zh",
          maxAgentIterations = 120,
          agentAuthorityMode = "assisted",
        ),
      )

      assertEquals("light", updated.themeMode)
      assertEquals("#C989B8", updated.themeColor)
      assertTrue(updated.networkEnabled)
      assertEquals("zh", updated.language)
      assertEquals(80, updated.maxAgentIterations)
      assertEquals("assisted", updated.agentAuthorityMode)
    } finally {
      store.save(original)
    }
  }

  @Test
  fun settingsControllerNormalizesAndPersistsAppearance() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val original = store.load()
    try {
      val controller = SettingsController(store)

      val light = controller.setAppearance(AppSettings(), "light", "c989b8")
      assertEquals("light", light.themeMode)
      assertEquals("#C989B8", light.themeColor)
      assertEquals("#C989B8", store.load().themeColor)

      val fallback = controller.setAppearance(light, "missing", "not-a-color")
      assertEquals("dark", fallback.themeMode)
      assertEquals(AppSettings().themeColor, fallback.themeColor)
    } finally {
      store.save(original)
    }
  }

  @Test
  fun settingsControllerPersistsSupportedLanguage() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val original = store.load()
    try {
      val controller = SettingsController(store)

      val zh = controller.setLanguage(AppSettings(), "zh")
      assertEquals("zh", zh.language)
      assertEquals("zh", store.load().language)

      val normalized = controller.setLanguage(zh, "missing")
      assertEquals("en", normalized.language)
    } finally {
      store.save(original)
    }
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

  @Test
  fun invalidSettingsFileIsReportedToUiState() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val settingsFile = File(context.filesDir, "settings.json")
    val original = store.load()
    try {
      settingsFile.writeText("not-json")

      val result = store.loadResult()
      assertEquals(AppSettings(), result.settings)
      assertTrue(result.warning.orEmpty().contains("invalid"))

      val controller = AgentController(context)
      assertTrue(controller.state.value.status.contains("invalid"))
    } finally {
      store.save(original)
    }
  }

  @Test
  fun agentControllerAppliesSettingsProposalFromWorkspace() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val original = store.load()
    val workspaceId = "settings-proposal-${System.currentTimeMillis()}"
    val workspace = WorkspaceManager(context, workspaceId)
    try {
      store.save(AppSettings(activeWorkspaceId = workspaceId))
      val controller = AgentController(context)
      workspace.writeFile(
        ".flovera/proposals/theme.json",
        """
        {
          "type": "settings",
          "title": "Switch preview",
          "reason": "The generated page is selected.",
          "changes": {
            "themeColor": "#9AA7FF",
            "selectedHtmlPath": "index.html"
          }
        }
        """.trimIndent(),
        createAutoSnapshot = false,
      )

      controller.refreshWorkspaceFiles()
      assertEquals(1, controller.state.value.settingsProposals.size)

      controller.approveSettingsProposal(".flovera/proposals/theme.json")

      assertEquals("#9AA7FF", controller.state.value.settings.themeColor)
      assertEquals("index.html", controller.state.value.settings.selectedHtmlPath)
      assertTrue(controller.state.value.settingsProposals.isEmpty())
    } finally {
      store.save(original)
      workspace.root.deleteRecursively()
    }
  }
}
