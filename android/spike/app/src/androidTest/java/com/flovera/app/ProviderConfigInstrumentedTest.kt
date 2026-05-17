package com.flovera.app

import ai.koog.prompt.llm.LLMProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.config.AppSettings
import com.flovera.app.config.CustomOpenAIProviderSettings
import com.flovera.app.config.ModelContextOverride
import com.flovera.app.config.ModelSettingsDraft
import com.flovera.app.config.OpenRouterProviderSettings
import com.flovera.app.config.SettingsProposalChanges
import com.flovera.app.config.SettingsController
import com.flovera.app.config.SettingsStore
import com.flovera.app.koog.ModelContextSpec
import com.flovera.app.koog.ModelProviderCatalog
import com.flovera.app.koog.ProviderRequestContext
import com.flovera.app.koog.ProviderRequestProfile
import com.flovera.app.koog.ProviderTransport
import com.flovera.app.koog.applyFloveraOpenAIRequestProfileToJson
import com.flovera.app.koog.hookIds
import com.flovera.app.koog.providerRuntimeHeaders
import com.flovera.app.koog.providerReasoningConfigFromEffort
import com.flovera.app.workspace.WorkspaceManager
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    assertEquals(AppSettings.LMSTUDIO_NOAUTH_PLACEHOLDER, settings.apiKeyFor("lmstudio"))
  }

  @Test
  fun providerCatalogHasDefaultModels() {
    assertTrue(ModelProviderCatalog.providers.size >= 24)
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
    assertEquals("openrouter", ModelProviderCatalog.findProvider("or")?.id)
    assertEquals("alibaba", ModelProviderCatalog.findProvider("dashscope")?.id)
    assertEquals("zai", ModelProviderCatalog.findProvider("zhipu")?.id)
    assertEquals("huggingface", ModelProviderCatalog.findProvider("hf")?.id)
    assertEquals("nvidia", ModelProviderCatalog.findProvider("nvidia-nim")?.id)
    assertEquals("novita", ModelProviderCatalog.findProvider("novitaai")?.id)
    assertEquals("moonshot", ModelProviderCatalog.findProvider("kimi")?.id)
    assertEquals("ai-gateway", ModelProviderCatalog.findProvider("vercel")?.id)
    assertEquals("alibaba-coding-plan", ModelProviderCatalog.findProvider("alibaba_coding")?.id)
    assertEquals("arcee", ModelProviderCatalog.findProvider("arceeai")?.id)
    assertEquals("kilocode", ModelProviderCatalog.findProvider("kilo")?.id)
    assertEquals("opencode-zen", ModelProviderCatalog.findProvider("opencode")?.id)
    assertEquals("opencode-go", ModelProviderCatalog.findProvider("go")?.id)
    assertEquals("xiaomi", ModelProviderCatalog.findProvider("mimo")?.id)
    assertEquals("kimi-coding-cn", ModelProviderCatalog.findProvider("moonshot-cn")?.id)
    assertEquals("lmstudio", ModelProviderCatalog.findProvider("lm-studio")?.id)
    assertEquals("lmstudio", ModelProviderCatalog.findProvider("lm_studio")?.id)
    assertEquals("tencent-tokenhub", ModelProviderCatalog.findProvider("tencent")?.id)
    assertEquals("tencent-tokenhub", ModelProviderCatalog.findProvider("tokenhub")?.id)
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
    assertEquals(ProviderTransport.FloveraOpenAICompatibleChatCompletions, alibaba.transport)
    assertEquals("chat_completions", profile.apiMode.id)
    assertEquals(ProviderTransport.FloveraOpenAICompatibleChatCompletions, profile.transport)
    assertEquals("https://dashscope-intl.aliyuncs.com/compatible-mode/v1", profile.baseUrl)
    assertEquals("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/models", profile.modelsUrl)
    assertEquals("/v1/chat/completions", profile.chatCompletionsPath)
    assertEquals("generic", profile.requestProfile.compatibilityMode)
    assertFalse(profile.requestProfile.injectOllamaNumCtx)
    assertEquals(LLMProvider.OpenRouter, openRouter.llmProvider)
    assertEquals(ProviderTransport.FloveraOpenAICompatibleChatCompletions, openRouter.transport)
    assertEquals(
      "https://openrouter.ai/api/v1",
      ModelProviderCatalog.runtimeProfileFor(openRouter, AppSettings()).baseUrl,
    )
  }

  @Test
  fun hermesOpenAICompatibleProfilesCarryEquivalentMetadata() {
    val aiGateway = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("ai-gateway"),
      AppSettings(provider = "ai-gateway", model = "moonshotai/kimi-k2.6"),
    )
    val xiaomi = ModelProviderCatalog.requireProvider("xiaomi")
    val opencodeGo = ModelProviderCatalog.requireProvider("opencode-go")
    val kimiCn = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("kimi-coding-cn"),
      AppSettings(provider = "kimi-coding-cn", model = "kimi-k2.6"),
    )
    val lmStudio = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("lmstudio"),
      AppSettings(provider = "lmstudio", model = "local-model"),
    )
    val tokenHub = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("tencent-tokenhub"),
      AppSettings(provider = "tencent-tokenhub", model = "hy3-preview"),
    )

    assertEquals("https://ai-gateway.vercel.sh/v1", aiGateway.baseUrl)
    assertEquals("google/gemini-3-flash", aiGateway.defaultAuxModel)
    assertEquals("https://hermes-agent.nousresearch.com", aiGateway.defaultHeaders["HTTP-Referer"])
    assertEquals("Hermes Agent", aiGateway.defaultHeaders["X-Title"])
    assertEquals(listOf("add_request_fields"), aiGateway.requestProfile.hookIds())
    assertEquals("https://api.xiaomimimo.com/v1", xiaomi.baseUrl)
    assertFalse(xiaomi.supportsHealthCheck)
    assertEquals(1_048_576, xiaomi.contextFor("mimo-v2.5-pro").contextWindowTokens)
    assertEquals("glm-5", opencodeGo.defaultAuxModel)
    assertEquals("https://api.moonshot.cn/v1", kimiCn.baseUrl)
    assertEquals(listOf("omit_request_fields", "inject_kimi_thinking"), kimiCn.requestProfile.hookIds())
    assertEquals("http://127.0.0.1:1234/v1", lmStudio.baseUrl)
    assertEquals(listOf("inject_lmstudio_reasoning"), lmStudio.requestProfile.hookIds())
    assertEquals("https://tokenhub.tencentmaas.com/v1", tokenHub.baseUrl)
    assertEquals("hy3-preview", tokenHub.defaultAuxModel)
    assertEquals(listOf("inject_tencent_tokenhub_reasoning"), tokenHub.requestProfile.hookIds())
  }

  @Test
  fun aiGatewayProfileAddsHermesDefaultReasoningBody() {
    val request = """{"model":"moonshotai/kimi-k2.6","messages":[]}"""
    val profile = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("ai-gateway"),
      AppSettings(provider = "ai-gateway", model = "moonshotai/kimi-k2.6"),
    )

    val updated = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
    )

    assertTrue(updated.contains("\"reasoning\":{\"enabled\":true,\"effort\":\"medium\"}"))
  }

  @Test
  fun providerProfilesDeclareExplicitTransports() {
    assertEquals(
      ProviderTransport.FloveraDeepSeekChatCompletions,
      ModelProviderCatalog.requireProvider("deepseek").transport,
    )
    assertEquals(
      ProviderTransport.KoogAnthropicMessages,
      ModelProviderCatalog.requireProvider("anthropic").transport,
    )
    assertEquals(
      ProviderTransport.FloveraOpenAICompatibleChatCompletions,
      ModelProviderCatalog.requireProvider("moonshot").transport,
    )
  }

  @Test
  fun openRouterUsesFloveraOpenAICompatibleTransportWithProviderIdentity() {
    val provider = ModelProviderCatalog.requireProvider("openrouter")
    val settings = AppSettings(provider = "openrouter", model = "anthropic/claude-sonnet-4.6")

    val client = ModelProviderCatalog.createClient(provider, apiKey = "openrouter-key", settings = settings)

    assertEquals(ProviderTransport.FloveraOpenAICompatibleChatCompletions, provider.transport)
    assertEquals(LLMProvider.OpenRouter, provider.llmProvider)
    assertEquals(LLMProvider.OpenRouter, client.llmProvider())
  }

  @Test
  fun openRouterGrokSessionHeaderMatchesHermesHook() {
    val provider = ModelProviderCatalog.requireProvider("openrouter")
    val grokSettings = AppSettings(
      provider = "openrouter",
      model = "x-ai/grok-4.20-reasoning",
      activeSessionId = "session-123",
    )
    val xaiSettings = grokSettings.copy(model = "xai/grok-4.20-reasoning")
    val nonGrokSettings = grokSettings.copy(model = "anthropic/claude-sonnet-4.6")

    assertEquals(
      "session-123",
      providerRuntimeHeaders(ModelProviderCatalog.runtimeProfileFor(provider, grokSettings), grokSettings)
        ["x-grok-conv-id"],
    )
    assertEquals(
      "session-123",
      providerRuntimeHeaders(ModelProviderCatalog.runtimeProfileFor(provider, xaiSettings), xaiSettings)
        ["x-grok-conv-id"],
    )
    assertFalse(
      "x-grok-conv-id" in providerRuntimeHeaders(
        ModelProviderCatalog.runtimeProfileFor(provider, nonGrokSettings),
        nonGrokSettings,
      ),
    )
  }

  @Test
  fun openRouterRoutingRequestHookMatchesHermesBodyExtras() {
    val preferences = JsonObject(
      mapOf(
        "sort" to JsonPrimitive("latency"),
        "allow_fallbacks" to JsonPrimitive(false),
      ),
    )
    val settings = AppSettings(
      provider = "openrouter",
      model = "openrouter/pareto-code",
      openRouterProvider = OpenRouterProviderSettings(
        providerPreferences = preferences,
        minCodingScore = 0.7,
      ),
    )
    val profile = ModelProviderCatalog.runtimeProfileFor(ModelProviderCatalog.requireProvider("openrouter"), settings)

    val updated = applyFloveraOpenAIRequestProfileToJson(
      requestJson = """{"model":"openrouter/pareto-code","messages":[]}""",
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(
        providerId = "openrouter",
        modelId = "openrouter/pareto-code",
        supportsReasoning = false,
        openRouterProviderPreferences = preferences,
        openRouterMinCodingScore = 0.7,
      ),
    )
    val nonPareto = applyFloveraOpenAIRequestProfileToJson(
      requestJson = """{"model":"anthropic/claude-sonnet-4.6","messages":[]}""",
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(
        providerId = "openrouter",
        modelId = "anthropic/claude-sonnet-4.6",
        supportsReasoning = false,
        openRouterProviderPreferences = preferences,
        openRouterMinCodingScore = 0.7,
      ),
    )

    assertEquals(listOf("inject_openrouter_routing"), profile.requestProfile.hookIds())
    assertTrue(updated.contains("\"provider\":{\"sort\":\"latency\",\"allow_fallbacks\":false}"))
    assertTrue(updated.contains("\"plugins\":[{\"id\":\"pareto-router\",\"min_coding_score\":0.7}]"))
    assertTrue(nonPareto.contains("\"provider\":{\"sort\":\"latency\",\"allow_fallbacks\":false}"))
    assertFalse(nonPareto.contains("\"plugins\""))
  }

  @Test
  fun openRouterReasoningRequestHookIsModelCapabilityGated() {
    val profile = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("openrouter"),
      AppSettings(provider = "openrouter", model = "anthropic/claude-sonnet-4.6"),
    )

    val defaultReasoning = applyFloveraOpenAIRequestProfileToJson(
      requestJson = """{"model":"anthropic/claude-sonnet-4.6","messages":[]}""",
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(
        providerId = "openrouter",
        modelId = "anthropic/claude-sonnet-4.6",
        supportsReasoning = true,
      ),
    )
    val disabledReasoning = applyFloveraOpenAIRequestProfileToJson(
      requestJson = """{"model":"anthropic/claude-sonnet-4.6","messages":[]}""",
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(
        providerId = "openrouter",
        modelId = "anthropic/claude-sonnet-4.6",
        supportsReasoning = true,
        reasoningConfig = providerReasoningConfigFromEffort("none"),
      ),
    )
    val unsupportedModel = applyFloveraOpenAIRequestProfileToJson(
      requestJson = """{"model":"deepseek/deepseek-chat","messages":[]}""",
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(
        providerId = "openrouter",
        modelId = "deepseek/deepseek-chat",
        supportsReasoning = false,
        reasoningConfig = providerReasoningConfigFromEffort("high"),
      ),
    )

    assertTrue(ModelProviderCatalog.contextFor(AppSettings(provider = "openrouter", model = "openai/gpt-5.4")).supportsReasoning)
    assertFalse(
      ModelProviderCatalog.contextFor(AppSettings(provider = "openrouter", model = "deepseek/deepseek-chat"))
        .supportsReasoning,
    )
    assertTrue(defaultReasoning.contains("\"reasoning\":{\"enabled\":true,\"effort\":\"medium\"}"))
    assertTrue(disabledReasoning.contains("\"reasoning\":{\"enabled\":false}"))
    assertFalse(unsupportedModel.contains("\"reasoning\""))
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
    assertEquals(listOf("omit_request_fields", "inject_kimi_thinking"), profile.requestProfile.hookIds())
    assertFalse(updated.contains("\"temperature\""))
    assertTrue(updated.contains("\"top_p\":0.9"))
    assertTrue(updated.contains("\"thinking\":{\"type\":\"enabled\"}"))
    assertTrue(updated.contains("\"reasoning_effort\":\"medium\""))
  }

  @Test
  fun kimiThinkingHookMatchesHermesReasoningEffortMapping() {
    val request = """{"model":"kimi-k2","messages":[],"temperature":0.7}"""
    val profile = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("moonshot"),
      AppSettings(provider = "moonshot", model = "kimi-k2-turbo-preview"),
    )

    val high = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(reasoningConfig = providerReasoningConfigFromEffort("high")),
    )
    val unsupportedEffort = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(reasoningConfig = providerReasoningConfigFromEffort("xhigh")),
    )
    val off = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(reasoningConfig = providerReasoningConfigFromEffort("none")),
    )

    assertTrue(high.contains("\"thinking\":{\"type\":\"enabled\"}"))
    assertTrue(high.contains("\"reasoning_effort\":\"high\""))
    assertTrue(unsupportedEffort.contains("\"reasoning_effort\":\"medium\""))
    assertTrue(off.contains("\"thinking\":{\"type\":\"disabled\"}"))
    assertFalse(off.contains("\"reasoning_effort\""))
  }

  @Test
  fun tencentTokenHubReasoningHookMatchesHermesMapping() {
    val request = """{"model":"hy3-preview","messages":[]}"""
    val profile = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("tencent-tokenhub"),
      AppSettings(provider = "tencent-tokenhub", model = "hy3-preview"),
    )

    val defaultEffort = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
    )
    val low = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(reasoningConfig = providerReasoningConfigFromEffort("low")),
    )
    val unsupportedEffort = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(reasoningConfig = providerReasoningConfigFromEffort("xhigh")),
    )
    val off = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(reasoningConfig = providerReasoningConfigFromEffort("none")),
    )

    assertTrue(defaultEffort.contains("\"reasoning_effort\":\"high\""))
    assertTrue(low.contains("\"reasoning_effort\":\"low\""))
    assertTrue(unsupportedEffort.contains("\"reasoning_effort\":\"high\""))
    assertFalse(off.contains("\"reasoning_effort\""))
  }

  @Test
  fun lmStudioReasoningHookMatchesHermesNoOptionsFallback() {
    val request = """{"model":"local-model","messages":[]}"""
    val profile = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("lmstudio"),
      AppSettings(provider = "lmstudio", model = "local-model"),
    )

    val unsupportedModel = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(supportsReasoning = false),
    )
    val xhigh = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(
        supportsReasoning = true,
        reasoningConfig = providerReasoningConfigFromEffort("xhigh"),
      ),
    )
    val off = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(
        supportsReasoning = true,
        reasoningConfig = providerReasoningConfigFromEffort("none"),
      ),
    )

    assertFalse(unsupportedModel.contains("\"reasoning_effort\""))
    assertTrue(xhigh.contains("\"reasoning_effort\":\"xhigh\""))
    assertTrue(off.contains("\"reasoning_effort\":\"none\""))
  }

  @Test
  fun openAICompatibleProviderProfilesCarryContextMetadata() {
    val alibaba = ModelProviderCatalog.contextFor(AppSettings(provider = "alibaba", model = "qwen3-coder-plus"))
    val moonshot = ModelProviderCatalog.contextFor(AppSettings(provider = "moonshot", model = "kimi-k2-turbo-preview"))
    val gmiKimi = ModelProviderCatalog.contextFor(AppSettings(provider = "gmi", model = "moonshotai/Kimi-K2.5"))
    val nvidia = ModelProviderCatalog.contextFor(
      AppSettings(provider = "nvidia", model = "nvidia/llama-3.3-70b-instruct"),
    )
    val ollamaCloud = ModelProviderCatalog.contextFor(
      AppSettings(provider = "ollama-cloud", model = "nemotron-3-nano:30b"),
    )
    val tokenHub = ModelProviderCatalog.contextFor(
      AppSettings(provider = "tencent-tokenhub", model = "hy3-preview"),
    )

    assertEquals(1_000_000, alibaba.contextWindowTokens)
    assertEquals("hermes_model_metadata", alibaba.source)
    assertEquals(262_144, moonshot.contextWindowTokens)
    assertEquals(262_144, gmiKimi.contextWindowTokens)
    assertEquals(131_072, nvidia.contextWindowTokens)
    assertEquals(131_072, ollamaCloud.contextWindowTokens)
    assertEquals(262_144, tokenHub.contextWindowTokens)
    assertTrue(tokenHub.supportsReasoning)
    assertEquals(82, moonshot.compressionThresholdPercent)
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
    assertEquals(
      listOf("inject_ollama_num_ctx"),
      ProviderRequestProfile(
        compatibilityMode = "ollama",
        injectOllamaNumCtx = true,
      ).hookIds(),
    )
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
          reasoningEffort = "XHIGH",
          customOpenAIBaseUrl = "https://llm.example.com/",
          customOpenAIChatCompletionsPath = "v1/chat/completions",
          customOpenAICompatibilityMode = "ollama",
          openRouterProviderPreferences = JsonObject(mapOf("sort" to JsonPrimitive("throughput"))),
          openRouterMinCodingScore = 1.2,
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
      assertEquals("xhigh", updated.reasoningEffort)
      assertEquals("https://llm.example.com", updated.customOpenAIProvider.baseUrl)
      assertEquals("/v1/chat/completions", updated.customOpenAIProvider.chatCompletionsPath)
      assertEquals("ollama", updated.customOpenAIProvider.compatibilityMode)
      assertEquals("throughput", updated.openRouterProvider.providerPreferences["sort"]?.toString()?.trim('"'))
      assertEquals(null, updated.openRouterProvider.minCodingScore)
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
