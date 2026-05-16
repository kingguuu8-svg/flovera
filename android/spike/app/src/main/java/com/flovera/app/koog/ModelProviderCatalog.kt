package com.flovera.app.koog

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.flovera.app.config.AppSettings
import com.flovera.app.config.ModelContextOverride

data class ModelProviderSpec(
  val id: String,
  val label: String,
  val apiKeyLabel: String,
  val defaultModel: String,
  val suggestedModels: List<String>,
  val llmProvider: LLMProvider,
  val createClient: (String) -> LLMClient,
  val apiMode: ProviderApiMode = ProviderApiMode.ChatCompletions,
  val aliases: Set<String> = emptySet(),
  val baseUrl: String = "",
  val modelsUrl: String = "",
  val chatCompletionsPath: String = "/v1/chat/completions",
  val authType: ProviderAuthType = ProviderAuthType.ApiKey,
  val supportsHealthCheck: Boolean = true,
  val defaultHeaders: Map<String, String> = emptyMap(),
  val defaultMaxTokens: Int? = null,
  val defaultAuxModel: String = "",
  val requestProfile: ProviderRequestProfile = ProviderRequestProfile(),
  val modelContexts: Map<String, ModelContextSpec> = emptyMap(),
  val defaultContext: ModelContextSpec = ModelContextSpec(),
) {
  fun contextFor(modelId: String): ModelContextSpec {
    return modelContexts[modelId.ifBlank { defaultModel }] ?: defaultContext
  }

  fun createModel(modelId: String, contextSpec: ModelContextSpec? = null): LLModel {
    val model = modelId.ifBlank { defaultModel }
    val context = contextSpec ?: contextFor(model)
    return LLModel(
      provider = llmProvider,
      id = model,
      capabilities = agentModelCapabilities,
      contextLength = (context.contextWindowTokens ?: 200_000).toLong(),
      maxOutputTokens = 32_000,
    )
  }
}

data class ProviderRuntimeProfile(
  val providerId: String,
  val label: String,
  val apiMode: ProviderApiMode,
  val llmProvider: LLMProvider,
  val baseUrl: String,
  val modelsUrl: String,
  val chatCompletionsPath: String,
  val authType: ProviderAuthType,
  val supportsHealthCheck: Boolean,
  val defaultHeaders: Map<String, String>,
  val defaultMaxTokens: Int?,
  val defaultAuxModel: String,
  val requestProfile: ProviderRequestProfile,
) {
  fun requireBaseUrl(): String {
    return baseUrl.takeIf { it.isNotBlank() } ?: error("Provider profile $providerId has no base URL configured.")
  }
}

data class ProviderRequestProfile(
  val compatibilityMode: String = "generic",
  val injectOllamaNumCtx: Boolean = false,
)

enum class ProviderApiMode(val id: String) {
  ChatCompletions("chat_completions"),
  AnthropicMessages("anthropic_messages"),
}

enum class ProviderAuthType(val id: String) {
  ApiKey("api_key"),
  OAuthExternal("oauth_external"),
}

data class ModelContextSpec(
  val contextWindowTokens: Int? = null,
  val source: String = "unknown",
  val usageSource: String = "estimate",
  val compressionThresholdPercent: Int? = null,
)

object ModelProviderCatalog {
  val providers = listOf(
    ModelProviderSpec(
      id = "deepseek",
      label = "DeepSeek",
      apiKeyLabel = "DeepSeek API key",
      defaultModel = "deepseek-v4-pro",
      suggestedModels = listOf("deepseek-v4-pro", "deepseek-v4-flash", "deepseek-chat", "deepseek-reasoner"),
      llmProvider = LLMProvider.DeepSeek,
      createClient = ::FloveraDeepSeekLLMClient,
      baseUrl = "https://api.deepseek.com",
      defaultMaxTokens = 32_000,
      modelContexts = listOf(
        "deepseek-v4-pro",
        "deepseek-v4-flash",
        "deepseek-chat",
        "deepseek-reasoner",
      ).associateWith {
        ModelContextSpec(
          contextWindowTokens = 1_000_000,
          source = "deepseek_catalog",
          usageSource = "estimate",
          compressionThresholdPercent = 82,
        )
      },
    ),
    ModelProviderSpec(
      id = "openai",
      label = "OpenAI",
      apiKeyLabel = "OpenAI API key",
      defaultModel = "gpt-4.1",
      suggestedModels = listOf("gpt-4.1", "gpt-4.1-mini", "gpt-4o-mini"),
      llmProvider = LLMProvider.OpenAI,
      createClient = { apiKey -> FloveraOpenAICompatibleLLMClient(apiKey) },
      aliases = setOf("gpt", "openai-compatible"),
      baseUrl = "https://api.openai.com/v1",
      modelsUrl = "https://api.openai.com/v1/models",
    ),
    ModelProviderSpec(
      id = "custom-openai",
      label = "Custom OpenAI-compatible",
      apiKeyLabel = "Custom provider API key",
      defaultModel = "custom-model",
      suggestedModels = listOf("custom-model", "gpt-oss-120b", "qwen3-coder", "deepseek-chat"),
      llmProvider = LLMProvider.OpenAI,
      createClient = { apiKey -> FloveraOpenAICompatibleLLMClient(apiKey) },
      aliases = setOf("custom", "ollama", "local", "vllm", "llamacpp", "openai-compatible-custom"),
      supportsHealthCheck = false,
    ),
    ModelProviderSpec(
      id = "openrouter",
      label = "OpenRouter",
      apiKeyLabel = "OpenRouter API key",
      defaultModel = "openai/gpt-4.1",
      suggestedModels = listOf("openai/gpt-4.1", "anthropic/claude-sonnet-4.5", "deepseek/deepseek-chat"),
      llmProvider = LLMProvider.OpenRouter,
      createClient = ::OpenRouterLLMClient,
      aliases = setOf("router"),
      baseUrl = "https://openrouter.ai/api/v1",
      modelsUrl = "https://openrouter.ai/api/v1/models",
    ),
    ModelProviderSpec(
      id = "anthropic",
      label = "Anthropic",
      apiKeyLabel = "Anthropic API key",
      defaultModel = "claude-sonnet-4-5",
      suggestedModels = listOf("claude-sonnet-4-5", "claude-3-5-haiku-latest"),
      llmProvider = LLMProvider.Anthropic,
      createClient = ::AnthropicLLMClient,
      apiMode = ProviderApiMode.AnthropicMessages,
      aliases = setOf("claude", "claude-code"),
      baseUrl = "https://api.anthropic.com",
      modelsUrl = "https://api.anthropic.com/v1/models",
    ),
  )

