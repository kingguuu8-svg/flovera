package com.flovera.app.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolChoice
import ai.koog.prompt.executor.clients.openai.models.OpenAIChatCompletionResponse
import ai.koog.prompt.executor.clients.openai.models.OpenAIChatCompletionStreamResponse
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.buildStreamFrameFlow
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.time.Clock

open class FloveraGoogleCloudCodeAssistLLMClient(
  rawApiKey: String,
  private val runtimeProfile: ProviderRuntimeProfile,
  settings: OpenAIClientSettings = OpenAIClientSettings(
    baseUrl = CODE_ASSIST_ENDPOINT,
    chatCompletionsPath = "/v1internal:generateContent",
  ),
  baseClient: HttpClient = googleCloudCodeAssistBaseClient(),
  clock: Clock = Clock.System,
  toolsConverter: OpenAICompatibleToolDescriptorSchemaGenerator = OpenAICompatibleToolDescriptorSchemaGenerator(),
) : OpenAILLMClient(
  apiKey = GoogleCloudCodeAssistCredentials.from(rawApiKey).initialOpenAIClientToken,
  settings = settings,
  baseClient = baseClient,
  clock = clock,
  toolsConverter = toolsConverter,
) {
  private val credentials = GoogleCloudCodeAssistCredentials.from(rawApiKey)
  private var cachedProjectId: String? = credentials.projectId.takeIf { it.isNotBlank() }
  private var lastModelId: String = runtimeProfile.defaultAuxModel.ifBlank { runtimeProfile.providerId }

  override fun llmProvider(): LLMProvider = LLMProvider.Google

  override fun serializeProviderChatRequest(
    messages: List<OpenAIMessage>,
    model: LLModel,
    tools: List<OpenAITool>?,
    toolChoice: OpenAIToolChoice?,
    params: LLMParams,
    stream: Boolean,
  ): String {
    lastModelId = model.id
    val accessToken = credentials.currentAccessToken()
    val openAiJson = super.serializeProviderChatRequest(messages, model, tools, toolChoice, params, stream = false)
    return buildGoogleCloudCodeAssistRequest(
      openAIRequestJson = openAiJson,
      projectId = ensureProjectId(model.id, accessToken),
    )
  }

  override fun decodeResponse(data: String): OpenAIChatCompletionResponse {
    return json.decodeFromString(
      translateGoogleCloudCodeAssistResponseToOpenAIJson(data, model = lastModelId),
    )
  }

  override fun decodeStreamingResponse(data: String): OpenAIChatCompletionStreamResponse {
    return super.decodeStreamingResponse(data)
  }

  override fun executeStreaming(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): Flow<StreamFrame> {
    lastModelId = model.id
    val request = buildRequestForPrompt(prompt, model, tools)
    return buildStreamFrameFlow {
      var toolCallIndex = 0
      var sawToolCall = false
      var emittedEnd = false
      streamGoogleCloudCodeAssistEvents(
        accessToken = request.accessToken,
        modelId = model.id,
        wrappedRequestJson = request.wrappedJson,
      ) { eventJson ->
        val translation = translateGoogleCloudCodeAssistStreamEvent(
          codeAssistEventJson = eventJson,
          toolCallStartIndex = toolCallIndex,
          anyPreviousToolCalls = sawToolCall,
        )
        toolCallIndex = translation.nextToolCallIndex
        if (translation.hasToolCalls) sawToolCall = true
        translation.chunks.forEach { chunk ->
          if (chunk.reasoning.isNotBlank()) {
            emitReasoningDelta(chunk.reasoning)
          }
          if (chunk.content.isNotBlank()) {
            emitTextDelta(chunk.content)
          }
          if (chunk.toolCallName.isNotBlank()) {
            emitToolCallDelta(
              id = chunk.toolCallId,
              name = chunk.toolCallName,
              args = chunk.toolCallArguments,
              index = chunk.toolCallIndex,
            )
          }
          if (chunk.finishReason != null) {
            emitEnd(chunk.finishReason)
            emittedEnd = true
          }
        }
      }
      if (!emittedEnd) {
        emitEnd(null)
      }
    }.flowOn(Dispatchers.IO)
  }

  override suspend fun execute(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): List<Message.Response> {
    lastModelId = model.id
    val request = buildRequestForPrompt(prompt, model, tools)
    val responseJson = postGoogleCloudCodeAssistGenerateContent(
      accessToken = request.accessToken,
      modelId = model.id,
      wrappedRequestJson = request.wrappedJson,
    )
    val decoded = decodeResponse(responseJson)
    return processProviderChatResponse(decoded).firstOrNull().orEmpty()
  }

  override suspend fun executeMultipleChoices(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): List<List<Message.Response>> {
    return listOf(execute(prompt, model, tools))
  }

  private fun buildRequestForPrompt(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): GoogleCloudCodeAssistPreparedRequest {
    val accessToken = credentials.currentAccessToken()
    val openAiJson = super.serializeProviderChatRequest(
      messages = convertPromptToMessages(prompt, model),
      model = model,
      tools = tools.map { it.toOpenAIChatTool() },
      toolChoice = prompt.params.toolChoice?.toOpenAIToolChoice(),
      params = prompt.params,
      stream = false,
    )
    return GoogleCloudCodeAssistPreparedRequest(
      accessToken = accessToken,
      wrappedJson = buildGoogleCloudCodeAssistRequest(
        openAIRequestJson = openAiJson,
        projectId = ensureProjectId(model.id, accessToken),
      ),
    )
  }

  private fun ensureProjectId(modelId: String, accessToken: String): String {
    cachedProjectId?.let { return it }
    val discovered = discoverProjectId(accessToken, modelId)
    cachedProjectId = discovered
    return discovered
  }

  private fun discoverProjectId(accessToken: String, modelId: String): String {
    val loadResponse = postCodeAssistJson(
      accessToken = accessToken,
      modelId = modelId,
      path = "/v1internal:loadCodeAssist",
      body = buildJsonObject {
        put("metadata", codeAssistClientMetadata(""))
      },
    )
    val loadProject = loadResponse.string("cloudaicompanionProject")
    val tierId = loadResponse.obj("currentTier")?.string("id").orEmpty()
    if (tierId.isNotBlank() && loadProject.isNotBlank()) return loadProject

    val onboardResponse = postCodeAssistJson(
      accessToken = accessToken,
      modelId = modelId,
      path = "/v1internal:onboardUser",
      body = buildJsonObject {
        put("tierId", FREE_TIER_ID)
        put("metadata", codeAssistClientMetadata())
      },
    )
    val responseBody = onboardResponse.obj("response")
    val onboardProject = responseBody?.string("cloudaicompanionProject").orEmpty()
    return onboardProject.ifBlank {
      loadProject.ifBlank {
        throw IllegalStateException("Google Cloud Code Assist did not return a project id.")
      }
    }
  }

  companion object {
    private val json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = false
    }
  }
}

