package com.flovera.app.koog

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.http.client.ktor.fromKtorClient
import com.flovera.app.config.AppSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import kotlinx.serialization.json.Json

enum class ProviderTransport(val id: String) {
  FloveraDeepSeekChatCompletions("flovera_deepseek_chat_completions"),
  FloveraOpenAICompatibleChatCompletions("flovera_openai_compatible_chat_completions"),
  KoogGoogleGeminiNative("koog_google_gemini_native"),
  FloveraAnthropicMessages("flovera_anthropic_messages"),
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
      ProviderTransport.FloveraAnthropicMessages -> createAnthropicMessagesClient(
        runtimeProfile = runtimeProfile,
        apiKey = apiKey,
      )
      ProviderTransport.KoogGoogleGeminiNative -> GoogleLLMClient(
        apiKey = apiKey,
        settings = GoogleClientSettings(baseUrl = runtimeProfile.requireBaseUrl()),
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
        supportsReasoning = modelContext.supportsReasoning,
        reasoningConfig = providerReasoningConfigFromEffort(settings.reasoningEffort),
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

  private fun createAnthropicMessagesClient(
    runtimeProfile: ProviderRuntimeProfile,
    apiKey: String,
  ): LLMClient {
    val settings = AnthropicClientSettings(
      baseUrl = runtimeProfile.requireBaseUrl(),
      messagesPath = runtimeProfile.messagesPath,
      modelsPath = runtimeProfile.modelsPath,
    )
    return AnthropicLLMClient(
      settings = settings,
      httpClient = KoogHttpClient.fromKtorClient(
        clientName = "FloveraAnthropicMessagesClient",
        logger = logger,
        baseClient = HttpClient(),
        baseUrl = runtimeProfile.requireBaseUrl(),
        requestTimeoutMillis = 900_000,
        connectTimeoutMillis = 10_000,
        socketTimeoutMillis = 900_000,
        json = anthropicJson,
        headers = providerAnthropicRuntimeHeaders(runtimeProfile, apiKey),
      ),
    )
  }

  private val logger = KotlinLogging.logger { }
  private val anthropicJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }
}

fun providerAnthropicRuntimeHeaders(
  runtimeProfile: ProviderRuntimeProfile,
  apiKey: String,
): Map<String, String> {
  val headers = runtimeProfile.defaultHeaders.toMutableMap()
  when (runtimeProfile.authType) {
    ProviderAuthType.BearerToken -> headers["Authorization"] = "Bearer $apiKey"
    else -> headers["x-api-key"] = apiKey
  }
  headers["anthropic-version"] = "2023-06-01"
  return headers
}