  val defaultProvider: ModelProviderSpec = providers.first()

  val supportedApiModes: List<String> = providers
    .map { it.apiMode.id }
    .distinct()

  fun findProvider(providerId: String): ModelProviderSpec? {
    val normalized = providerId.trim().lowercase()
    if (normalized.isBlank()) return null
    return providers.firstOrNull { provider ->
      provider.id == normalized || normalized in provider.aliases
    }
  }

  fun requireProvider(providerId: String): ModelProviderSpec {
    return findProvider(providerId) ?: error("Unsupported model provider: $providerId")
  }

  fun runtimeProfileFor(settings: AppSettings): ProviderRuntimeProfile {
    val provider = findProvider(settings.provider) ?: defaultProvider
    return runtimeProfileFor(provider, settings)
  }

  fun runtimeProfileFor(provider: ModelProviderSpec, settings: AppSettings): ProviderRuntimeProfile {
    val customProfile = settings.customOpenAIProvider.takeIf { provider.id == "custom-openai" }
    val baseUrl = customProfile?.baseUrl?.takeIf { it.isNotBlank() } ?: provider.baseUrl
    val chatCompletionsPath = customProfile?.chatCompletionsPath?.takeIf { it.isNotBlank() }
      ?: provider.chatCompletionsPath
    val modelsUrl = provider.modelsUrl.ifBlank { defaultModelsUrl(baseUrl) }
    val compatibilityMode = customProfile?.compatibilityMode ?: provider.requestProfile.compatibilityMode
    val requestProfile = provider.requestProfile.copy(
      compatibilityMode = compatibilityMode,
      injectOllamaNumCtx = compatibilityMode == "ollama",
    )
    return ProviderRuntimeProfile(
      providerId = provider.id,
      label = provider.label,
      apiMode = provider.apiMode,
      llmProvider = provider.llmProvider,
      baseUrl = baseUrl,
      modelsUrl = modelsUrl,
      chatCompletionsPath = chatCompletionsPath,
      authType = provider.authType,
      supportsHealthCheck = provider.supportsHealthCheck,
      defaultHeaders = provider.defaultHeaders,
      defaultMaxTokens = provider.defaultMaxTokens,
      defaultAuxModel = provider.defaultAuxModel,
      requestProfile = requestProfile,
    )
  }

  private fun defaultModelsUrl(baseUrl: String): String {
    return baseUrl.takeIf { it.isNotBlank() }?.trimEnd('/')?.plus("/models").orEmpty()
  }

  fun createClient(provider: ModelProviderSpec, apiKey: String, settings: AppSettings): LLMClient {
    val runtimeProfile = runtimeProfileFor(provider, settings)
    return when (provider.id) {
      "deepseek" -> FloveraDeepSeekLLMClient(
        apiKey = apiKey,
        requestSettings = FloveraDeepSeekRequestSettings.from(settings),
      )
      "custom-openai" -> FloveraOpenAICompatibleLLMClient(
        apiKey = apiKey,
        settings = OpenAIClientSettings(
          baseUrl = runtimeProfile.requireBaseUrl(),
          chatCompletionsPath = runtimeProfile.chatCompletionsPath,
        ),
        requestProfile = runtimeProfile.requestProfile,
        modelContext = contextFor(settings),
      )
      else -> provider.createClient(apiKey)
    }
  }

  fun contextFor(settings: AppSettings): ModelContextSpec {
    val provider = findProvider(settings.provider) ?: defaultProvider
    val model = settings.model.ifBlank { provider.defaultModel }
    val base = provider.contextFor(model)
    return base.withOverride(settings.modelContextOverrideFor(provider.id, model))
  }

  private fun ModelContextSpec.withOverride(override: ModelContextOverride?): ModelContextSpec {
    if (override == null) return this
    val window = override.contextWindowTokens?.takeIf { it > 0 }
    val threshold = override.compressionThresholdPercent?.coerceIn(1, 100)
    if (window == null && threshold == null) return this
    return copy(
      contextWindowTokens = window ?: contextWindowTokens,
      source = "user_override",
      usageSource = "estimate",
      compressionThresholdPercent = threshold ?: compressionThresholdPercent,
    )
  }
}

private val agentModelCapabilities = listOf(
  LLMCapability.Completion,
  LLMCapability.Temperature,
  LLMCapability.Tools,
  LLMCapability.ToolChoice,
  LLMCapability.Schema.JSON.Basic,
  LLMCapability.Schema.JSON.Standard,
)
