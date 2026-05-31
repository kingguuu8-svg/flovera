package com.flovera.app.koog

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.bedrock.BedrockAPIMethod
import ai.koog.prompt.executor.clients.bedrock.BedrockClientSettings
import ai.koog.prompt.executor.clients.bedrock.BedrockLLMClient
import ai.koog.prompt.executor.clients.bedrock.BedrockModelFamilies
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.http.client.ktor.fromKtorClient
import aws.sdk.kotlin.runtime.auth.credentials.DefaultChainCredentialsProvider
import com.flovera.app.config.AppSettings
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

enum class ProviderTransport(val id: String) {
  FloveraDeepSeekChatCompletions("flovera_deepseek_chat_completions"),
  FloveraOpenAICompatibleChatCompletions("flovera_openai_compatible_chat_completions"),
  FloveraCodexResponses("flovera_codex_responses"),
  KoogGoogleGeminiNative("koog_google_gemini_native"),
  KoogBedrockConverse("koog_bedrock_converse"),
  FloveraAnthropicMessages("flovera_anthropic_messages"),
  KoogAnthropicMessages("koog_anthropic_messages"),
  FloveraGoogleCloudCodeAssist("flovera_google_cloud_code_assist"),
  FloveraExternalProcess("flovera_external_process"),
}

fun providerRuntimeHeaders(
  runtimeProfile: ProviderRuntimeProfile,
  settings: AppSettings,
): Map<String, String> {
  val headers = runtimeProfile.defaultHeaders.toMutableMap()
  val sessionId = settings.activeSessionId?.takeIf { it.isNotBlank() }
  if (sessionId != null && runtimeProfile.providerId == "xai") {
    headers["x-grok-conv-id"] = sessionId
  }
  if (sessionId != null && runtimeProfile.providerId == "openai-codex") {
    headers["session_id"] = sessionId
    headers["x-client-request-id"] = sessionId
  }
  if (runtimeProfile.providerId == "openrouter" && sessionId != null && isOpenRouterGrokModel(settings.model)) {
    headers["x-grok-conv-id"] = sessionId
  }
  return headers
}

private fun isOpenRouterGrokModel(modelId: String): Boolean {
  val normalized = modelId.trim().lowercase()
  return normalized.startsWith("x-ai/grok-") || normalized.startsWith("xai/grok-")
}

data class ProviderKtorRoute(
  val baseUrl: String,
  val chatCompletionsPath: String,
  val responsesPath: String,
  val messagesPath: String,
  val modelsPath: String,
)

fun providerKtorRoute(runtimeProfile: ProviderRuntimeProfile): ProviderKtorRoute {
  return providerKtorRouteCandidates(runtimeProfile).first()
}

fun providerKtorRouteCandidates(runtimeProfile: ProviderRuntimeProfile): List<ProviderKtorRoute> {
  val baseUrl = runtimeProfile.requireBaseUrl()
  val uri = runCatching { URI(baseUrl) }.getOrNull()
  val origin = if (uri?.scheme != null && uri.rawAuthority != null) {
    "${uri.scheme}://${uri.rawAuthority}"
  } else {
    baseUrl.trimEnd('/')
  }
  val basePath = uri?.rawPath.orEmpty().trim('/')
  return routePathCandidates(basePath, runtimeProfile.chatCompletionsPath).map { chatPath ->
    ProviderKtorRoute(
      baseUrl = origin,
      chatCompletionsPath = chatPath,
      responsesPath = combineProviderPath(basePath, runtimeProfile.responsesPath),
      messagesPath = combineProviderPath(basePath, runtimeProfile.messagesPath),
      modelsPath = combineProviderPath(basePath, runtimeProfile.modelsPath),
    )
  }.distinct()
}

private fun routePathCandidates(basePath: String, requestPath: String): List<String> {
  val base = basePath.trim().trim('/')
  val request = requestPath.trim().trimStart('/')
  val candidates = mutableListOf<String>()
  fun add(path: String) {
    if (path.isNotBlank() && path !in candidates) candidates += path
  }

  add(combineProviderPath(base, request))

  val requestWithoutVersion = request.removeLeadingVersionSegment()
  val requestStartsWithVersion = requestWithoutVersion != request
  val baseHasVersion = base.split('/').any { it.isVersionSegment() }

  if (requestStartsWithVersion) {
    add(combineProviderPath(base, requestWithoutVersion))
  } else if (!baseHasVersion) {
    add(combineProviderPath(base, "v1/$request"))
  }

  if (base.isNotBlank()) {
    add(request)
    if (requestStartsWithVersion) {
      add(requestWithoutVersion)
    } else {
      add("v1/$request")
    }
  }

  return candidates
}

