package com.flovera.app.koog

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import com.flovera.app.config.AppSettings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header

enum class ProviderTransport(val id: String) {
  FloveraDeepSeekChatCompletions("flovera_deepseek_chat_completions"),
  FloveraOpenAICompatibleChatCompletions("flovera_openai_compatible_chat_completions"),
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
      providerIdentity = runtimeProfile.llmProvider,
      requestProfile = runtimeProfile.requestProfile,
      modelContext = modelContext,
      baseClient = openAICompatibleBaseClient(runtimeProfile.defaultHeaders),
    )
  }

  private fun openAICompatibleBaseClient(defaultHeaders: Map<String, String>): HttpClient {
    if (defaultHeaders.isEmpty()) return HttpClient()
    return HttpClient {
      defaultRequest {
        defaultHeaders.forEach { (name, value) ->
          header(name, value)
        }
      }
    }
  }
}
