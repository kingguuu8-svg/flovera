package com.flovera.app.koog

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

data class ModelProviderSpec(
  val id: String,
  val label: String,
  val apiKeyLabel: String,
  val defaultModel: String,
  val suggestedModels: List<String>,
  val llmProvider: LLMProvider,
  val createClient: (String) -> LLMClient,
  val modelContexts: Map<String, ModelContextSpec> = emptyMap(),
  val defaultContext: ModelContextSpec = ModelContextSpec(),
) {
  fun contextFor(modelId: String): ModelContextSpec {
    return modelContexts[modelId.ifBlank { defaultModel }] ?: defaultContext
  }

  fun createModel(modelId: String): LLModel {
    val model = modelId.ifBlank { defaultModel }
    val context = contextFor(model)
    return LLModel(
      provider = llmProvider,
      id = model,
      capabilities = agentModelCapabilities,
      contextLength = (context.contextWindowTokens ?: 200_000).toLong(),
      maxOutputTokens = 32_000,
    )
  }
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
      createClient = ::OpenAILLMClient,
    ),
    ModelProviderSpec(
      id = "openrouter",
      label = "OpenRouter",
      apiKeyLabel = "OpenRouter API key",
      defaultModel = "openai/gpt-4.1",
      suggestedModels = listOf("openai/gpt-4.1", "anthropic/claude-sonnet-4.5", "deepseek/deepseek-chat"),
      llmProvider = LLMProvider.OpenRouter,
      createClient = ::OpenRouterLLMClient,
    ),
    ModelProviderSpec(
      id = "anthropic",
      label = "Anthropic",
      apiKeyLabel = "Anthropic API key",
      defaultModel = "claude-sonnet-4-5",
      suggestedModels = listOf("claude-sonnet-4-5", "claude-3-5-haiku-latest"),
      llmProvider = LLMProvider.Anthropic,
      createClient = ::AnthropicLLMClient,
    ),
  )

  val defaultProvider: ModelProviderSpec = providers.first()

  fun findProvider(providerId: String): ModelProviderSpec? = providers.firstOrNull { it.id == providerId }

  fun requireProvider(providerId: String): ModelProviderSpec {
    return findProvider(providerId) ?: error("Unsupported model provider: $providerId")
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
