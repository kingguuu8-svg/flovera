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

fun providerRuntimeHeaders(
  runtimeProfile: ProviderRuntimeProfile,
  settings: AppSettings,
): Map<String, String> {
  val headers = runtimeProfile.defaultHeaders.toMutableMap()
  val sessionId = settings.activeSessionId?.takeIf { it.isNotBlank() }
  if (runtimeProfile.providerId == "openrouter" && sessionId != null && isOpenRouterGrokModel(settings.model)) {
    headers["x-grok-conv-id"] = sessionId
  }
  return headers
}

private fun isOpenRouterGrokModel(modelId: String): Boolean {
  val normalized = modelId.trim().lowercase()
  return normalized.startsWith("x-ai/grok-") || normalized.startsWith("xai/grok-")
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
        settings = settings,
        modelContext = modelContext,
      )
      ProviderTransport.KoogAnthropicMessages -> AnthropicLLMClient(apiKey)
    }
  }

  private fun createOpenAICompatibleClient(
    runtimeProfile: ProviderRuntimeProfile,
    apiKey: String,
    settings: AppSettings,
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
      requestContext = ProviderRequestContext(
        providerId = runtimeProfile.providerId,
        openRouterProviderPreferences = settings.openRouterProvider.providerPreferences,
        openRouterMinCodingScore = settings.openRouterProvider.minCodingScore,
      ),
      baseClient = openAICompatibleBaseClient(providerRuntimeHeaders(runtimeProfile, settings)),
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
