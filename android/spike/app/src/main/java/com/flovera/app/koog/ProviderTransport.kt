package com.flovera.app.koog

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import com.flovera.app.config.AppSettings

enum class ProviderTransport(val id: String) {
  FloveraDeepSeekChatCompletions("flovera_deepseek_chat_completions"),
  FloveraOpenAICompatibleChatCompletions("flovera_openai_compatible_chat_completions"),
  KoogOpenRouterChatCompletions("koog_openrouter_chat_completions"),
  KoogAnthropicMessages("koog_anthropic_messages"),
}

object ProviderTransportFactory {
  fun createClient(
    transport: ProviderTransport,
    runtimeProfile: ProviderRuntimeProfile,
    apiKey: String,
    settings: AppSettings,
    modelContext: ModelContextSpec,
  ): LLMClient {
    return when (transport) {
      ProviderTransport.FloveraDeepSeekChatCompletions -> FloveraDeepSeekLLMClient(
        apiKey = apiKey,
        requestSettings = FloveraDeepSeekRequestSettings.from(settings),
      )
      ProviderTransport.FloveraOpenAICompatibleChatCompletions -> createOpenAICompatibleClient(
        runtimeProfile = runtimeProfile,
        apiKey = apiKey,
        modelContext = modelContext,
      )
      ProviderTransport.KoogOpenRouterChatCompletions -> OpenRouterLLMClient(apiKey)
      ProviderTransport.KoogAnthropicMessages -> AnthropicLLMClient(apiKey)
    }
  }

  private fun createOpenAICompatibleClient(
    runtimeProfile: ProviderRuntimeProfile,
    apiKey: String,
    modelContext: ModelContextSpec,
  ): LLMClient {
    return FloveraOpenAICompatibleLLMClient(
      apiKey = apiKey,
      settings = OpenAIClientSettings(
        baseUrl = runtimeProfile.requireBaseUrl(),
        chatCompletionsPath = runtimeProfile.chatCompletionsPath,
      ),
      requestProfile = runtimeProfile.requestProfile,
      modelContext = modelContext,
    )
  }
}