private data class GoogleCloudCodeAssistPreparedRequest(
  val accessToken: String,
  val wrappedJson: String,
)

internal class GoogleCloudCodeAssistCredentials(
  val token: String,
  val projectId: String = "",
  val managedProjectId: String = "",
  val mode: GoogleCloudCodeAssistCredentialMode = GoogleCloudCodeAssistCredentialMode.AccessToken,
) {
  @Volatile
  private var cachedAccessToken: String = if (mode == GoogleCloudCodeAssistCredentialMode.AccessToken) token else ""

  @Volatile
  private var expiresAtMillis: Long = 0L

  val accessToken: String
    get() = currentAccessToken()

  val refreshToken: String
    get() = if (mode == GoogleCloudCodeAssistCredentialMode.RefreshToken) token else ""

  val initialOpenAIClientToken: String
    get() = if (mode == GoogleCloudCodeAssistCredentialMode.AccessToken) token else "google-oauth-refresh"

  val usesRefreshToken: Boolean
    get() = mode == GoogleCloudCodeAssistCredentialMode.RefreshToken

  @Synchronized
  fun currentAccessToken(): String {
    if (mode == GoogleCloudCodeAssistCredentialMode.AccessToken) return token
    val now = System.currentTimeMillis()
    if (cachedAccessToken.isNotBlank() && now + GOOGLE_OAUTH_REFRESH_SKEW_MILLIS < expiresAtMillis) {
      return cachedAccessToken
    }
    val refreshed = refreshGoogleOAuthAccessToken(token)
    cachedAccessToken = refreshed.accessToken
    expiresAtMillis = now + refreshed.expiresInSeconds.coerceAtLeast(60) * 1000L
    return cachedAccessToken
  }

  companion object {
    fun from(rawApiKey: String): GoogleCloudCodeAssistCredentials {
      val parts = rawApiKey.split("|")
      val rawToken = parts.getOrNull(0).orEmpty().trim()
      val mode = when {
        rawToken.startsWith("refresh:") -> GoogleCloudCodeAssistCredentialMode.RefreshToken
        rawToken.startsWith("access:") -> GoogleCloudCodeAssistCredentialMode.AccessToken
        rawToken.startsWith("1//") -> GoogleCloudCodeAssistCredentialMode.RefreshToken
        else -> GoogleCloudCodeAssistCredentialMode.AccessToken
      }
      val token = rawToken
        .removePrefix("refresh:")
        .removePrefix("access:")
      return GoogleCloudCodeAssistCredentials(
        token = token,
        projectId = parts.getOrNull(1).orEmpty().trim(),
        managedProjectId = parts.getOrNull(2).orEmpty().trim(),
        mode = mode,
      )
    }
  }
}

