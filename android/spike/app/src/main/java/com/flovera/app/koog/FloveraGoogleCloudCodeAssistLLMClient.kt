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
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import kotlinx.coroutines.flow.Flow
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
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
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
  apiKey = GoogleCloudCodeAssistCredentials.from(rawApiKey).accessToken,
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
    val openAiJson = super.serializeProviderChatRequest(messages, model, tools, toolChoice, params, stream = false)
    return buildGoogleCloudCodeAssistRequest(
      openAIRequestJson = openAiJson,
      projectId = ensureProjectId(model.id),
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
    throw UnsupportedOperationException("Google Cloud Code Assist streaming transport is not implemented yet.")
  }

  private fun ensureProjectId(modelId: String): String {
    cachedProjectId?.let { return it }
    val discovered = discoverProjectId(credentials.accessToken, modelId)
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

internal data class GoogleCloudCodeAssistCredentials(
  val accessToken: String,
  val projectId: String = "",
  val managedProjectId: String = "",
) {
  companion object {
    fun from(rawApiKey: String): GoogleCloudCodeAssistCredentials {
      val parts = rawApiKey.split("|")
      return GoogleCloudCodeAssistCredentials(
        accessToken = parts.getOrNull(0).orEmpty().trim(),
        projectId = parts.getOrNull(1).orEmpty().trim(),
        managedProjectId = parts.getOrNull(2).orEmpty().trim(),
      )
    }
  }
}

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

private fun postCodeAssistJson(
  accessToken: String,
  modelId: String,
  path: String,
  body: JsonObject,
): JsonObject {
  val connection = (URL(CODE_ASSIST_ENDPOINT + path).openConnection() as HttpURLConnection).apply {
    requestMethod = "POST"
    connectTimeout = 15_000
    readTimeout = 30_000
    doOutput = true
    setRequestProperty("Content-Type", "application/json")
    setRequestProperty("Accept", "application/json")
    setRequestProperty("Authorization", "Bearer $accessToken")
    setRequestProperty("User-Agent", "$GEMINI_CLI_USER_AGENT model/$modelId")
    setRequestProperty("X-Goog-Api-Client", GEMINI_CLI_API_CLIENT)
    setRequestProperty("x-activity-request-id", UUID.randomUUID().toString())
  }
  val payload = cloudCodeJson.encodeToString(body).toByteArray(Charsets.UTF_8)
  connection.outputStream.use { it.write(payload) }
  val status = connection.responseCode
  val stream = if (status in 200..299) connection.inputStream else connection.errorStream
  val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
  if (status !in 200..299) {
    throw IllegalStateException("Code Assist HTTP $status: ${responseText.take(500)}")
  }
  return if (responseText.isBlank()) buildJsonObject { } else cloudCodeJson.parseToJsonElement(responseText).jsonObject
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
