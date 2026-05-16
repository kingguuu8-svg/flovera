package com.flovera.app.koog

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolChoice
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

open class FloveraOpenAICompatibleLLMClient(
  apiKey: String,
  settings: OpenAIClientSettings = OpenAIClientSettings(),
  private val requestProfile: ProviderRequestProfile = ProviderRequestProfile(),
  private val modelContext: ModelContextSpec = ModelContextSpec(),
  baseClient: HttpClient = HttpClient(),
  clock: Clock = Clock.System,
  toolsConverter: OpenAICompatibleToolDescriptorSchemaGenerator = OpenAICompatibleToolDescriptorSchemaGenerator(),
) : OpenAILLMClient(
  apiKey = apiKey,
  settings = settings,
  baseClient = baseClient,
  clock = clock,
  toolsConverter = toolsConverter,
) {
  override fun serializeProviderChatRequest(
    messages: List<OpenAIMessage>,
    model: LLModel,
    tools: List<OpenAITool>?,
    toolChoice: OpenAIToolChoice?,
    params: LLMParams,
    stream: Boolean,
  ): String {
    return applyFloveraOpenAIRequestProfileToJson(
      requestJson = super.serializeProviderChatRequest(messages, model, tools, toolChoice, params, stream),
      requestProfile = requestProfile,
      modelContext = modelContext,
    )
  }
}

fun applyFloveraOpenAIRequestProfileToJson(
  requestJson: String,
  requestProfile: ProviderRequestProfile,
  modelContext: ModelContextSpec,
): String {
  if (!requestProfile.injectOllamaNumCtx) return requestJson
  val numCtx = modelContext.contextWindowTokens?.takeIf { it > 0 } ?: return requestJson
  val root = profileJson.parseToJsonElement(requestJson).jsonObject.toMutableMap()
  val options = (root["options"] as? JsonObject)?.toMutableMap() ?: mutableMapOf<String, JsonElement>()
  options["num_ctx"] = JsonPrimitive(numCtx)
  root["options"] = JsonObject(options)
  return profileJson.encodeToString(JsonObject.serializer(), JsonObject(root))
}

private val profileJson = Json {
  encodeDefaults = false
}