internal enum class GoogleCloudCodeAssistCredentialMode {
  AccessToken,
  RefreshToken,
}

private data class GoogleOAuthRefreshResult(
  val accessToken: String,
  val expiresInSeconds: Long,
)

internal fun buildGoogleCloudCodeAssistRequest(
  openAIRequestJson: String,
  projectId: String,
  userPromptId: String = UUID.randomUUID().toString(),
): String {
  val request = cloudCodeJson.parseToJsonElement(openAIRequestJson).jsonObject
  val inner = buildGeminiInnerRequest(request)
  return cloudCodeJson.encodeToString(
    buildJsonObject {
      put("project", projectId)
      put("model", request.string("model"))
      put("user_prompt_id", userPromptId)
      put("request", inner)
    },
  )
}

internal fun translateGoogleCloudCodeAssistResponseToOpenAIJson(
  codeAssistResponseJson: String,
  model: String,
): String {
  val response = cloudCodeJson.parseToJsonElement(codeAssistResponseJson).jsonObject
  val inner = response.obj("response") ?: response
  val candidate = inner.array("candidates")?.firstOrNull()?.objOrNull()
  val parts = candidate
    ?.obj("content")
    ?.array("parts")
    .orEmpty()
  val textParts = mutableListOf<String>()
  val reasoningParts = mutableListOf<String>()
  val toolCalls = mutableListOf<JsonObject>()

  parts.forEachIndexed { index, partElement ->
    val part = partElement.objOrNull() ?: return@forEachIndexed
    when {
      part.boolean("thought") && part.string("text").isNotBlank() -> reasoningParts += part.string("text")
      part.string("text").isNotBlank() -> textParts += part.string("text")
      part.obj("functionCall") != null -> {
        val functionCall = part.obj("functionCall") ?: return@forEachIndexed
        toolCalls += buildJsonObject {
          put("id", "call_${UUID.randomUUID().toString().replace("-", "").take(12)}")
          put("type", "function")
          put("index", index)
          put(
            "function",
            buildJsonObject {
              put("name", functionCall.string("name"))
              put("arguments", cloudCodeJson.encodeToString(functionCall["args"] ?: buildJsonObject { }))
            },
          )
        }
      }
    }
  }

  val finishReason = if (toolCalls.isNotEmpty()) {
    "tool_calls"
  } else {
    mapGeminiFinishReason(candidate?.string("finishReason").orEmpty())
  }
  val usage = inner.obj("usageMetadata")
  val promptTokens = usage?.int("promptTokenCount") ?: 0
  val completionTokens = usage?.int("candidatesTokenCount") ?: 0
  val totalTokens = usage?.int("totalTokenCount") ?: promptTokens + completionTokens

  return cloudCodeJson.encodeToString(
    buildJsonObject {
      put("id", "chatcmpl-${UUID.randomUUID()}")
      put("object", "chat.completion")
      put("created", System.currentTimeMillis() / 1000L)
      put("model", model)
      put(
        "choices",
        buildJsonArray {
          add(
            buildJsonObject {
              put("index", 0)
              put("finish_reason", finishReason)
              put(
                "message",
                buildJsonObject {
                  put("role", "assistant")
                  put("content", textParts.joinToString(""))
                  if (reasoningParts.isNotEmpty()) {
                    put("reasoning_content", reasoningParts.joinToString(""))
                  }
                  if (toolCalls.isNotEmpty()) {
                    put("tool_calls", JsonArray(toolCalls))
                  }
                },
              )
            },
          )
        },
      )
      put(
        "usage",
        buildJsonObject {
          put("prompt_tokens", promptTokens)
          put("completion_tokens", completionTokens)
          put("total_tokens", totalTokens)
          put(
            "prompt_tokens_details",
            buildJsonObject {
              put("cached_tokens", usage?.int("cachedContentTokenCount") ?: 0)
            },
          )
        },
      )
    },
  )
}

internal data class GoogleCloudCodeAssistStreamChunk(
  val content: String = "",
  val reasoning: String = "",
  val toolCallId: String = "",
  val toolCallName: String = "",
  val toolCallArguments: String = "",
  val toolCallIndex: Int? = null,
  val finishReason: String? = null,
)

