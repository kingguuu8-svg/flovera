package com.flovera.app

import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolChoice
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.config.AppSettings
import com.flovera.app.config.CustomOpenAIProviderSettings
import com.flovera.app.config.ModelContextOverride
import com.flovera.app.config.ModelSettingsDraft
import com.flovera.app.config.OpenRouterProviderSettings
import com.flovera.app.config.SettingsProposalChanges
import com.flovera.app.config.SettingsController
import com.flovera.app.config.SettingsStore
import com.flovera.app.config.agentAllowedSecretEnvironment
import com.flovera.app.koog.ModelContextSpec
import com.flovera.app.koog.ModelProviderCatalog
import com.flovera.app.koog.ProviderRequestContext
import com.flovera.app.koog.ProviderRequestProfile
import com.flovera.app.koog.ProviderTransport
import com.flovera.app.koog.FloveraOpenAICompatibleLLMClient
import com.flovera.app.koog.FloveraGoogleCloudCodeAssistLLMClient
import com.flovera.app.koog.GoogleCloudCodeAssistCredentials
import com.flovera.app.koog.applyFloveraOpenAIRequestProfileToJson
import com.flovera.app.koog.buildGoogleCloudCodeAssistRequest
import com.flovera.app.koog.codexResponsesInclude
import com.flovera.app.koog.codexResponsesReasoningConfig
import com.flovera.app.koog.googleCloudCodeAssistErrorMessage
import com.flovera.app.koog.googleOAuthRefreshFormBody
import com.flovera.app.koog.grokSupportsReasoningEffort
import com.flovera.app.koog.hookIds
import com.flovera.app.koog.providerAnthropicRuntimeHeaders
import com.flovera.app.koog.providerKtorRoute
import com.flovera.app.koog.providerKtorRouteCandidates
import com.flovera.app.koog.providerRuntimeHeaders
import com.flovera.app.koog.providerReasoningConfigFromEffort
import com.flovera.app.koog.translateGoogleCloudCodeAssistStreamEvent
import com.flovera.app.koog.translateGoogleCloudCodeAssistResponseToOpenAIJson
import com.flovera.app.workspace.WorkspaceManager
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    assertTrue(ModelProviderCatalog.providers.size >= 37)
    ModelProviderCatalog.providers.forEach { provider ->
      assertTrue(provider.id.isNotBlank())
      assertTrue(provider.defaultModel.isNotBlank())
      assertTrue(provider.suggestedModels.contains(provider.defaultModel))
      assertTrue(provider.apiMode.id.isNotBlank())
    }
    assertTrue(ModelProviderCatalog.supportedApiModes.contains("chat_completions"))
    assertTrue(ModelProviderCatalog.supportedApiModes.contains("anthropic_messages"))
    assertTrue(ModelProviderCatalog.supportedApiModes.contains("bedrock_converse"))
    assertTrue(ModelProviderCatalog.supportedApiModes.contains("codex_responses"))
    assertEquals(listOf("deepseek"), ModelProviderCatalog.selectableProviders.map { it.id })
    assertEquals(listOf("chat_completions"), ModelProviderCatalog.supportedSelectableApiModes)
    assertEquals(null, ModelProviderCatalog.findSelectableProvider("openai"))
    assertEquals("deepseek", ModelProviderCatalog.findSelectableProvider("deepseek")?.id)
  }

  @Test
  fun providerCatalogNormalizesAliasesToCanonicalProfiles() {
    assertEquals("anthropic", ModelProviderCatalog.findProvider("claude")?.id)
    assertEquals("custom-openai", ModelProviderCatalog.findProvider("custom")?.id)
    assertEquals("custom-openai", ModelProviderCatalog.findProvider("ollama")?.id)
    assertEquals("openrouter", ModelProviderCatalog.findProvider("router")?.id)
    assertEquals("openrouter", ModelProviderCatalog.findProvider("or")?.id)
    assertEquals("alibaba", ModelProviderCatalog.findProvider("dashscope")?.id)
    assertEquals("zai", ModelProviderCatalog.findProvider("zhipu")?.id)
    assertEquals("huggingface", ModelProviderCatalog.findProvider("hf")?.id)
    assertEquals("nvidia", ModelProviderCatalog.findProvider("nvidia-nim")?.id)
    assertEquals("novita", ModelProviderCatalog.findProvider("novitaai")?.id)
    assertEquals("moonshot", ModelProviderCatalog.findProvider("kimi-coding")?.id)
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
    assertEquals("minimax", ModelProviderCatalog.findProvider("mini-max")?.id)
    assertEquals("minimax", ModelProviderCatalog.findProvider("minimax-global")?.id)
    assertEquals("minimax-cn", ModelProviderCatalog.findProvider("minimax-china")?.id)
    assertEquals("minimax-cn", ModelProviderCatalog.findProvider("minimax_cn")?.id)
    assertEquals("gemini", ModelProviderCatalog.findProvider("google")?.id)
    assertEquals("gemini", ModelProviderCatalog.findProvider("google-gemini")?.id)
    assertEquals("gemini", ModelProviderCatalog.findProvider("google-ai-studio")?.id)
    assertEquals("google-gemini-cli", ModelProviderCatalog.findProvider("gemini-cli")?.id)
    assertEquals("google-gemini-cli", ModelProviderCatalog.findProvider("gemini-oauth")?.id)
    assertEquals("bedrock", ModelProviderCatalog.findProvider("aws")?.id)
    assertEquals("bedrock", ModelProviderCatalog.findProvider("aws-bedrock")?.id)
    assertEquals("bedrock", ModelProviderCatalog.findProvider("amazon-bedrock")?.id)
    assertEquals("bedrock", ModelProviderCatalog.findProvider("amazon")?.id)
    assertEquals("xai", ModelProviderCatalog.findProvider("grok")?.id)
    assertEquals("xai", ModelProviderCatalog.findProvider("x-ai")?.id)
    assertEquals("xai", ModelProviderCatalog.findProvider("x.ai")?.id)
    assertEquals("copilot", ModelProviderCatalog.findProvider("github-copilot")?.id)
    assertEquals("copilot", ModelProviderCatalog.findProvider("github-models")?.id)
    assertEquals("copilot", ModelProviderCatalog.findProvider("github-model")?.id)
    assertEquals("copilot", ModelProviderCatalog.findProvider("github")?.id)
    assertEquals("copilot-acp", ModelProviderCatalog.findProvider("github-copilot-acp")?.id)
    assertEquals("copilot-acp", ModelProviderCatalog.findProvider("copilot-acp-agent")?.id)
    assertEquals("azure-foundry", ModelProviderCatalog.findProvider("azure")?.id)
    assertEquals("azure-foundry", ModelProviderCatalog.findProvider("azure-ai-foundry")?.id)
    assertEquals("azure-foundry", ModelProviderCatalog.findProvider("azure-ai")?.id)
    assertEquals("stepfun", ModelProviderCatalog.findProvider("step")?.id)
    assertEquals("stepfun", ModelProviderCatalog.findProvider("stepfun-coding-plan")?.id)
    assertEquals("nous", ModelProviderCatalog.findProvider("nous-portal")?.id)
    assertEquals("nous", ModelProviderCatalog.findProvider("nousresearch")?.id)
    assertEquals("qwen-oauth", ModelProviderCatalog.findProvider("qwen")?.id)
    assertEquals("qwen-oauth", ModelProviderCatalog.findProvider("qwen-portal")?.id)
    assertEquals("qwen-oauth", ModelProviderCatalog.findProvider("qwen-cli")?.id)
    assertEquals("openai-codex", ModelProviderCatalog.findProvider("codex")?.id)
    assertEquals("openai-codex", ModelProviderCatalog.findProvider("openai_codex")?.id)
    assertEquals("minimax-oauth", ModelProviderCatalog.findProvider("minimax_oauth")?.id)
    assertEquals("minimax-oauth", ModelProviderCatalog.findProvider("minimax-oauth-io")?.id)
  }

  @Test
  fun hermesProviderEquivalenceMatrixCoversCurrentCatalog() {
    val fixtures = listOf(
      ProviderEquivalenceFixture("deepseek", "ordinary-api", "api_key", "chat_completions", "flovera_deepseek_chat_completions", "https://api.deepseek.com"),
      ProviderEquivalenceFixture("nous", "oauth-api", "oauth_device_code", "chat_completions", "flovera_openai_compatible_chat_completions", "https://inference-api.nousresearch.com/v1", listOf("inject_nous_portal_reasoning")),
      ProviderEquivalenceFixture("qwen-oauth", "oauth-api", "oauth_external", "chat_completions", "flovera_openai_compatible_chat_completions", "https://portal.qwen.ai/v1", listOf("inject_qwen_portal_request_shape")),
      ProviderEquivalenceFixture("openai", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://api.openai.com/v1"),
      ProviderEquivalenceFixture("azure-foundry", "ordinary-api-user-endpoint", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", ""),
      ProviderEquivalenceFixture("ai-gateway", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://ai-gateway.vercel.sh/v1", listOf("add_request_fields")),
      ProviderEquivalenceFixture("alibaba", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"),
      ProviderEquivalenceFixture("alibaba-coding-plan", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://coding-intl.dashscope.aliyuncs.com/v1"),
      ProviderEquivalenceFixture("moonshot", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://api.moonshot.ai/v1", listOf("omit_request_fields", "inject_kimi_thinking")),
      ProviderEquivalenceFixture("kimi-coding-cn", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://api.moonshot.cn/v1", listOf("omit_request_fields", "inject_kimi_thinking")),
      ProviderEquivalenceFixture("arcee", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://api.arcee.ai/api/v1"),
      ProviderEquivalenceFixture("gmi", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://api.gmi-serving.com/v1"),
      ProviderEquivalenceFixture("nvidia", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://integrate.api.nvidia.com/v1"),
      ProviderEquivalenceFixture("novita", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://api.novita.ai/openai/v1"),
      ProviderEquivalenceFixture("kilocode", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://api.kilo.ai/api/gateway"),
      ProviderEquivalenceFixture("opencode-zen", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://opencode.ai/zen/v1"),
      ProviderEquivalenceFixture("opencode-go", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://opencode.ai/zen/go/v1"),
      ProviderEquivalenceFixture("zai", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://api.z.ai/api/paas/v4"),
      ProviderEquivalenceFixture("stepfun", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://api.stepfun.ai/step_plan/v1"),
      ProviderEquivalenceFixture("huggingface", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://router.huggingface.co/v1"),
      ProviderEquivalenceFixture("ollama-cloud", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://ollama.com/v1"),
      ProviderEquivalenceFixture("lmstudio", "ordinary-api-local", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "http://127.0.0.1:1234/v1", listOf("inject_lmstudio_reasoning")),
      ProviderEquivalenceFixture("xiaomi", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://api.xiaomimimo.com/v1"),
      ProviderEquivalenceFixture("tencent-tokenhub", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://tokenhub.tencentmaas.com/v1", listOf("inject_tencent_tokenhub_reasoning")),
      ProviderEquivalenceFixture("custom-openai", "ordinary-api-user-endpoint", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", ""),
      ProviderEquivalenceFixture("openai-codex", "oauth-api", "oauth_external", "codex_responses", "flovera_codex_responses", "https://chatgpt.com/backend-api/codex"),
      ProviderEquivalenceFixture("copilot", "optional-api", "copilot", "codex_responses", "flovera_codex_responses", "https://api.githubcopilot.com", listOf("inject_copilot_reasoning")),
      ProviderEquivalenceFixture("copilot-acp", "unsupported-boundary", "external_process", "chat_completions", "flovera_external_process", "acp://copilot"),
      ProviderEquivalenceFixture("bedrock", "native-sdk-api", "aws_sdk", "bedrock_converse", "koog_bedrock_converse", "https://bedrock-runtime.us-east-1.amazonaws.com"),
      ProviderEquivalenceFixture("gemini", "native-api", "api_key", "chat_completions", "koog_google_gemini_native", "https://generativelanguage.googleapis.com/v1beta"),
      ProviderEquivalenceFixture("google-gemini-cli", "optional-oauth-api", "oauth_external", "chat_completions", "flovera_google_cloud_code_assist", "cloudcode-pa://google"),
      ProviderEquivalenceFixture("xai", "ordinary-api", "api_key", "codex_responses", "flovera_codex_responses", "https://api.x.ai/v1"),
      ProviderEquivalenceFixture("openrouter", "ordinary-api", "api_key", "chat_completions", "flovera_openai_compatible_chat_completions", "https://openrouter.ai/api/v1", listOf("inject_openrouter_routing")),
      ProviderEquivalenceFixture("minimax", "ordinary-anthropic-api", "bearer_token", "anthropic_messages", "flovera_anthropic_messages", "https://api.minimax.io/anthropic"),
      ProviderEquivalenceFixture("minimax-cn", "ordinary-anthropic-api", "bearer_token", "anthropic_messages", "flovera_anthropic_messages", "https://api.minimaxi.com/anthropic"),
      ProviderEquivalenceFixture("minimax-oauth", "oauth-api", "oauth_external", "anthropic_messages", "flovera_anthropic_messages", "https://api.minimax.io/anthropic"),
      ProviderEquivalenceFixture("anthropic", "ordinary-anthropic-api", "api_key", "anthropic_messages", "flovera_anthropic_messages", "https://api.anthropic.com"),
    )

    assertEquals(
      fixtures.map { it.providerId }.toSet(),
      ModelProviderCatalog.providers.map { it.id }.toSet(),
    )

    fixtures.forEach { fixture ->
      val provider = ModelProviderCatalog.requireProvider(fixture.providerId)
      val profile = ModelProviderCatalog.runtimeProfileFor(
        provider,
        AppSettings(provider = fixture.providerId, model = provider.defaultModel),
      )

      assertTrue(fixture.tier in providerEquivalenceTiers)
      assertEquals(fixture.authType, profile.authType.id)
      assertEquals(fixture.apiMode, profile.apiMode.id)
      assertEquals(fixture.transport, profile.transport.id)
      assertEquals(fixture.baseUrl, profile.baseUrl)
      assertEquals(fixture.requestHookIds, profile.requestProfile.hookIds())
      if (fixture.tier == "unsupported-boundary") {
        assertFalse(profile.supportsHealthCheck)
      }
    }
  }

  @Test
  fun dynamicProviderRoutesMatchHermesFixtures() {
    val routeFixtures = listOf(
      RouteFixture("copilot", "gpt-5", "codex_responses", "flovera_codex_responses", "responses"),
      RouteFixture("copilot", "gpt-5-mini", "chat_completions", "flovera_openai_compatible_chat_completions", "/chat/completions"),
      RouteFixture("copilot", "claude-sonnet-4.6", "anthropic_messages", "flovera_anthropic_messages", "/v1/messages"),
      RouteFixture("azure-foundry", "gpt-5.3-codex", "codex_responses", "flovera_codex_responses", "models/responses"),
      RouteFixture("azure-foundry", "gpt-4o", "chat_completions", "flovera_openai_compatible_chat_completions", "/models/chat/completions"),
    )

    routeFixtures.forEach { fixture ->
      val settings = AppSettings(
        provider = fixture.providerId,
        model = fixture.model,
        customOpenAIProvider = CustomOpenAIProviderSettings(
          baseUrl = "https://example-resource.services.ai.azure.com",
          chatCompletionsPath = "/models/chat/completions",
        ),
      )
      val profile = ModelProviderCatalog.runtimeProfileFor(
        ModelProviderCatalog.requireProvider(fixture.providerId),
        settings,
      )

      assertEquals(fixture.apiMode, profile.apiMode.id)
      assertEquals(fixture.transport, profile.transport.id)
      assertEquals(
        fixture.requestPath,
        when (profile.apiMode.id) {
          "codex_responses" -> profile.responsesPath
          "anthropic_messages" -> profile.messagesPath
          else -> profile.chatCompletionsPath
        },
      )
    }
  }

  @Test
  fun providerKtorRoutesPreserveHermesBasePathPrefixes() {
    val openAi = providerKtorRoute(
      ModelProviderCatalog.runtimeProfileFor(
        ModelProviderCatalog.requireProvider("openai"),
        AppSettings(provider = "openai", model = "gpt-5.2"),
      ),
    )
    val alibaba = providerKtorRoute(
      ModelProviderCatalog.runtimeProfileFor(
        ModelProviderCatalog.requireProvider("alibaba"),
        AppSettings(provider = "alibaba", model = "qwen3-coder-plus"),
      ),
    )
    val zai = providerKtorRoute(
      ModelProviderCatalog.runtimeProfileFor(
        ModelProviderCatalog.requireProvider("zai"),
        AppSettings(provider = "zai", model = "glm-5"),
      ),
    )
    val xai = providerKtorRoute(
      ModelProviderCatalog.runtimeProfileFor(
        ModelProviderCatalog.requireProvider("xai"),
        AppSettings(provider = "xai", model = "grok-code-fast-2"),
      ),
    )
    val minimax = providerKtorRoute(
      ModelProviderCatalog.runtimeProfileFor(
        ModelProviderCatalog.requireProvider("minimax"),
        AppSettings(provider = "minimax", model = "MiniMax-M2.7"),
      ),
    )
    val custom = providerKtorRoute(
      ModelProviderCatalog.runtimeProfileFor(
        ModelProviderCatalog.requireProvider("custom-openai"),
        AppSettings(
          provider = "custom-openai",
          model = "local-model",
          customOpenAIProvider = CustomOpenAIProviderSettings(
            baseUrl = "https://local.example/v1",
            chatCompletionsPath = "/chat/completions",
          ),
        ),
      ),
    )

    assertEquals("https://api.openai.com", openAi.baseUrl)
    assertEquals("v1/chat/completions", openAi.chatCompletionsPath)

    assertEquals("https://dashscope-intl.aliyuncs.com", alibaba.baseUrl)
    assertEquals("compatible-mode/v1/chat/completions", alibaba.chatCompletionsPath)

    assertEquals("https://api.z.ai", zai.baseUrl)
    assertEquals("api/paas/v4/chat/completions", zai.chatCompletionsPath)

    assertEquals("https://api.x.ai", xai.baseUrl)
    assertEquals("v1/responses", xai.responsesPath)

    assertEquals("https://api.minimax.io", minimax.baseUrl)
    assertEquals("anthropic/v1/messages", minimax.messagesPath)

    assertEquals("https://local.example", custom.baseUrl)
    assertEquals("v1/chat/completions", custom.chatCompletionsPath)

    val zaiHermesV1PathCandidates = providerKtorRouteCandidates(
      ModelProviderCatalog.runtimeProfileFor(
        ModelProviderCatalog.requireProvider("zai"),
        AppSettings(provider = "zai", model = "glm-5"),
      ).copy(chatCompletionsPath = "/v1/chat/completions"),
    )
    assertEquals("api/paas/v4/v1/chat/completions", zaiHermesV1PathCandidates.first().chatCompletionsPath)
    assertTrue(zaiHermesV1PathCandidates.any { it.chatCompletionsPath == "api/paas/v4/chat/completions" })
  }

  @Test
  fun representativeOpenAICompatibleRequestsEmitHermesEquivalentGoldenBodies() {
    val openRouter = openAICompatibleRequestProbe(
      AppSettings(
        provider = "openrouter",
        model = "openrouter/pareto-code",
        openRouterProvider = OpenRouterProviderSettings(
          providerPreferences = JsonObject(mapOf("sort" to JsonPrimitive("latency"))),
          minCodingScore = 0.7,
        ),
      ),
    )
    val copilot = openAICompatibleRequestProbe(
      AppSettings(provider = "copilot", model = "gpt-5-mini", reasoningEffort = "xhigh"),
    )
    val kimi = openAICompatibleRequestProbe(
      AppSettings(provider = "moonshot", model = "kimi-k2-turbo-preview", reasoningEffort = "high"),
    )
    val customOllama = openAICompatibleRequestProbe(
      AppSettings(
        provider = "custom-openai",
        model = "local-model",
        customOpenAIProvider = CustomOpenAIProviderSettings(
          baseUrl = "https://local.example/v1",
          chatCompletionsPath = "/chat/completions",
          compatibilityMode = "ollama",
        ),
      ).withModelContextOverride(
        providerId = "custom-openai",
        modelId = "local-model",
        override = ModelContextOverride(contextWindowTokens = 65_536),
      ),
    )

    assertEquals("https://openrouter.ai/api/v1", openRouter.baseUrl)
    assertEquals("/v1/chat/completions", openRouter.requestPath)
    assertEquals("latency", openRouter.body["provider"]?.jsonObject?.get("sort")?.jsonPrimitive?.contentOrNull)
    assertEquals(
      "pareto-router",
      openRouter.body["plugins"]?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull,
    )
    assertEquals("0.7", openRouter.body["plugins"]?.jsonArray?.firstOrNull()?.jsonObject?.get("min_coding_score")?.jsonPrimitive?.contentOrNull)

    assertEquals("https://api.githubcopilot.com", copilot.baseUrl)
    assertEquals("/chat/completions", copilot.requestPath)
    assertEquals("vscode/1.104.1", copilot.headers["Editor-Version"])
    assertEquals("conversation-edits", copilot.headers["Openai-Intent"])
    assertEquals("high", copilot.body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.contentOrNull)

    assertEquals("https://api.moonshot.ai/v1", kimi.baseUrl)
    assertEquals("hermes-agent/1.0", kimi.headers["User-Agent"])
    assertEquals("enabled", kimi.body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
    assertEquals("high", kimi.body["reasoning_effort"]?.jsonPrimitive?.contentOrNull)

    assertEquals("https://local.example/v1", customOllama.baseUrl)
    assertEquals("/chat/completions", customOllama.requestPath)
    assertEquals("65536", customOllama.body["options"]?.jsonObject?.get("num_ctx")?.jsonPrimitive?.contentOrNull)
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

    val zai = ModelProviderCatalog.requireProvider("zai")
    val zaiProfile = ModelProviderCatalog.runtimeProfileFor(
      zai,
      AppSettings(provider = "zai", model = "glm-5"),
    )
    assertEquals("https://api.z.ai/api/paas/v4", zaiProfile.baseUrl)
    assertEquals("chat/completions", zaiProfile.chatCompletionsPath)

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
    val azureFoundry = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("azure-foundry"),
      AppSettings(
        provider = "azure-foundry",
        model = "gpt-4o",
        customOpenAIProvider = CustomOpenAIProviderSettings(
          baseUrl = "https://example-resource.services.ai.azure.com",
          chatCompletionsPath = "/models/chat/completions",
        ),
      ),
    )
    val azureFoundryResponses = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("azure-foundry"),
      AppSettings(
        provider = "azure-foundry",
        model = "gpt-5.3-codex",
        customOpenAIProvider = CustomOpenAIProviderSettings(
          baseUrl = "https://example-resource.services.ai.azure.com",
          chatCompletionsPath = "/models/chat/completions",
        ),
      ),
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
    assertEquals("https://example-resource.services.ai.azure.com", azureFoundry.baseUrl)
    assertEquals("https://example-resource.services.ai.azure.com/models", azureFoundry.modelsUrl)
    assertEquals("chat_completions", azureFoundry.apiMode.id)
    assertEquals(ProviderTransport.FloveraOpenAICompatibleChatCompletions, azureFoundry.transport)
    assertEquals("/models/chat/completions", azureFoundry.chatCompletionsPath)
    assertEquals("api_key", azureFoundry.authType.id)
    assertFalse(azureFoundry.supportsHealthCheck)
    assertEquals("codex_responses", azureFoundryResponses.apiMode.id)
    assertEquals(ProviderTransport.FloveraCodexResponses, azureFoundryResponses.transport)
    assertEquals("models/responses", azureFoundryResponses.responsesPath)
  }

  @Test
  fun hermesOAuthProfilesCarryEquivalentMetadata() {
    val nous = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("nous"),
      AppSettings(provider = "nous", model = "hermes-3-405b"),
    )
    val qwen = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("qwen-oauth"),
      AppSettings(provider = "qwen-oauth", model = "qwen3-coder-plus"),
    )
    val codex = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("openai-codex"),
      AppSettings(provider = "openai-codex", model = "gpt-5.3-codex", activeSessionId = "codex-session-123"),
    )
    val minimax = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("minimax-oauth"),
      AppSettings(provider = "minimax-oauth", model = "MiniMax-M2.7-highspeed"),
    )
    val codexHeaders = providerRuntimeHeaders(codex, AppSettings(activeSessionId = "codex-session-123"))

    assertEquals("oauth_device_code", nous.authType.id)
    assertEquals("https://inference-api.nousresearch.com/v1", nous.baseUrl)
    assertEquals(listOf("inject_nous_portal_reasoning"), nous.requestProfile.hookIds())
    assertEquals("hermes-3-405b", ModelProviderCatalog.requireProvider("nous").defaultAuxModel.ifBlank { ModelProviderCatalog.requireProvider("nous").defaultModel })
    assertEquals("oauth_external", qwen.authType.id)
    assertEquals("https://portal.qwen.ai/v1", qwen.baseUrl)
    assertEquals(65_536, qwen.defaultMaxTokens)
    assertEquals(listOf("inject_qwen_portal_request_shape"), qwen.requestProfile.hookIds())
    assertEquals("codex_responses", codex.apiMode.id)
    assertEquals(ProviderTransport.FloveraCodexResponses, codex.transport)
    assertEquals("oauth_external", codex.authType.id)
    assertEquals("https://chatgpt.com/backend-api/codex", codex.baseUrl)
    assertEquals("responses", codex.responsesPath)
    assertEquals("models", codex.modelsPath)
    assertFalse(codex.supportsHealthCheck)
    assertEquals("codex-session-123", codexHeaders["session_id"])
    assertEquals("codex-session-123", codexHeaders["x-client-request-id"])
    assertFalse("x-grok-conv-id" in codexHeaders)
    assertEquals("anthropic_messages", minimax.apiMode.id)
    assertEquals(ProviderTransport.FloveraAnthropicMessages, minimax.transport)
    assertEquals("oauth_external", minimax.authType.id)
    assertEquals("MiniMax-M2.7-highspeed", minimax.defaultAuxModel)
    assertEquals(
      "Bearer minimax-token",
      providerAnthropicRuntimeHeaders(minimax, "minimax-token")["Authorization"],
    )
  }

  @Test
  fun copilotProfileMirrorsHermesRoutingHeadersAndAuth() {
    val provider = ModelProviderCatalog.requireProvider("copilot")
    val chat = ModelProviderCatalog.runtimeProfileFor(
      provider,
      AppSettings(provider = "copilot", model = "gpt-5-mini"),
    )
    val responses = ModelProviderCatalog.runtimeProfileFor(
      provider,
      AppSettings(provider = "copilot", model = "gpt-5"),
    )
    val anthropic = ModelProviderCatalog.runtimeProfileFor(
      provider,
      AppSettings(provider = "copilot", model = "claude-sonnet-4.6"),
    )
    val chatHeaders = providerRuntimeHeaders(chat, AppSettings(provider = "copilot", model = "gpt-5-mini"))
    val anthropicHeaders = providerAnthropicRuntimeHeaders(anthropic, "copilot-token")

    assertEquals("copilot", chat.authType.id)
    assertEquals("https://api.githubcopilot.com", chat.baseUrl)
    assertEquals("https://api.githubcopilot.com/models", chat.modelsUrl)
    assertEquals("chat_completions", chat.apiMode.id)
    assertEquals(ProviderTransport.FloveraOpenAICompatibleChatCompletions, chat.transport)
    assertEquals("/chat/completions", chat.chatCompletionsPath)
    assertEquals("vscode/1.104.1", chatHeaders["Editor-Version"])
    assertEquals("HermesAgent/1.0", chatHeaders["User-Agent"])
    assertEquals("conversation-edits", chatHeaders["Openai-Intent"])
    assertEquals("agent", chatHeaders["x-initiator"])
    assertEquals("codex_responses", responses.apiMode.id)
    assertEquals(ProviderTransport.FloveraCodexResponses, responses.transport)
    assertEquals("responses", responses.responsesPath)
    assertEquals("models", responses.modelsPath)
    assertEquals("anthropic_messages", anthropic.apiMode.id)
    assertEquals(ProviderTransport.FloveraAnthropicMessages, anthropic.transport)
    assertEquals("/v1/messages", anthropic.messagesPath)
    assertEquals("Bearer copilot-token", anthropicHeaders["Authorization"])
    assertEquals("vscode/1.104.1", anthropicHeaders["Editor-Version"])
    assertFalse("x-api-key" in anthropicHeaders)
  }

  @Test
  fun externalHermesProfilesDeclareTransportBoundary() {
    val geminiCli = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("google-gemini-cli"),
      AppSettings(provider = "google-gemini-cli", model = "gemini-3-flash-preview"),
    )
    val copilotAcp = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("copilot-acp"),
      AppSettings(provider = "copilot-acp", model = "copilot-acp"),
    )

    assertEquals("chat_completions", geminiCli.apiMode.id)
    assertEquals(ProviderTransport.FloveraGoogleCloudCodeAssist, geminiCli.transport)
    assertEquals("cloudcode-pa://google", geminiCli.baseUrl)
    assertEquals("oauth_external", geminiCli.authType.id)
    assertFalse(geminiCli.supportsHealthCheck)
    assertEquals(
      1_048_576,
      ModelProviderCatalog.contextFor(
        AppSettings(provider = "google-gemini-cli", model = "gemini-3-flash-preview"),
      ).contextWindowTokens,
    )
    assertEquals("chat_completions", copilotAcp.apiMode.id)
    assertEquals(ProviderTransport.FloveraExternalProcess, copilotAcp.transport)
    assertEquals("acp://copilot", copilotAcp.baseUrl)
    assertEquals("external_process", copilotAcp.authType.id)
    assertFalse(copilotAcp.supportsHealthCheck)
  }

  @Test
  fun externalHermesProfilesCreateConcreteClientsOrFailExplicitly() {
    val geminiCli = ModelProviderCatalog.requireProvider("google-gemini-cli")
    val copilotAcp = ModelProviderCatalog.requireProvider("copilot-acp")

    val geminiClient = ModelProviderCatalog.createClient(
      geminiCli,
      apiKey = "oauth-token|flovera-project|managed-project",
      settings = AppSettings(provider = "google-gemini-cli", model = "gemini-3-flash-preview"),
    )
    val acpError = try {
      ModelProviderCatalog.createClient(
        copilotAcp,
        apiKey = "",
        settings = AppSettings(provider = "copilot-acp", model = "copilot-acp"),
      )
      null
    } catch (error: UnsupportedOperationException) {
      error
    }

    assertTrue(geminiClient is FloveraGoogleCloudCodeAssistLLMClient)
    assertTrue(acpError?.message.orEmpty().contains("flovera_external_process"))
    assertTrue(acpError?.message.orEmpty().contains("external process transport"))
  }

  @Test
  fun googleCloudCodeAssistRequestTranslationMatchesHermesEnvelope() {
    val credentials = GoogleCloudCodeAssistCredentials.from("oauth-token|flovera-project|managed-project")
    val wrapped = buildGoogleCloudCodeAssistRequest(
      openAIRequestJson = """
        {
          "model": "gemini-3-flash-preview",
          "messages": [
            {"role": "system", "content": "You are Flovera."},
            {"role": "user", "content": "hello"},
            {
              "role": "assistant",
              "content": "",
              "tool_calls": [
                {
                  "id": "call_1",
                  "type": "function",
                  "function": {"name": "read_file", "arguments": "{\"path\":\"README.md\"}"}
                }
              ]
            },
            {"role": "tool", "tool_call_id": "read_file", "content": "{\"output\":\"ok\"}"}
          ],
          "tools": [
            {
              "type": "function",
              "function": {
                "name": "read_file",
                "description": "Read a file",
                "parameters": {
                  "type": "object",
                  "additionalProperties": false,
                  "properties": {
                    "path": {"type": "string"},
                    "mode": {"type": "integer", "enum": [1, 2]}
                  },
                  "required": ["path"]
                }
              }
            }
          ],
          "tool_choice": {"type": "function", "function": {"name": "read_file"}},
          "temperature": 0.2,
          "max_tokens": 64,
          "top_p": 0.9,
          "stop": ["END"],
          "extra_body": {"thinking_config": {"thinking_budget": 256, "thinking_level": "HIGH", "include_thoughts": true}}
        }
      """.trimIndent(),
      projectId = credentials.projectId,
      userPromptId = "prompt-1",
    )
    val root = Json.parseToJsonElement(wrapped).jsonObject
    val request = root["request"]!!.jsonObject
    val contents = request["contents"]!!.jsonArray
    val firstToolParameters = request["tools"]!!
      .jsonArray[0]
      .jsonObject["functionDeclarations"]!!
      .jsonArray[0]
      .jsonObject["parameters"]!!
      .jsonObject

    assertEquals("oauth-token", credentials.accessToken)
    assertEquals("flovera-project", credentials.projectId)
    assertEquals("managed-project", credentials.managedProjectId)
    assertEquals("flovera-project", root["project"]!!.jsonPrimitive.content)
    assertEquals("gemini-3-flash-preview", root["model"]!!.jsonPrimitive.content)
    assertEquals("prompt-1", root["user_prompt_id"]!!.jsonPrimitive.content)
    assertEquals("You are Flovera.", request["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    assertEquals("user", contents[0].jsonObject["role"]!!.jsonPrimitive.content)
    assertEquals("model", contents[1].jsonObject["role"]!!.jsonPrimitive.content)
    assertEquals("skip_thought_signature_validator", contents[1].jsonObject["parts"]!!.jsonArray[0].jsonObject["thoughtSignature"]!!.jsonPrimitive.content)
    assertEquals("read_file", contents[2].jsonObject["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    assertFalse(firstToolParameters.containsKey("additionalProperties"))
    assertFalse(firstToolParameters["properties"]!!.jsonObject["mode"]!!.jsonObject.containsKey("enum"))
    assertEquals("ANY", request["toolConfig"]!!.jsonObject["functionCallingConfig"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
    assertEquals(64, request["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.content.toInt())
    assertEquals("high", request["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
  }

  @Test
  fun googleCloudCodeAssistResponseTranslationMatchesOpenAIShape() {
    val translated = translateGoogleCloudCodeAssistResponseToOpenAIJson(
      codeAssistResponseJson = """
        {
          "response": {
            "candidates": [
              {
                "finishReason": "STOP",
                "content": {
                  "parts": [
                    {"thought": true, "text": "reasoning"},
                    {"text": "hello"},
                    {"functionCall": {"name": "write_file", "args": {"path": "README.md"}}}
                  ]
                }
              }
            ],
            "usageMetadata": {
              "promptTokenCount": 10,
              "candidatesTokenCount": 5,
              "totalTokenCount": 15,
              "cachedContentTokenCount": 3
            }
          }
        }
      """.trimIndent(),
      model = "gemini-3-flash-preview",
    )
    val root = Json.parseToJsonElement(translated).jsonObject
    val choice = root["choices"]!!.jsonArray[0].jsonObject
    val message = choice["message"]!!.jsonObject

    assertEquals("chat.completion", root["object"]!!.jsonPrimitive.content)
    assertEquals("gemini-3-flash-preview", root["model"]!!.jsonPrimitive.content)
    assertEquals("tool_calls", choice["finish_reason"]!!.jsonPrimitive.content)
    assertEquals("hello", message["content"]!!.jsonPrimitive.content)
    assertEquals("reasoning", message["reasoning_content"]!!.jsonPrimitive.content)
    assertEquals("write_file", message["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    assertEquals(3, root["usage"]!!.jsonObject["prompt_tokens_details"]!!.jsonObject["cached_tokens"]!!.jsonPrimitive.content.toInt())
  }

  @Test
  fun googleCloudCodeAssistStreamTranslationMatchesHermesChunks() {
    val first = translateGoogleCloudCodeAssistStreamEvent(
      codeAssistEventJson = """
        {
          "response": {
            "candidates": [
              {
                "content": {
                  "parts": [
                    {"thought": true, "text": "plan"},
                    {"text": "hello"},
                    {"functionCall": {"name": "read_file", "args": {"path": "README.md"}}}
                  ]
                }
              }
            ]
          }
        }
      """.trimIndent(),
      toolCallStartIndex = 0,
    )
    val second = translateGoogleCloudCodeAssistStreamEvent(
      codeAssistEventJson = """
        {
          "response": {
            "candidates": [
              {
                "finishReason": "STOP",
                "content": {
                  "parts": [
                    {"functionCall": {"name": "write_file", "args": {"path": "README.md"}}}
                  ]
                }
              }
            ]
          }
        }
      """.trimIndent(),
      toolCallStartIndex = first.nextToolCallIndex,
      anyPreviousToolCalls = first.hasToolCalls,
    )

    assertEquals("plan", first.chunks[0].reasoning)
    assertEquals("hello", first.chunks[1].content)
    assertEquals("read_file", first.chunks[2].toolCallName)
    assertEquals(0, first.chunks[2].toolCallIndex)
    assertEquals("{\"path\":\"README.md\"}", first.chunks[2].toolCallArguments)
    assertEquals(1, first.nextToolCallIndex)
    assertEquals("write_file", second.chunks[0].toolCallName)
    assertEquals(1, second.chunks[0].toolCallIndex)
    assertEquals("tool_calls", second.chunks[1].finishReason)
  }

  @Test
  fun googleCloudCodeAssistErrorMappingMatchesHermesDiagnostics() {
    val capacity = googleCloudCodeAssistErrorMessage(
      status = 429,
      bodyText = """
        {
          "error": {
            "status": "RESOURCE_EXHAUSTED",
            "message": "capacity",
            "details": [
              {
                "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                "reason": "MODEL_CAPACITY_EXHAUSTED",
                "metadata": {"model": "gemini-3-flash-preview"}
              },
              {
                "@type": "type.googleapis.com/google.rpc.RetryInfo",
                "retryDelay": "30s"
              }
            ]
          }
        }
      """.trimIndent(),
    )
    val notFound = googleCloudCodeAssistErrorMessage(
      status = 404,
      bodyText = """{"error":{"status":"NOT_FOUND","message":"retired model"}}""",
    )

    assertTrue(capacity.contains("Gemini capacity exhausted"))
    assertTrue(capacity.contains("gemini-3-flash-preview"))
    assertTrue(capacity.contains("30s"))
    assertTrue(notFound.contains("Code Assist 404"))
    assertTrue(notFound.contains("retired model"))
  }

  @Test
  fun googleCloudCodeAssistCredentialsSupportHermesRefreshPacking() {
    val access = GoogleCloudCodeAssistCredentials.from("access:ya29.access-token|project-a|managed-a")
    val refresh = GoogleCloudCodeAssistCredentials.from("refresh:1//refresh-token|project-b|managed-b")
    val inferredRefresh = GoogleCloudCodeAssistCredentials.from("1//refresh-token|project-c|managed-c")
    val refreshBody = googleOAuthRefreshFormBody("1//refresh-token")

    assertFalse(access.usesRefreshToken)
    assertEquals("ya29.access-token", access.accessToken)
    assertEquals("project-a", access.projectId)
    assertEquals("managed-a", access.managedProjectId)
    assertTrue(refresh.usesRefreshToken)
    assertEquals("1//refresh-token", refresh.refreshToken)
    assertEquals("project-b", refresh.projectId)
    assertEquals("managed-b", refresh.managedProjectId)
    assertTrue(inferredRefresh.usesRefreshToken)
    assertTrue(refreshBody.contains("grant_type=refresh_token"))
    assertTrue(refreshBody.contains("refresh_token=1%2F%2Frefresh-token"))
    assertTrue(refreshBody.contains("client_id=681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com"))
    assertTrue(refreshBody.contains("client_secret=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"))
  }

  @Test
  fun copilotReasoningHookMatchesHermesEffortMapping() {
    val profile = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("copilot"),
      AppSettings(provider = "copilot", model = "gpt-5-mini"),
    )
    val request = """{"model":"gpt-5-mini","messages":[]}"""

    val defaultReasoning = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(
        providerId = "copilot",
        modelId = "gpt-5-mini",
        supportsReasoning = true,
      ),
    )
    val xhigh = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(
        providerId = "copilot",
        modelId = "gpt-5-mini",
        supportsReasoning = true,
        reasoningConfig = providerReasoningConfigFromEffort("xhigh"),
      ),
    )
    val unsupported = applyFloveraOpenAIRequestProfileToJson(
      requestJson = """{"model":"claude-sonnet-4.6","messages":[]}""",
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(
        providerId = "copilot",
        modelId = "claude-sonnet-4.6",
        supportsReasoning = true,
      ),
    )
    val disabled = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(
        providerId = "copilot",
        modelId = "gpt-5-mini",
        supportsReasoning = true,
        reasoningConfig = providerReasoningConfigFromEffort("none"),
      ),
    )

    assertTrue(defaultReasoning.contains("\"reasoning\":{\"effort\":\"medium\"}"))
    assertTrue(xhigh.contains("\"reasoning\":{\"effort\":\"high\"}"))
    assertFalse(unsupported.contains("\"reasoning\""))
    assertFalse(disabled.contains("\"reasoning\""))
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
      ProviderTransport.FloveraAnthropicMessages,
      ModelProviderCatalog.requireProvider("anthropic").transport,
    )
    assertEquals(
      ProviderTransport.FloveraOpenAICompatibleChatCompletions,
      ModelProviderCatalog.requireProvider("moonshot").transport,
    )
    assertEquals(
      ProviderTransport.KoogGoogleGeminiNative,
      ModelProviderCatalog.requireProvider("gemini").transport,
    )
    assertEquals(
      ProviderTransport.KoogBedrockConverse,
      ModelProviderCatalog.requireProvider("bedrock").transport,
    )
    assertEquals(
      ProviderTransport.FloveraCodexResponses,
      ModelProviderCatalog.requireProvider("xai").transport,
    )
  }

  @Test
  fun geminiProfileMirrorsHermesNativeProviderMetadata() {
    val provider = ModelProviderCatalog.requireProvider("gemini")
    val profile = ModelProviderCatalog.runtimeProfileFor(
      provider,
      AppSettings(provider = "gemini", model = "gemini-3-flash-preview"),
    )
    val client = ModelProviderCatalog.createClient(provider, apiKey = "gemini-key", settings = AppSettings(provider = "gemini"))

    assertEquals("chat_completions", profile.apiMode.id)
    assertEquals(ProviderTransport.KoogGoogleGeminiNative, profile.transport)
    assertEquals(LLMProvider.Google, provider.llmProvider)
    assertEquals(LLMProvider.Google, client.llmProvider())
    assertEquals("https://generativelanguage.googleapis.com/v1beta", profile.baseUrl)
    assertEquals("api_key", profile.authType.id)
    assertEquals("gemini-3-flash-preview", profile.defaultAuxModel)
    assertEquals(1_048_576, ModelProviderCatalog.contextFor(AppSettings(provider = "gemini", model = "gemini-3-flash-preview")).contextWindowTokens)
  }

  @Test
  fun bedrockProfileMirrorsHermesConverseProviderMetadata() {
    val provider = ModelProviderCatalog.requireProvider("bedrock")
    val profile = ModelProviderCatalog.runtimeProfileFor(
      provider,
      AppSettings(provider = "bedrock", model = "us.anthropic.claude-sonnet-4-6"),
    )
    val client = ModelProviderCatalog.createClient(provider, apiKey = "", settings = AppSettings(provider = "bedrock"))

    assertEquals("bedrock_converse", profile.apiMode.id)
    assertEquals(ProviderTransport.KoogBedrockConverse, profile.transport)
    assertEquals(LLMProvider.Bedrock, provider.llmProvider)
    assertEquals(LLMProvider.Bedrock, client.llmProvider())
    assertEquals("https://bedrock-runtime.us-east-1.amazonaws.com", profile.baseUrl)
    assertEquals("aws_sdk", profile.authType.id)
    assertFalse(profile.supportsHealthCheck)
    assertEquals("us.anthropic.claude-sonnet-4-6", profile.defaultAuxModel)
    assertEquals(
      200_000,
      ModelProviderCatalog.contextFor(AppSettings(provider = "bedrock", model = "us.anthropic.claude-sonnet-4-6")).contextWindowTokens,
    )
    assertEquals(
      300_000,
      ModelProviderCatalog.contextFor(AppSettings(provider = "bedrock", model = "us.amazon.nova-pro-v1:0")).contextWindowTokens,
    )
    assertEquals(
      128_000,
      ModelProviderCatalog.contextFor(AppSettings(provider = "bedrock", model = "us.meta.llama4-maverick-17b-instruct-v1:0")).contextWindowTokens,
    )
  }

  @Test
  fun xaiProfileMirrorsHermesCodexResponsesProviderMetadata() {
    val provider = ModelProviderCatalog.requireProvider("xai")
    val settings = AppSettings(
      provider = "xai",
      model = "grok-4.3",
      activeSessionId = "xai-session-123",
      reasoningEffort = "minimal",
    )
    val profile = ModelProviderCatalog.runtimeProfileFor(provider, settings)
    val client = ModelProviderCatalog.createClient(provider, apiKey = "xai-key", settings = settings)

    assertEquals("codex_responses", profile.apiMode.id)
    assertEquals(ProviderTransport.FloveraCodexResponses, profile.transport)
    assertEquals(LLMProvider.OpenAI, provider.llmProvider)
    assertEquals(LLMProvider.OpenAI, client.llmProvider())
    assertEquals("https://api.x.ai/v1", profile.baseUrl)
    assertEquals("https://api.x.ai/v1/models", profile.modelsUrl)
    assertEquals("responses", profile.responsesPath)
    assertEquals("models", profile.modelsPath)
    assertEquals("api_key", profile.authType.id)
    assertEquals("grok-4.3", profile.defaultAuxModel)
    assertEquals(1_000_000, ModelProviderCatalog.contextFor(settings).contextWindowTokens)
    assertEquals(
      "xai-session-123",
      providerRuntimeHeaders(profile, settings)["x-grok-conv-id"],
    )
  }

  @Test
  fun xaiCodexResponsesReasoningGateMatchesHermesAllowlist() {
    assertTrue(grokSupportsReasoningEffort("grok-3-mini"))
    assertTrue(grokSupportsReasoningEffort("x-ai/grok-4.3"))
    assertTrue(grokSupportsReasoningEffort("grok-4.20-multi-agent-0309"))
    assertFalse(grokSupportsReasoningEffort("grok-4.20-0309-reasoning"))
    assertFalse(grokSupportsReasoningEffort("grok-4"))

    assertEquals(
      ReasoningEffort.LOW,
      codexResponsesReasoningConfig("xai", "grok-4.3", "minimal")?.effort,
    )
    assertEquals(null, codexResponsesReasoningConfig("xai", "grok-4.20-0309-reasoning", "high"))
    assertEquals(null, codexResponsesReasoningConfig("xai", "grok-4.3", "none"))
    assertEquals(
      listOf(OpenAIInclude.REASONING_ENCRYPTED_CONTENT),
      codexResponsesInclude("openai-codex", codexResponsesReasoningConfig("openai-codex", "gpt-5.3-codex", "high")),
    )
    assertEquals(
      emptyList<OpenAIInclude>(),
      codexResponsesInclude("xai", codexResponsesReasoningConfig("xai", "grok-4.3", "high")),
    )
  }

  @Test
  fun nousPortalHookMatchesHermesTagsAndReasoningBody() {
    val profile = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("nous"),
      AppSettings(provider = "nous", model = "hermes-3-405b"),
    )
    val request = """{"model":"hermes-3-405b","messages":[]}"""

    val defaultReasoning = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(providerId = "nous", supportsReasoning = true),
    )
    val disabledReasoning = applyFloveraOpenAIRequestProfileToJson(
      requestJson = request,
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
      requestContext = ProviderRequestContext(
        providerId = "nous",
        supportsReasoning = true,
        reasoningConfig = providerReasoningConfigFromEffort("none"),
      ),
    )

    assertTrue(defaultReasoning.contains("\"tags\":[\"product=hermes-agent\",\"client=hermes-client-vunknown\"]"))
    assertTrue(defaultReasoning.contains("\"reasoning\":{\"enabled\":true,\"effort\":\"medium\"}"))
    assertTrue(disabledReasoning.contains("\"tags\":[\"product=hermes-agent\",\"client=hermes-client-vunknown\"]"))
    assertFalse(disabledReasoning.contains("\"reasoning\""))
  }

  @Test
  fun qwenPortalHookMatchesHermesMessageShapeExtras() {
    val profile = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("qwen-oauth"),
      AppSettings(provider = "qwen-oauth", model = "qwen3-coder-plus"),
    )

    val updated = applyFloveraOpenAIRequestProfileToJson(
      requestJson = """{"model":"qwen3-coder-plus","messages":[{"role":"system","content":"sys"},{"role":"user","content":"hello"}]}""",
      requestProfile = profile.requestProfile,
      modelContext = ModelContextSpec(),
    )

    assertTrue(updated.contains("\"vl_high_resolution_images\":true"))
    assertTrue(updated.contains("\"role\":\"system\",\"content\":[{\"type\":\"text\",\"text\":\"sys\",\"cache_control\":{\"type\":\"ephemeral\"}}]"))
    assertTrue(updated.contains("\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]"))
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
    assertEquals(ProviderTransport.FloveraAnthropicMessages, provider.transport)
    assertEquals("/v1/messages", provider.messagesPath)
    assertEquals("interleaved-thinking-2025-05-14,fine-grained-tool-streaming-2025-05-14", provider.defaultHeaders["anthropic-beta"])
    val headers = providerAnthropicRuntimeHeaders(ModelProviderCatalog.runtimeProfileFor(provider, AppSettings()), "anthropic-key")
    assertEquals("anthropic-key", headers["x-api-key"])
    assertEquals("2023-06-01", headers["anthropic-version"])
    assertFalse("Authorization" in headers)
  }

  @Test
  fun minimaxAnthropicProfilesMirrorHermesEndpointAndAuthMetadata() {
    val minimax = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("minimax"),
      AppSettings(provider = "minimax", model = "MiniMax-M2.7"),
    )
    val minimaxCn = ModelProviderCatalog.runtimeProfileFor(
      ModelProviderCatalog.requireProvider("minimax-cn"),
      AppSettings(provider = "minimax-cn", model = "MiniMax-M2.7"),
    )

    assertEquals("anthropic_messages", minimax.apiMode.id)
    assertEquals(ProviderTransport.FloveraAnthropicMessages, minimax.transport)
    assertEquals("https://api.minimax.io/anthropic", minimax.baseUrl)
    assertEquals("/v1/messages", minimax.messagesPath)
    assertEquals("/v1/models", minimax.modelsPath)
    assertEquals("bearer_token", minimax.authType.id)
    assertEquals("interleaved-thinking-2025-05-14", minimax.defaultHeaders["anthropic-beta"])
    assertEquals(131_072, ModelProviderCatalog.contextFor(AppSettings(provider = "minimax", model = "MiniMax-M2.7")).contextWindowTokens)
    assertEquals("https://api.minimaxi.com/anthropic", minimaxCn.baseUrl)
    assertEquals("bearer_token", minimaxCn.authType.id)
    val headers = providerAnthropicRuntimeHeaders(minimax, "minimax-key")
    assertEquals("Bearer minimax-key", headers["Authorization"])
    assertEquals("2023-06-01", headers["anthropic-version"])
    assertFalse("x-api-key" in headers)
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
  fun networkToolsDefaultToEnabled() {
    assertTrue(AppSettings().networkEnabled)
    assertTrue(AppSettings().webSearchEnabled)
    assertFalse(AppSettings().networkUserConfigured)
    assertFalse(AppSettings().webSearchUserConfigured)
    assertFalse(AppSettings().backgroundKeepAliveEnabled)
    assertTrue(AppSettings(networkEnabled = true).networkEnabled)
  }

  @Test
  fun settingsControllerMigratesOldNetworkDefaultsWithoutOverridingUserChoice() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val original = store.load()
    try {
      store.save(AppSettings(networkEnabled = false, networkUserConfigured = false))
      assertTrue(SettingsController(store).load().networkEnabled)

      store.save(AppSettings(networkEnabled = false, networkUserConfigured = true))
      assertFalse(SettingsController(store).load().networkEnabled)
    } finally {
      store.save(original)
    }
  }

  @Test
  fun fullAuthorityCanBeSelectedInCurrentSettings() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val original = store.load()
    try {
      val controller = SettingsController(store)

      val normalized = controller.setAuthorityMode(AppSettings(), "full")

      assertEquals("full", normalized.agentAuthorityMode)
    } finally {
      store.save(original)
    }
  }

  @Test
  fun fullAuthorityAutoAppliesSettingsProposalsWithSnapshotAndAudit() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val original = store.load()
    val workspaceId = "test-full-authority-auto-${System.currentTimeMillis()}"
    try {
      store.save(AppSettings(activeWorkspaceId = workspaceId, agentAuthorityMode = "full"))
      val controller = AgentController(context, settingsStore = store)
      val workspace = WorkspaceManager(context, workspaceId)
      workspace.writeFile(
        path = ".flovera/proposals/theme.json",
        content = """
          {
            "type": "settings",
            "title": "Switch theme",
            "reason": "Exercise Full Authority auto-apply.",
            "changes": {
              "themeMode": "light",
              "themeColor": "#C989B8",
              "maxAgentIterations": 44
            }
          }
        """.trimIndent(),
        createAutoSnapshot = false,
      )

      controller.refreshWorkspaceFiles()
      val state = controller.state.value
      val audit = workspace.readFile(".flovera/logs/full-authority.jsonl")

      assertEquals("full", state.settings.agentAuthorityMode)
      assertEquals("light", state.settings.themeMode)
      assertEquals("#C989B8", state.settings.themeColor)
      assertEquals(0, state.settings.maxAgentIterations)
      assertTrue(workspace.listSettingsProposals().isEmpty())
      assertTrue(audit.contains("\"action\":\"settings_proposal_auto_apply\""))
      assertTrue(audit.contains("\"targetPath\":\".flovera/proposals/theme.json\""))
      assertTrue(workspace.listSnapshots().any { it.reason == "full_authority_settings" })
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
          backgroundKeepAliveEnabled = true,
          language = "zh",
          maxAgentIterations = 120,
          agentAuthorityMode = "full",
          deepSeekThinkingEffort = "max",
          reasoningEffort = "XHIGH",
          customOpenAIBaseUrl = "https://llm.example.com/",
          customOpenAIChatCompletionsPath = "v1/chat/completions",
          customOpenAICompatibilityMode = "ollama",
          provider = "openai",
          model = "gpt-4.1",
          openRouterProviderPreferences = JsonObject(mapOf("sort" to JsonPrimitive("throughput"))),
          openRouterMinCodingScore = 1.2,
          modelContextWindowTokens = 512_000,
          modelCompressionThresholdPercent = 75,
        ),
      )

      assertEquals("light", updated.themeMode)
      assertEquals("#C989B8", updated.themeColor)
      assertTrue(updated.networkEnabled)
      assertTrue(updated.networkUserConfigured)
      assertTrue(updated.webSearchEnabled)
      assertTrue(updated.webSearchUserConfigured)
      assertTrue(updated.backgroundKeepAliveEnabled)
      assertEquals("zh", updated.language)
      assertEquals("deepseek", updated.provider)
      assertEquals(ModelProviderCatalog.defaultProvider.defaultModel, updated.model)
      assertEquals(0, updated.maxAgentIterations)
      assertEquals("full", updated.agentAuthorityMode)
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
      assertTrue(updated.webSearchUserConfigured)
      assertEquals("brave-key", updated.braveSearchApiKey)
      assertEquals("brave-key", store.load().braveSearchApiKey)
    } finally {
      store.save(original)
    }
  }

  @Test
  fun settingsControllerPersistsBackgroundKeepAliveSetting() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val original = store.load()
    try {
      val controller = SettingsController(store)

      val enabled = controller.setBackgroundKeepAlive(AppSettings(), enabled = true)
      val disabled = controller.setBackgroundKeepAlive(enabled, enabled = false)

      assertTrue(enabled.backgroundKeepAliveEnabled)
      assertFalse(disabled.backgroundKeepAliveEnabled)
      assertFalse(store.load().backgroundKeepAliveEnabled)
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
  fun settingsControllerPersistsWorkspaceSecrets() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = SettingsStore(context)
    val original = store.load()
    try {
      val controller = SettingsController(store)
      val saved = controller.saveWorkspaceSecret(
        settings = AppSettings(),
        originalName = "",
        name = " Amap ",
        label = "",
        description = "",
        value = " secret-value ",
        agentAllowed = true,
      )

      assertEquals("FLOVERA_SECRET_1", saved.workspaceSecrets.single().name)
      assertEquals("Amap", saved.workspaceSecrets.single().label)
      assertEquals("", saved.workspaceSecrets.single().description)
      assertEquals("secret-value", saved.workspaceSecrets.single().value)
      assertEquals(mapOf("FLOVERA_SECRET_1" to "secret-value"), saved.agentAllowedSecretEnvironment())

      val disabled = controller.setWorkspaceSecretAgentAllowed(saved, "FLOVERA_SECRET_1", false)
      assertFalse(disabled.workspaceSecrets.single().agentAllowed)
      assertTrue(disabled.agentAllowedSecretEnvironment().isEmpty())

      val renamed = controller.saveWorkspaceSecret(
        settings = disabled,
        originalName = "FLOVERA_SECRET_1",
        name = "Zhipu",
        label = "",
        description = "",
        value = "zhipu-secret",
        agentAllowed = true,
      )
      assertEquals("FLOVERA_SECRET_1", renamed.workspaceSecrets.single().name)
      assertEquals("Zhipu", renamed.workspaceSecrets.single().label)
      assertEquals("zhipu-secret", store.load().workspaceSecrets.single().value)

      val deleted = controller.deleteWorkspaceSecret(renamed, "FLOVERA_SECRET_1")
      assertTrue(deleted.workspaceSecrets.isEmpty())
      assertTrue(store.load().workspaceSecrets.isEmpty())
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
      assertEquals(null, openAiDraft)

      val saved = controller.saveModelSettings(
        normalized,
        ModelSettingsDraft(providerId = "openai", model = "  ", apiKey = " openai-key "),
      )
      assertEquals("deepseek", saved.provider)
      assertEquals(ModelProviderCatalog.defaultProvider.defaultModel, saved.model)
      assertEquals("openai-key", saved.apiKeyFor("deepseek"))

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
      assertEquals("deepseek", customSaved.provider)
      assertEquals(ModelProviderCatalog.defaultProvider.defaultModel, customSaved.model)
      assertEquals("custom-key", customSaved.apiKeyFor("deepseek"))
      assertEquals("https://llm.example.com/v1", customSaved.customOpenAIProvider.baseUrl)
      assertEquals("/chat/completions", customSaved.customOpenAIProvider.chatCompletionsPath)
      assertEquals("ollama", customSaved.customOpenAIProvider.compatibilityMode)

      val aliasSaved = controller.saveModelSettings(
        customSaved,
        ModelSettingsDraft(providerId = "claude", model = "", apiKey = " anthropic-key "),
      )
      assertEquals("deepseek", aliasSaved.provider)
      assertEquals(ModelProviderCatalog.defaultProvider.defaultModel, aliasSaved.model)
      assertEquals("anthropic-key", aliasSaved.apiKeyFor("deepseek"))
    } finally {
      store.save(original)
    }
  }

  @Test
  fun settingsControllerPersistsFullSettingsBatchAsOneConsistentState() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "settings-batch-${System.currentTimeMillis()}").apply {
      deleteRecursively()
      mkdirs()
    }
    val store = SettingsStore(context, File(root, "settings.json"))
    val controller = SettingsController(store)

    val saved = controller.saveModelSettingsBatch(
      settings = AppSettings(),
      draft = ModelSettingsDraft(
        providerId = "deepseek",
        model = " deepseek-chat ",
        apiKey = " batch-key ",
      ),
      language = "zh",
      themeMode = "dark",
      themeColor = "#c989b8",
      authorityMode = "assisted",
      deepSeekThinkingEffort = "low",
      networkEnabled = false,
      webSearchEnabled = false,
      braveSearchApiKey = " brave-key ",
      backgroundKeepAliveEnabled = true,
      workspaceMemoryEnabled = false,
      inputBarVisible = false,
    )

    assertEquals("deepseek-chat", saved.model)
    assertEquals("batch-key", saved.apiKeyFor("deepseek"))
    assertEquals("zh", saved.language)
    assertEquals("dark", saved.themeMode)
    assertEquals("#C989B8", saved.themeColor)
    assertEquals("assisted", saved.agentAuthorityMode)
    assertEquals("low", saved.deepSeekThinkingEffort)
    assertFalse(saved.networkEnabled)
    assertFalse(saved.webSearchEnabled)
    assertEquals("brave-key", saved.braveSearchApiKey)
    assertTrue(saved.backgroundKeepAliveEnabled)
    assertFalse(saved.workspaceMemoryEnabled)
    assertFalse(saved.inputBarVisible)
    assertEquals(saved, store.load())
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

  private data class ProviderEquivalenceFixture(
    val providerId: String,
    val tier: String,
    val authType: String,
    val apiMode: String,
    val transport: String,
    val baseUrl: String,
    val requestHookIds: List<String> = emptyList(),
  )

  private data class RouteFixture(
    val providerId: String,
    val model: String,
    val apiMode: String,
    val transport: String,
    val requestPath: String,
  )

  private data class OpenAICompatibleRequestProbe(
    val baseUrl: String,
    val requestPath: String,
    val headers: Map<String, String>,
    val body: JsonObject,
  )

  private fun openAICompatibleRequestProbe(settings: AppSettings): OpenAICompatibleRequestProbe {
    val provider = ModelProviderCatalog.requireProvider(settings.provider)
    val profile = ModelProviderCatalog.runtimeProfileFor(provider, settings)
    val modelContext = ModelProviderCatalog.contextFor(settings)
    val model = provider.createModel(settings.model, modelContext)
    val requestContext = ProviderRequestContext(
      providerId = profile.providerId,
      supportsReasoning = modelContext.supportsReasoning,
      reasoningConfig = providerReasoningConfigFromEffort(settings.reasoningEffort),
      openRouterProviderPreferences = settings.openRouterProvider.providerPreferences,
      openRouterMinCodingScore = settings.openRouterProvider.minCodingScore,
    )
    val client = TestOpenAICompatibleClient(
      requestProfile = profile.requestProfile,
      modelContext = modelContext,
      requestContext = requestContext,
    )
    val body = client.serializeProviderChatRequest(
      messages = listOf(OpenAIMessage.User(Content.Text("hello"))),
      model = model,
      tools = null,
      toolChoice = null,
      params = LLMParams(),
      stream = false,
    )

    return OpenAICompatibleRequestProbe(
      baseUrl = profile.baseUrl,
      requestPath = profile.chatCompletionsPath,
      headers = providerRuntimeHeaders(profile, settings),
      body = Json.parseToJsonElement(body).jsonObject,
    )
  }

  private class TestOpenAICompatibleClient(
    requestProfile: ProviderRequestProfile,
    modelContext: ModelContextSpec,
    requestContext: ProviderRequestContext,
  ) : FloveraOpenAICompatibleLLMClient(
    apiKey = "test-key",
    requestProfile = requestProfile,
    modelContext = modelContext,
    requestContext = requestContext,
  ) {
    public override fun serializeProviderChatRequest(
      messages: List<OpenAIMessage>,
      model: LLModel,
      tools: List<OpenAITool>?,
      toolChoice: OpenAIToolChoice?,
      params: LLMParams,
      stream: Boolean,
    ): String {
      return super.serializeProviderChatRequest(messages, model, tools, toolChoice, params, stream)
    }
  }

  private companion object {
    val providerEquivalenceTiers = setOf(
      "ordinary-api",
      "ordinary-api-local",
      "ordinary-api-user-endpoint",
      "ordinary-anthropic-api",
      "oauth-api",
      "optional-api",
      "optional-oauth-api",
      "native-api",
      "native-sdk-api",
      "unsupported-boundary",
    )
  }
}