private fun combineProviderPath(basePath: String, requestPath: String): String {
  val request = requestPath.trim().trimStart('/')
  val base = basePath.trim().trim('/')
  if (base.isBlank()) return request
  if (request.isBlank()) return base
  val baseSegments = base.split('/').filter { it.isNotBlank() }
  val requestSegments = request.split('/').filter { it.isNotBlank() }
  val overlap = (minOf(baseSegments.size, requestSegments.size) downTo 1)
    .firstOrNull { size ->
      baseSegments.takeLast(size) == requestSegments.take(size)
    } ?: 0
  return (baseSegments + requestSegments.drop(overlap)).joinToString("/")
}

private fun String.removeLeadingVersionSegment(): String {
  val segments = split('/').filter { it.isNotBlank() }
  if (segments.firstOrNull()?.isVersionSegment() != true) return this
  return segments.drop(1).joinToString("/")
}

private fun String.isVersionSegment(): Boolean {
  return matches(Regex("v\\d+(?:\\.\\d+)?", RegexOption.IGNORE_CASE))
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
      ProviderTransport.FloveraCodexResponses -> createCodexResponsesClient(
        runtimeProfile = runtimeProfile,
        apiKey = apiKey,
        settings = settings,
      )
      ProviderTransport.FloveraAnthropicMessages -> createAnthropicMessagesClient(
        runtimeProfile = runtimeProfile,
        apiKey = apiKey,
      )
      ProviderTransport.KoogGoogleGeminiNative -> GoogleLLMClient(
        apiKey = apiKey,
        settings = GoogleClientSettings(baseUrl = runtimeProfile.requireBaseUrl()),
      )
      ProviderTransport.KoogBedrockConverse -> BedrockLLMClient(
        identityProvider = DefaultChainCredentialsProvider(),
        settings = BedrockClientSettings(
          region = "us-east-1",
          endpointUrl = runtimeProfile.requireBaseUrl(),
          apiMethod = BedrockAPIMethod.Converse,
          fallbackModelFamily = BedrockModelFamilies.AnthropicClaude,
        ),
      )
      ProviderTransport.KoogAnthropicMessages -> AnthropicLLMClient(apiKey)
      ProviderTransport.FloveraGoogleCloudCodeAssist -> FloveraGoogleCloudCodeAssistLLMClient(
        rawApiKey = apiKey,
        runtimeProfile = runtimeProfile,
      )
      ProviderTransport.FloveraExternalProcess -> unsupportedProviderTransport(
        runtimeProfile = runtimeProfile,
        detail = "This Hermes provider is backed by an external process transport that is not yet available in Flovera Android.",
      )
    }
  }

  private fun unsupportedProviderTransport(
    runtimeProfile: ProviderRuntimeProfile,
    detail: String,
  ): LLMClient {
    throw UnsupportedOperationException(
      "Provider ${runtimeProfile.providerId} uses transport ${runtimeProfile.transport.id}. $detail",
    )
  }

  private fun createOpenAICompatibleClient(
    runtimeProfile: ProviderRuntimeProfile,
    apiKey: String,
    settings: AppSettings,
    modelContext: ModelContextSpec,
  ): LLMClient {
    val routeCandidates = providerKtorRouteCandidates(runtimeProfile)
    val clients = routeCandidates.map { route ->
      FloveraOpenAICompatibleLLMClient(
        apiKey = apiKey,
        settings = OpenAIClientSettings(
          baseUrl = route.baseUrl,
          chatCompletionsPath = route.chatCompletionsPath,
          modelsPath = route.modelsPath,
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
    return ProviderRouteFallbackLLMClient(
      clients = clients,
      routes = routeCandidates,
      providerIdentity = runtimeProfile.llmProvider,
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

  private fun createCodexResponsesClient(
    runtimeProfile: ProviderRuntimeProfile,
    apiKey: String,
    settings: AppSettings,
  ): LLMClient {
    val route = providerKtorRoute(runtimeProfile)
    return FloveraCodexResponsesLLMClient(
      apiKey = apiKey,
      settings = OpenAIClientSettings(
        baseUrl = route.baseUrl,
        chatCompletionsPath = route.chatCompletionsPath,
        responsesAPIPath = route.responsesPath,
        modelsPath = route.modelsPath,
      ),
      providerIdentity = runtimeProfile.llmProvider,
      requestSettings = FloveraCodexResponsesRequestSettings(
        providerId = runtimeProfile.providerId,
        sessionId = settings.activeSessionId,
        reasoningEffort = settings.reasoningEffort,
      ),
      baseClient = openAICompatibleBaseClient(providerRuntimeHeaders(runtimeProfile, settings)),
    )
  }

  private fun createAnthropicMessagesClient(
    runtimeProfile: ProviderRuntimeProfile,
    apiKey: String,
  ): LLMClient {
    val route = providerKtorRoute(runtimeProfile)
    val settings = AnthropicClientSettings(
      baseUrl = route.baseUrl,
      messagesPath = route.messagesPath,
      modelsPath = route.modelsPath,
    )
    return AnthropicLLMClient(
      settings = settings,
      httpClient = KoogHttpClient.fromKtorClient(
        clientName = "FloveraAnthropicMessagesClient",
        logger = logger,
        baseClient = HttpClient(),
        baseUrl = route.baseUrl,
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

private class ProviderRouteFallbackLLMClient(
  private val clients: List<LLMClient>,
  private val routes: List<ProviderKtorRoute>,
  private val providerIdentity: LLMProvider,
) : LLMClient() {
  override fun llmProvider(): LLMProvider = providerIdentity

  override suspend fun execute(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): List<Message.Response> {
    return retryRoute404 { client ->
      client.execute(prompt, model, tools)
    }
  }

  override suspend fun executeMultipleChoices(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): List<List<Message.Response>> {
    return retryRoute404 { client ->
      client.executeMultipleChoices(prompt, model, tools)
    }
  }

  override fun executeStreaming(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): Flow<StreamFrame> = flow {
    clients.forEachIndexed { index, client ->
      try {
        emitAll(client.executeStreaming(prompt, model, tools))
        return@flow
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        if (!isRouteNotFound(error) || index == clients.lastIndex) throw error
        logger.info { "Provider streaming route returned 404; retrying with fallback route ${routes[index + 1]}" }
      }
    }
  }

  override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
    return clients.first().moderate(prompt, model)
  }

  override suspend fun models(): List<LLModel> {
    return retryRoute404 { client ->
      client.models()
    }
  }

  override fun close() {
    clients.forEach { it.close() }
  }

  private suspend fun <T> retryRoute404(block: suspend (LLMClient) -> T): T {
    var lastError: Throwable? = null
    clients.forEachIndexed { index, client ->
      try {
        return block(client)
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        if (!isRouteNotFound(error) || index == clients.lastIndex) throw error
        lastError = error
        logger.info { "Provider route returned 404; retrying with fallback route ${routes[index + 1]}" }
      }
    }
    throw lastError ?: IllegalStateException("Provider route fallback had no clients.")
  }

  private companion object {
    val logger = KotlinLogging.logger {}
  }
}

private fun isRouteNotFound(error: Throwable): Boolean {
  var current: Throwable? = error
  while (current != null) {
    val message = current.message.orEmpty()
    if (
      message.contains("Status code: 404", ignoreCase = true) ||
      message.contains("status=404", ignoreCase = true) ||
      message.contains("Expected status code 200 but was 404", ignoreCase = true)
    ) {
      return true
    }
    current = current.cause
  }
  return false
}

fun providerAnthropicRuntimeHeaders(
  runtimeProfile: ProviderRuntimeProfile,
  apiKey: String,
): Map<String, String> {
  val headers = runtimeProfile.defaultHeaders.toMutableMap()
  when (runtimeProfile.authType) {
    ProviderAuthType.BearerToken,
    ProviderAuthType.OAuthExternal,
    ProviderAuthType.OAuthDeviceCode,
    ProviderAuthType.Copilot,
    -> headers["Authorization"] = "Bearer $apiKey"
    else -> headers["x-api-key"] = apiKey
  }
  headers["anthropic-version"] = "2023-06-01"
  return headers
}