internal data class GoogleCloudCodeAssistStreamTranslation(
  val chunks: List<GoogleCloudCodeAssistStreamChunk>,
  val nextToolCallIndex: Int,
  val hasToolCalls: Boolean,
)

internal fun translateGoogleCloudCodeAssistStreamEvent(
  codeAssistEventJson: String,
  toolCallStartIndex: Int = 0,
  anyPreviousToolCalls: Boolean = false,
): GoogleCloudCodeAssistStreamTranslation {
  val event = cloudCodeJson.parseToJsonElement(codeAssistEventJson).jsonObject
  val inner = event.obj("response") ?: event
  val candidate = inner.array("candidates")?.firstOrNull()?.objOrNull()
    ?: return GoogleCloudCodeAssistStreamTranslation(emptyList(), toolCallStartIndex, hasToolCalls = false)
  val chunks = mutableListOf<GoogleCloudCodeAssistStreamChunk>()
  var nextToolCallIndex = toolCallStartIndex
  var hasToolCalls = false

  candidate.obj("content")?.array("parts").orEmpty().forEach { partElement ->
    val part = partElement.objOrNull() ?: return@forEach
    when {
      part.boolean("thought") && part.string("text").isNotBlank() -> {
        chunks += GoogleCloudCodeAssistStreamChunk(reasoning = part.string("text"))
      }
      part.string("text").isNotBlank() -> {
        chunks += GoogleCloudCodeAssistStreamChunk(content = part.string("text"))
      }
      part.obj("functionCall") != null -> {
        val functionCall = part.obj("functionCall") ?: return@forEach
        val index = nextToolCallIndex
        nextToolCallIndex += 1
        hasToolCalls = true
        chunks += GoogleCloudCodeAssistStreamChunk(
          toolCallId = "call_${UUID.randomUUID().toString().replace("-", "").take(12)}",
          toolCallName = functionCall.string("name"),
          toolCallArguments = cloudCodeJson.encodeToString(functionCall["args"] ?: buildJsonObject { }),
          toolCallIndex = index,
        )
      }
    }
  }

  val finishReason = candidate.string("finishReason")
  if (finishReason.isNotBlank()) {
    chunks += GoogleCloudCodeAssistStreamChunk(
      finishReason = if (hasToolCalls || anyPreviousToolCalls) {
        "tool_calls"
      } else {
        mapGeminiFinishReason(finishReason)
      },
    )
  }

  return GoogleCloudCodeAssistStreamTranslation(
    chunks = chunks,
    nextToolCallIndex = nextToolCallIndex,
    hasToolCalls = hasToolCalls,
  )
}

private fun buildGeminiInnerRequest(request: JsonObject): JsonObject {
  val (contents, systemInstruction) = buildGeminiContents(request.array("messages").orEmpty())
  val tools = translateToolsToGemini(request.array("tools"))
  val toolConfig = translateToolChoiceToGemini(request["tool_choice"])
  val generationConfig = buildGenerationConfig(request)
  return buildJsonObject {
    put("contents", JsonArray(contents))
    systemInstruction?.let { put("systemInstruction", it) }
    if (tools.isNotEmpty()) put("tools", JsonArray(tools))
    toolConfig?.let { put("toolConfig", it) }
    if (generationConfig.isNotEmpty()) put("generationConfig", generationConfig)
  }
}

private fun buildGeminiContents(messages: List<JsonElement>): Pair<List<JsonObject>, JsonObject?> {
  val systemParts = mutableListOf<String>()
  val contents = mutableListOf<JsonObject>()

  messages.forEach { element ->
    val message = element.objOrNull() ?: return@forEach
    val role = message.string("role").ifBlank { "user" }
    if (role == "system") {
      coerceContentToText(message["content"]).takeIf { it.isNotBlank() }?.let { systemParts += it }
      return@forEach
    }
    if (role == "tool" || role == "function") {
      contents += buildJsonObject {
        put("role", "user")
        put("parts", buildJsonArray { add(translateToolResultToGemini(message)) })
      }
      return@forEach
    }

    val parts = mutableListOf<JsonObject>()
    coerceContentToText(message["content"]).takeIf { it.isNotBlank() }?.let { text ->
      parts += buildJsonObject { put("text", text) }
    }
    message.array("tool_calls").orEmpty().forEach { toolCall ->
      toolCall.objOrNull()?.let { parts += translateToolCallToGemini(it) }
    }
    if (parts.isNotEmpty()) {
      contents += buildJsonObject {
        put("role", if (role == "assistant") "model" else "user")
        put("parts", JsonArray(parts))
      }
    }
  }

  val systemInstruction = systemParts.joinToString("\n").trim().takeIf { it.isNotBlank() }?.let { text ->
    buildJsonObject {
      put("role", "system")
      put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
    }
  }
  return contents to systemInstruction
}

