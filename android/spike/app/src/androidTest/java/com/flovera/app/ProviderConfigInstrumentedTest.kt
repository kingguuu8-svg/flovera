package com.flovera.app

import ai.koog.prompt.llm.LLMProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.config.AppSettings
import com.flovera.app.config.CustomOpenAIProviderSettings
import com.flovera.app.config.ModelContextOverride
import com.flovera.app.config.ModelSettingsDraft
import com.flovera.app.config.SettingsProposalChanges
import com.flovera.app.config.SettingsController
import com.flovera.app.config.SettingsStore
import com.flovera.app.koog.ModelContextSpec
import com.flovera.app.koog.ModelProviderCatalog
import com.flovera.app.koog.ProviderRequestProfile
import com.flovera.app.koog.applyFloveraOpenAIRequestProfileToJson
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
    assertTrue(ModelProviderCatalog.providers.size >= 14)
    ModelProviderCatalog.providers.forEach { provider ->
      assertTrue(provider.id.isNotBlank())
      assertTrue(provider.defaultModel.isNotBlank())
      assertTrue(provider.suggestedModels.contains(provider.defaultModel))
      assertTrue(provider.apiMode.id.isNotBlank())
    }
    assertTrue(ModelProviderCatalog.supportedApiModes.contains("chat_completions"))
    assertTrue(ModelProviderCatalog.supportedApiModes.contains("anthropic_messages"))
  }

  @Test
  fun providerCatalogNormalizesAliasesToCanonicalProfiles() {
    assertEquals("anthropic", ModelProviderCatalog.findProvider("claude")?.id)
    assertEquals("custom-openai", ModelProviderCatalog.findProvider("ollama")?.id)
    assertEquals("openrouter", ModelProviderCatalog.findProvider("router")?.id)
    assertEquals("alibaba", ModelProviderCatalog.findProvider("dashscope")?.id)
    assertEquals("zai", ModelProviderCatalog.findProvider("zhipu")?.id)
    assertEquals("huggingface", ModelProviderCatalog.findProvider("hf")?.id)
    assertEquals("nvidia", ModelProviderCatalog.findProvider("nvidia-nim")?.id)
    assertEquals("novita", ModelProviderCatalog.findProvider("novitaai")?.id)
    assertEquals("moonshot", ModelProviderCatalog.findProvider("kimi")?.id)
  }

  @Test
  fun openAICompatibleProviderProfilesResolveFromCatalogData() {
    val alibaba = ModelProviderCatalog.requireProvider("dashscope")
    val profile = ModelProviderCatalog.runtimeProfileFor(
      alibaba,
      AppSettings(provider = "alibaba", model = "qwen3-coder-plus"),
    )
    val openRouter = ModelProviderCatalog.requireProvider("openrouter")

    assertEquals("alibaba", alibaba.id)
    assertEquals(LLMProvider.OpenAI, alibaba.llmProvider)
    assertEquals("chat_completions", profile.apiMode.id)
    assertEquals("https://dashscope-intl.aliyuncs.com/compatible-mode/v1", profile.baseUrl)
    assertEquals("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/models", profile.modelsUrl)
    assertEquals("/v1/chat/completions", profile.chatCompletionsPath)
    assertEquals("generic", profile.requestProfile.compatibilityMode)
    assertFalse(profile.requestProfile.injectOllamaNumCtx)
    assertEquals(LLMProvider.OpenRouter, openRouter.llmProvider)
    assertEquals(
      "https://openrouter.ai/api/v1",
      ModelProviderCatalog.runtimeProfileFor(openRouter, AppSettings()).baseUrl,
    )
  }

  @Test
  fun providerRequestProfilesCanOmitUnsupportedFields() {
    val request = """{"model":"kimi-k2","messages":[],"temperature":0.7,"top_p":0.9}"""
    val moonshot = ModelProviderCatalog.requireProvider("moonshot")
    val profile = ModelProviderCatalog.runtimeProfileFor(
      moonshot,
      AppSettings(provider = "moonshot", model = "kimi-k2-turbo-preview"),
    )

    val updated = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
    )

    assertTrue(profile.requestProfile.omittedRequestFields.contains("temperature"))
    assertFalse(updated.contains("\"temperature\""))
    assertTrue(updated.contains("\"top_p\":0.9"))
  }

  @Test
  fun customOpenAIProviderIsAvailableAsControlledRoutingSlot() {
    val provider = ModelProviderCatalog.requireProvider("custom-openai")
    val settings = AppSettings(
      provider = "custom-openai",
      model = "my-model",
      customOpenAIProvider = CustomOpenAIProviderSettings(
        baseUrl = "https://llm.example.com",
        chatCompletionsPath = "/v1/chat/completions",
      ),
    ).withApiKey("custom-openai", "custom-key")

    assertEquals("Custom OpenAI-compatible", provider.label)
    assertEquals("chat_completions", provider.apiMode.id)
    assertEquals("my-model", provider.createModel(settings.model).id)
    assertEquals("custom-key", settings.apiKeyFor("custom-openai"))
  }

  @Test
  fun customOpenAISettingsResolveIntoRuntimeProviderProfile() {
    val settings = AppSettings(
      provider = "custom-openai",
      customOpenAIProvider = CustomOpenAIProviderSettings(
        baseUrl = "https://llm.example.com/v1",
        chatCompletionsPath = "/chat/completions",
        compatibilityMode = "ollama",
      ),
    )

    val customProfile = ModelProviderCatalog.runtimeProfileFor(settings)
    val openAiProfile = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("openai"),
      settings,
    )

    assertEquals("custom-openai", customProfile.providerId)
    assertEquals("chat_completions", customProfile.apiMode.id)
    assertEquals("https://llm.example.com/v1", customProfile.baseUrl)
    assertEquals("https://llm.example.com/v1/models", customProfile.modelsUrl)
    assertEquals("/chat/completions", customProfile.chatCompletionsPath)
    assertEquals("ollama", customProfile.requestProfile.compatibilityMode)
    assertTrue(customProfile.requestProfile.injectOllamaNumCtx)
    assertFalse(customProfile.supportsHealthCheck)
    assertEquals("https://api.openai.com/v1", openAiProfile.baseUrl)
    assertEquals("/v1/chat/completions", openAiProfile.chatCompletionsPath)
    assertFalse(openAiProfile.requestProfile.injectOllamaNumCtx)
  }

  @Test
  fun ollamaRuntimeProfileInjectsContextWindowAsNumCtx() {
    val request = """{"model":"qwen3","messages":[],"temperature":0.7}"""

    val updated = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = ProviderRequestProfile(compatibilityMode = "ollama", injectOllamaNumCtx = true),
      modelContext = ModelContextSpec(contextWindowTokens = 65_536),
    )
    val unchanged = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = ProviderRequestProfile(),
      modelContext = ModelContextSpec(contextWindowTokens = 65_536),
    )

    assertTrue(updated.contains("\"options\":{\"num_ctx\":65536}"))
    assertEquals(request, unchanged)
  }

  @Test
  fun anthropicProviderDeclaresNativeApiMode() {
    val provider = ModelProviderCatalog.requireProvider("anthropic")

    assertEquals("anthropic_messages", provider.apiMode.id)
    assertTrue(provider.aliases.contains("claude-code"))
    assertEquals("https://api.anthropic.com", provider.baseUrl)
  }

  @Test
  fun deepSeekProviderDeclaresContextMetadata() {
    val provider = ModelProviderCatalog.requireProvider("deepseek")
    val context = provider.contextFor("deepseek-v4-pro")

    assertEquals(1_000_000, context.contextWindowTokens)
    assertEquals("deepseek_catalog", context.source)
    assertEquals("estimate", context.usageSource)
    assertEquals(82, context.compressionThresholdPercent)
    assertEquals(1_000_000L, provider.createModel("deepseek-v4-pro").contextLength)
  }

  @Test
  fun modelContextMetadataCanBeOverriddenFromSettings() {
    val settings = AppSettings()
      .withModelContextOverride(
        providerId = "deepseek",
        modelId = "deepseek-v4-pro",
        override = ModelContextOverride(
          contextWindowTokens = 256_000,
          compressionThresholdPercent = 70,
        ),
      )

    val context = ModelProviderCatalog.contextFor(settings)
    val model = ModelProviderCatalog.requireProvider("deepseek").createModel(settings.model, context)

    assertEquals(256_000, context.contextWindowTokens)
    assertEquals("user_override", context.source)
    assertEquals(70, context.compressionThresholdPercent)
    assertEquals(256_000L, model.contextLength)
  }

  @Test
  fun networkToolsDefaultToDisabled() {
    assertFalse(AppSettings().networkEnabled)
    assertFalse(AppSettings().webSearchEnabled)
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
          webSearchEnabled = true,
          language = "zh",
          maxAgentIterations = 120,
          agentAuthorityMode = "assisted",
          deepSeekThinkingEffort = "max",
          customOpenAIBaseUrl = "https://llm.example.com/",
          customOpenAIChatCompletionsPath = "v1/chat/completions",
          customOpenAICompatibilityMode = "ollama",
          modelContextWindowTokens = 512_000,
          modelCompressionThresholdPercent = 75,
        ),
      )

      assertEquals("light", updated.themeMode)
      assertEquals("#C989B8", updated.themeColor)
      assertTrue(updated.networkEnabled)
      assertTrue(updated.webSearchEnabled)
      assertEquals("zh", updated.language)
      assertEquals(80, updated.maxAgentIterations)
      assertEquals("assisted", updated.agentAuthorityMode)
      assertEquals("max", updated.deepSeekThinkingEffort)
      assertEquals("https://llm.example.com", updated.customOpenAIProvider.baseUrl)
      assertEquals("/v1/chat/completions", updated.customOpenAIProvider.chatCompletionsPath)
      assertEquals("ollama", updated.customOpenAIProvider.compatibilityMode)
      val override = updated.modelContextOverrideFor("deepseek", "deepseek-v4-pro")
      assertEquals(512_000, override?.contextWindowTokens)
      assertEquals(75, override?.compressionThresholdPercent)
    } finally {
      store.save(original)
    }
  }

  @Test
  fun settingsControllerPinsHtmlPathsDistinctly() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val original = store.load()
    try {
      val controller = SettingsController(store)

      val first = controller.setPinnedHtmlPath(AppSettings(), "index.html", true)
      val second = controller.setPinnedHtmlPath(first, "nested/page.html", true)
      val unpinned = controller.setPinnedHtmlPath(second, "index.html", false)

      assertEquals(listOf("nested/page.html", "index.html"), second.pinnedHtmlPaths)
      assertEquals(listOf("nested/page.html"), unpinned.pinnedHtmlPaths)
    } finally {
      store.save(original)
    }
  }

  @Test
  fun settingsControllerTracksRecentHtmlSelections() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val original = store.load()
    try {
      val controller = SettingsController(store)

      val first = controller.setSelectedHtml(AppSettings(), "index.html")
      val second = controller.setSelectedHtml(first, "nested/page.html")
      val repeated = controller.setSelectedHtml(second, "index.html")

      assertEquals("index.html", repeated.selectedHtmlPath)
      assertEquals(listOf("index.html", "nested/page.html"), repeated.recentHtmlPaths)
      assertEquals(repeated.recentHtmlPaths, store.load().recentHtmlPaths)
    } finally {
      store.save(original)
    }
  }

  @Test
  fun settingsControllerPersistsWebSearchSettings() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val original = store.load()
    try {
      val controller = SettingsController(store)

      val updated = controller.setWebSearch(AppSettings(), enabled = true, braveApiKey = " brave-key ")

      assertTrue(updated.webSearchEnabled)
      assertEquals("brave-key", updated.braveSearchApiKey)
      assertEquals("brave-key", store.load().braveSearchApiKey)
    } finally {
      store.save(original)
    }
  }

  @Test
  fun settingsControllerExtractsBraveKeyFromPastedText() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val original = store.load()
    try {
      val controller = SettingsController(store)
      val pasted = """
        BSAmkdXRBkbVDqD6mHralmPbYtSY5JH
        unrelated pasted notification text
        brave key
        BSAmkdXRBkbVDqD6mHralmPbYtSY5JH
      """.trimIndent()

      val updated = controller.setWebSearch(AppSettings(), enabled = true, braveApiKey = pasted)

      assertTrue(updated.webSearchEnabled)
      assertEquals("BSAmkdXRBkbVDqD6mHralmPbYtSY5JH", updated.braveSearchApiKey)
      assertEquals("BSAmkdXRBkbVDqD6mHralmPbYtSY5JH", store.load().braveSearchApiKey)
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

      val customSaved = controller.saveModelSettings(
        saved,
        ModelSettingsDraft(
          providerId = "custom-openai",
          model = " custom-model ",
          apiKey = " custom-key ",
          customOpenAIBaseUrl = "https://llm.example.com/v1/",
          customOpenAIChatCompletionsPath = "chat/completions",
          customOpenAICompatibilityMode = "OLLAMA",
        ),
      )
      assertEquals("custom-openai", customSaved.provider)
      assertEquals("custom-model", customSaved.model)
      assertEquals("custom-key", customSaved.apiKeyFor("custom-openai"))
      assertEquals("https://llm.example.com/v1", customSaved.customOpenAIProvider.baseUrl)
      assertEquals("/chat/completions", customSaved.customOpenAIProvider.chatCompletionsPath)
      assertEquals("ollama", customSaved.customOpenAIProvider.compatibilityMode)

      val aliasSaved = controller.saveModelSettings(
        customSaved,
        ModelSettingsDraft(providerId = "claude", model = "", apiKey = " anthropic-key "),
      )
      assertEquals("anthropic", aliasSaved.provider)
      assertEquals(ModelProviderCatalog.requireProvider("anthropic").defaultModel, aliasSaved.model)
      assertEquals("anthropic-key", aliasSaved.apiKeyFor("anthropic"))
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