private fun translateToolCallToGemini(toolCall: JsonObject): JsonObject {
  val function = toolCall.obj("function") ?: buildJsonObject { }
  val rawArguments = function.string("arguments")
  val args = parseJsonObjectOrWrapped(rawArguments)
  return buildJsonObject {
    put(
      "functionCall",
      buildJsonObject {
        put("name", function.string("name"))
        put("args", args)
      },
    )
    put("thoughtSignature", "skip_thought_signature_validator")
  }
}

private fun translateToolResultToGemini(message: JsonObject): JsonObject {
  val name = message.string("name").ifBlank { message.string("tool_call_id").ifBlank { "tool" } }
  val content = coerceContentToText(message["content"])
  val parsed = parseJsonObjectOrNull(content)
  return buildJsonObject {
    put(
      "functionResponse",
      buildJsonObject {
        put("name", name)
        put("response", parsed ?: buildJsonObject { put("output", content) })
      },
    )
  }
}

private fun translateToolsToGemini(tools: JsonArray?): List<JsonObject> {
  if (tools == null || tools.isEmpty()) return emptyList()
  val declarations = tools.mapNotNull { tool ->
    val function = tool.objOrNull()?.obj("function") ?: return@mapNotNull null
    val name = function.string("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
    buildJsonObject {
      put("name", name)
      function.string("description").takeIf { it.isNotBlank() }?.let { put("description", it) }
      put("parameters", sanitizeGeminiToolParameters(function["parameters"]))
    }
  }
  if (declarations.isEmpty()) return emptyList()
  return listOf(buildJsonObject { put("functionDeclarations", JsonArray(declarations)) })
}

private fun translateToolChoiceToGemini(toolChoice: JsonElement?): JsonObject? {
  val primitive = toolChoice as? JsonPrimitive
  if (primitive != null && primitive.isString) {
    return when (primitive.contentOrNull) {
      "auto" -> functionCallingConfig("AUTO")
      "required" -> functionCallingConfig("ANY")
      "none" -> functionCallingConfig("NONE")
      else -> null
    }
  }
  val functionName = toolChoice?.objOrNull()?.obj("function")?.string("name").orEmpty()
  if (functionName.isBlank()) return null
  return functionCallingConfig("ANY", functionName)
}

private fun functionCallingConfig(mode: String, allowedFunctionName: String = ""): JsonObject {
  return buildJsonObject {
    put(
      "functionCallingConfig",
      buildJsonObject {
        put("mode", mode)
        if (allowedFunctionName.isNotBlank()) {
          put("allowedFunctionNames", buildJsonArray { add(JsonPrimitive(allowedFunctionName)) })
        }
      },
    )
  }
}

private fun buildGenerationConfig(request: JsonObject): JsonObject {
  val extraBody = request.obj("extra_body")
  val thinkingConfig = extraBody?.obj("thinking_config") ?: extraBody?.obj("thinkingConfig")
  return buildJsonObject {
    request.double("temperature")?.let { put("temperature", it) }
    request.int("max_tokens")?.takeIf { it > 0 }?.let { put("maxOutputTokens", it) }
    request.double("top_p")?.let { put("topP", it) }
    val stop = request["stop"]
    when {
      stop is JsonPrimitive && stop.isString && !stop.content.isNullOrBlank() -> {
        put("stopSequences", buildJsonArray { add(JsonPrimitive(stop.content)) })
      }
      stop is JsonArray && stop.isNotEmpty() -> {
        put(
          "stopSequences",
          buildJsonArray {
            stop.forEach { item ->
              item.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let { add(JsonPrimitive(it)) }
            }
          },
        )
      }
    }
    normalizeThinkingConfig(thinkingConfig)?.let { put("thinkingConfig", it) }
  }
}

private fun normalizeThinkingConfig(config: JsonObject?): JsonObject? {
  if (config == null || config.isEmpty()) return null
  val normalized = buildJsonObject {
    (config.int("thinkingBudget") ?: config.int("thinking_budget"))?.let { put("thinkingBudget", it) }
    (config.string("thinkingLevel").ifBlank { config.string("thinking_level") })
      .takeIf { it.isNotBlank() }
      ?.let { put("thinkingLevel", it.lowercase()) }
    (config.booleanOrNull("includeThoughts") ?: config.booleanOrNull("include_thoughts"))?.let {
      put("includeThoughts", it)
    }
  }
  return normalized.takeIf { it.isNotEmpty() }
}

private fun sanitizeGeminiToolParameters(parameters: JsonElement?): JsonObject {
  val cleaned = sanitizeGeminiSchema(parameters)
  return cleaned.takeIf { it.isNotEmpty() } ?: buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { })
  }
}

private fun sanitizeGeminiSchema(schema: JsonElement?): JsonObject {
  val obj = schema?.objOrNull() ?: return buildJsonObject { }
  val cleaned = buildJsonObject {
    obj.forEach { (key, value) ->
      if (key !in GEMINI_SCHEMA_ALLOWED_KEYS) return@forEach
      when (key) {
        "properties" -> {
          val properties = value.objOrNull() ?: return@forEach
          put(
            key,
            buildJsonObject {
              properties.forEach { (propertyName, propertySchema) ->
                put(propertyName, sanitizeGeminiSchema(propertySchema))
              }
            },
          )
        }
        "items" -> put(key, sanitizeGeminiSchema(value))
        "anyOf" -> {
          val items = value.arrayOrNull() ?: return@forEach
          put(
            key,
            buildJsonArray {
              items.forEach { item ->
                if (item.objOrNull() != null) add(sanitizeGeminiSchema(item))
              }
            },
          )
        }
        else -> put(key, value)
      }
    }
  }.toMutableMap()
  val type = cleaned["type"]?.jsonPrimitive?.contentOrNull
  val enum = cleaned["enum"]?.arrayOrNull()
  if (type in setOf("integer", "number", "boolean") && enum?.any { it !is JsonPrimitive || !it.isString } == true) {
    cleaned.remove("enum")
  }
  return JsonObject(cleaned)
}

private fun coerceContentToText(content: JsonElement?): String {
  return when (content) {
    null, JsonNull -> ""
    is JsonPrimitive -> content.contentOrNull.orEmpty()
    is JsonArray -> content.mapNotNull { part ->
      when (part) {
        is JsonPrimitive -> part.contentOrNull
        is JsonObject -> when {
          part.string("type") == "text" -> part.string("text")
          part.string("type") == "input_text" -> part.string("text")
          else -> null
        }
        else -> null
      }
    }.joinToString("\n")
    else -> content.toString()
  }
}

private fun parseJsonObjectOrWrapped(raw: String): JsonObject {
  return parseJsonObjectOrNull(raw) ?: buildJsonObject {
    if (raw.isBlank()) {
      put("_raw", "")
    } else {
      put("_raw", raw)
    }
  }
}

private fun parseJsonObjectOrNull(raw: String): JsonObject? {
  if (raw.isBlank()) return null
  return runCatching { cloudCodeJson.parseToJsonElement(raw).objOrNull() }.getOrNull()
}

private fun mapGeminiFinishReason(reason: String): String {
  return when (reason.uppercase()) {
    "MAX_TOKENS" -> "length"
    "SAFETY",
    "RECITATION",
    "BLOCKLIST",
    "PROHIBITED_CONTENT",
    "SPII",
    "MALFORMED_FUNCTION_CALL",
    -> "content_filter"
    else -> "stop"
  }
}

private fun googleCloudCodeAssistBaseClient(): HttpClient {
  return HttpClient {
    defaultRequest {
      header("User-Agent", "hermes-agent (gemini-cli-compat)")
      header("X-Goog-Api-Client", "gl-python/hermes")
      header("x-activity-request-id", UUID.randomUUID().toString())
    }
  }
}

private suspend fun streamGoogleCloudCodeAssistEvents(
  accessToken: String,
  modelId: String,
  wrappedRequestJson: String,
  onEvent: suspend (String) -> Unit,
) {
  val connection = openCodeAssistConnection(
    accessToken = accessToken,
    modelId = modelId,
    path = "/v1internal:streamGenerateContent?alt=sse",
    accept = "text/event-stream",
  )
  connection.outputStream.use { it.write(wrappedRequestJson.toByteArray(Charsets.UTF_8)) }
  val status = connection.responseCode
  if (status !in 200..299) {
    val responseText = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    throw IllegalStateException(googleCloudCodeAssistErrorMessage(status, responseText))
  }
  connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
    while (true) {
      val line = reader.readLine() ?: break
      val trimmed = line.trimEnd('\r')
      if (!trimmed.startsWith("data: ")) continue
      val data = trimmed.removePrefix("data: ")
      if (data == "[DONE]") break
      if (data.isBlank()) continue
      onEvent(data)
    }
  }
}

private fun postGoogleCloudCodeAssistGenerateContent(
  accessToken: String,
  modelId: String,
  wrappedRequestJson: String,
): String {
  val connection = openCodeAssistConnection(
    accessToken = accessToken,
    modelId = modelId,
    path = "/v1internal:generateContent",
    accept = "application/json",
  )
  connection.outputStream.use { it.write(wrappedRequestJson.toByteArray(Charsets.UTF_8)) }
  val status = connection.responseCode
  val stream = if (status in 200..299) connection.inputStream else connection.errorStream
  val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
  if (status !in 200..299) {
    throw IllegalStateException(googleCloudCodeAssistErrorMessage(status, responseText))
  }
  return responseText
}

private fun postCodeAssistJson(
  accessToken: String,
  modelId: String,
  path: String,
  body: JsonObject,
): JsonObject {
  val connection = openCodeAssistConnection(accessToken, modelId, path, accept = "application/json")
  val payload = cloudCodeJson.encodeToString(body).toByteArray(Charsets.UTF_8)
  connection.outputStream.use { it.write(payload) }
  val status = connection.responseCode
  val stream = if (status in 200..299) connection.inputStream else connection.errorStream
  val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
  if (status !in 200..299) {
    throw IllegalStateException(googleCloudCodeAssistErrorMessage(status, responseText))
  }
  return if (responseText.isBlank()) buildJsonObject { } else cloudCodeJson.parseToJsonElement(responseText).jsonObject
}

private fun openCodeAssistConnection(
  accessToken: String,
  modelId: String,
  path: String,
  accept: String,
): HttpURLConnection {
  return (URL(CODE_ASSIST_ENDPOINT + path).openConnection() as HttpURLConnection).apply {
    requestMethod = "POST"
    connectTimeout = 15_000
    readTimeout = 600_000
    doOutput = true
    setRequestProperty("Content-Type", "application/json")
    setRequestProperty("Accept", accept)
    setRequestProperty("Authorization", "Bearer $accessToken")
    setRequestProperty("User-Agent", "$GEMINI_CLI_USER_AGENT model/$modelId")
    setRequestProperty("X-Goog-Api-Client", GEMINI_CLI_API_CLIENT)
    setRequestProperty("x-activity-request-id", UUID.randomUUID().toString())
  }
}

internal fun googleCloudCodeAssistErrorMessage(status: Int, bodyText: String): String {
  val error = runCatching { cloudCodeJson.parseToJsonElement(bodyText).jsonObject.obj("error") }.getOrNull()
  val errStatus = error?.string("status").orEmpty()
  val errMessage = error?.string("message").orEmpty()
  val details = error?.array("details").orEmpty().mapNotNull { it.objOrNull() }
  val errorInfo = details.firstOrNull { it.string("@type").endsWith("/google.rpc.ErrorInfo") }
  val retryInfo = details.firstOrNull { it.string("@type").endsWith("/google.rpc.RetryInfo") }
  val reason = errorInfo?.string("reason").orEmpty()
  val modelHint = errorInfo?.obj("metadata")?.string("model").orEmpty()
    .ifBlank { errorInfo?.obj("metadata")?.string("modelId").orEmpty() }
  val retryDelay = retryInfo?.string("retryDelay").orEmpty()
  val retrySuffix = retryDelay.takeIf { it.isNotBlank() }?.let { " Google suggests retrying in $it." }.orEmpty()
  return when {
    status == 429 && reason == "MODEL_CAPACITY_EXHAUSTED" -> {
      "Gemini capacity exhausted for ${modelHint.ifBlank { "this Gemini model" }} (Google-side throttle, not a Flovera issue).$retrySuffix"
    }
    status == 429 && errStatus == "RESOURCE_EXHAUSTED" -> {
      "Gemini quota exhausted (${errMessage.ifBlank { "RESOURCE_EXHAUSTED" }}).$retrySuffix"
    }
    status == 401 -> "Code Assist HTTP 401: Google OAuth token is unauthorized or expired."
    status == 404 -> {
      "Code Assist 404: ${modelHint.ifBlank { errMessage.ifBlank { "model" } }} is not available at cloudcode-pa.googleapis.com."
    }
    errMessage.isNotBlank() -> "Code Assist HTTP $status (${errStatus.ifBlank { "error" }}): $errMessage"
    else -> "Code Assist returned HTTP $status: ${bodyText.take(500)}"
  }
}

internal fun googleOAuthRefreshFormBody(
  refreshToken: String,
  clientId: String = GOOGLE_GEMINI_CLI_OAUTH_CLIENT_ID,
  clientSecret: String = GOOGLE_GEMINI_CLI_OAUTH_CLIENT_SECRET,
): String {
  val fields = linkedMapOf(
    "grant_type" to "refresh_token",
    "refresh_token" to refreshToken,
    "client_id" to clientId,
  )
  if (clientSecret.isNotBlank()) fields["client_secret"] = clientSecret
  return fields.entries.joinToString("&") { (key, value) ->
    "${urlEncode(key)}=${urlEncode(value)}"
  }
}

private fun refreshGoogleOAuthAccessToken(refreshToken: String): GoogleOAuthRefreshResult {
  if (refreshToken.isBlank()) {
    throw IllegalStateException("Cannot refresh Google OAuth token: refresh token is empty.")
  }
  val connection = (URL(GOOGLE_OAUTH_TOKEN_ENDPOINT).openConnection() as HttpURLConnection).apply {
    requestMethod = "POST"
    connectTimeout = 15_000
    readTimeout = 30_000
    doOutput = true
    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    setRequestProperty("Accept", "application/json")
  }
  val body = googleOAuthRefreshFormBody(refreshToken).toByteArray(Charsets.UTF_8)
  connection.outputStream.use { it.write(body) }
  val status = connection.responseCode
  val stream = if (status in 200..299) connection.inputStream else connection.errorStream
  val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
  if (status !in 200..299) {
    val error = runCatching { cloudCodeJson.parseToJsonElement(responseText).jsonObject }.getOrNull()
    val errorCode = error?.string("error").orEmpty()
    val description = error?.string("error_description").orEmpty()
    throw IllegalStateException(
      "Google OAuth token endpoint returned HTTP $status: ${description.ifBlank { errorCode.ifBlank { responseText.take(500) } }}",
    )
  }
  val payload = cloudCodeJson.parseToJsonElement(responseText).jsonObject
  val accessToken = payload.string("access_token")
  if (accessToken.isBlank()) {
    throw IllegalStateException("Google OAuth refresh response did not include an access_token.")
  }
  return GoogleOAuthRefreshResult(
    accessToken = accessToken,
    expiresInSeconds = payload["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L,
  )
}

private fun urlEncode(value: String): String {
  return URLEncoder.encode(value, Charsets.UTF_8.name())
}

private fun codeAssistClientMetadata(duetProject: String = ""): JsonObject {
  return buildJsonObject {
    put("duetProject", duetProject)
    put("ideType", "IDE_UNSPECIFIED")
    put("platform", "PLATFORM_UNSPECIFIED")
    put("pluginType", "GEMINI")
  }
}

private fun JsonElement.objOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement.arrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonObject.obj(key: String): JsonObject? = this[key]?.objOrNull()

private fun JsonObject.array(key: String): JsonArray? = this[key]?.arrayOrNull()

private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

private fun JsonObject.boolean(key: String): Boolean = booleanOrNull(key) == true

private fun JsonObject.booleanOrNull(key: String): Boolean? {
  return this[key]?.jsonPrimitive?.contentOrNull?.let {
    when (it.lowercase()) {
      "true" -> true
      "false" -> false
      else -> null
    }
  }
}

private const val CODE_ASSIST_ENDPOINT = "https://cloudcode-pa.googleapis.com"
private const val GOOGLE_OAUTH_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
private const val GOOGLE_GEMINI_CLI_OAUTH_CLIENT_ID =
  "configure-google-oauth-client-id.apps.googleusercontent.com"
private const val GOOGLE_GEMINI_CLI_OAUTH_CLIENT_SECRET = "configure-google-oauth-client-secret"
private const val GOOGLE_OAUTH_REFRESH_SKEW_MILLIS = 60_000L
private const val FREE_TIER_ID = "free-tier"
private const val GEMINI_CLI_USER_AGENT = "google-api-nodejs-client/9.15.1 (gzip)"
private const val GEMINI_CLI_API_CLIENT = "gl-node/24.0.0"

private val cloudCodeJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = false
}

private val GEMINI_SCHEMA_ALLOWED_KEYS = setOf(
  "type",
  "format",
  "title",
  "description",
  "nullable",
  "enum",
  "maxItems",
  "minItems",
  "properties",
  "required",
  "minProperties",
  "maxProperties",
  "minLength",
  "maxLength",
  "pattern",
  "example",
  "anyOf",
  "propertyOrdering",
  "default",
  "items",
  "minimum",
  "maximum",
)
