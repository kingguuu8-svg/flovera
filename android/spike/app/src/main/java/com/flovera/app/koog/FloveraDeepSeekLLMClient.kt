package com.flovera.app.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekParams
import ai.koog.prompt.executor.clients.deepseek.models.DeepSeekChatCompletionResponse
import ai.koog.prompt.executor.clients.deepseek.models.DeepSeekChatCompletionStreamResponse
import ai.koog.prompt.executor.clients.openai.base.AbstractOpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIResponseFormat
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIStreamOptions
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolChoice
import ai.koog.prompt.executor.clients.serialization.AdditionalPropertiesFlatteningSerializer
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.buildStreamFrameFlow
import com.flovera.app.config.AppSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock

open class FloveraDeepSeekLLMClient(
  private val settings: DeepSeekClientSettings = DeepSeekClientSettings(),
  private val requestSettings: FloveraDeepSeekRequestSettings = FloveraDeepSeekRequestSettings(),
  httpClient: KoogHttpClient,
  clock: Clock = Clock.System,
  toolsConverter: OpenAICompatibleToolDescriptorSchemaGenerator = OpenAICompatibleToolDescriptorSchemaGenerator(),
) : AbstractOpenAILLMClient<DeepSeekChatCompletionResponse, DeepSeekChatCompletionStreamResponse>(
  settings = settings,
  httpClient = httpClient,
  clock = clock,
  logger = staticLogger,
  toolsConverter = toolsConverter,
) {
  constructor(
    apiKey: String,
    requestSettings: FloveraDeepSeekRequestSettings = FloveraDeepSeekRequestSettings(),
    settings: DeepSeekClientSettings = DeepSeekClientSettings(),
    baseClient: HttpClient = HttpClient(),
    clock: Clock = Clock.System,
    toolsConverter: OpenAICompatibleToolDescriptorSchemaGenerator = OpenAICompatibleToolDescriptorSchemaGenerator(),
  ) : this(
    settings = settings,
    requestSettings = requestSettings,
    httpClient = createConfiguredHttpClient(apiKey, settings, staticLogger, baseClient, DEEPSEEK_CLIENT_NAME),
    clock = clock,
    toolsConverter = toolsConverter,
  )

  override val clientName: String = DEEPSEEK_CLIENT_NAME

  private val reasoningByToolCallId = mutableMapOf<String, String>()

  override fun llmProvider(): LLMProvider = LLMProvider.DeepSeek

  override suspend fun execute(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): List<Message.Response> {
    return retryDeepSeekTransient { super.execute(prompt, model, tools) }
  }

  override suspend fun executeMultipleChoices(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): List<List<Message.Response>> {
    return retryDeepSeekTransient { super.executeMultipleChoices(prompt, model, tools) }
  }

  override fun serializeProviderChatRequest(
    messages: List<OpenAIMessage>,
    model: LLModel,
    tools: List<OpenAITool>?,
    toolChoice: OpenAIToolChoice?,
    params: LLMParams,
    stream: Boolean,
  ): String {
    val deepSeekParams = params.toFloveraDeepSeekParams()
    val request = FloveraDeepSeekChatCompletionRequest(
      messages = prepareDeepSeekMessagesForFlovera(
        messages = messages,
        reasoningByToolCallId = reasoningByToolCallId,
        addJsonResponseHint = params.schema != null,
      ),
      model = model.id,
      frequencyPenalty = deepSeekParams.frequencyPenalty,
      logprobs = deepSeekParams.logprobs,
      maxTokens = deepSeekParams.maxTokens,
      presencePenalty = deepSeekParams.presencePenalty,
      responseFormat = createResponseFormat(params.schema, model),
      stop = deepSeekParams.stop,
      stream = stream,
      temperature = deepSeekParams.temperature,
      thinking = requestSettings.thinking,
      reasoningEffort = requestSettings.reasoningEffort,
      toolChoice = deepSeekParams.toolChoice?.toOpenAIToolChoice(),
      tools = tools,
      topLogprobs = deepSeekParams.topLogprobs,
      topP = deepSeekParams.topP,
      additionalProperties = deepSeekParams.additionalProperties,
    )
    return json.encodeToString(FloveraDeepSeekChatCompletionRequestSerializer, request)
  }

  override fun processProviderChatResponse(response: DeepSeekChatCompletionResponse): List<LLMChoice> {
    require(response.choices.isNotEmpty()) { "Empty choices in response" }
    response.choices.forEach { choice ->
      val message = choice.message as? OpenAIMessage.Assistant
      val reasoning = message?.reasoningContent
      if (!reasoning.isNullOrBlank()) {
        message.toolCalls.orEmpty().forEach { toolCall ->
          reasoningByToolCallId[toolCall.id] = reasoning
        }
      }
    }
    return response.choices.map { choice ->
      choice.message.toMessageResponses(
        choice.finishReason,
        createMetaInfo(response.usage),
      )
    }
  }

  override fun decodeStreamingResponse(data: String): DeepSeekChatCompletionStreamResponse {
    return json.decodeFromString(data)
  }

  override fun decodeResponse(data: String): DeepSeekChatCompletionResponse {
    return json.decodeFromString(data)
  }

  override fun processStreamingResponse(response: Flow<DeepSeekChatCompletionStreamResponse>): Flow<StreamFrame> {
    return buildStreamFrameFlow {
      var finishReason: String? = null
      var metaInfo: ResponseMetaInfo? = null

      response.collect { chunk ->
        chunk.choices.firstOrNull()?.let { choice ->
          choice.delta.content?.let { emitTextDelta(it) }
          choice.delta.toolCalls?.forEach { toolCall ->
            emitToolCallDelta(
              id = toolCall.id,
              name = toolCall.function?.name,
              args = toolCall.function?.arguments,
              index = toolCall.index,
            )
          }
          choice.finishReason?.let { finishReason = it }
        }
        chunk.usage?.let { metaInfo = createMetaInfo(it) }
      }

      emitEnd(finishReason, metaInfo)
    }
  }

  override fun createResponseFormat(schema: LLMParams.Schema?, model: LLModel): OpenAIResponseFormat? {
    return schema?.let {
      require(model.supports(it.capability)) {
        "Model ${model.id} does not support structured output schema ${it.name}"
      }
      when (it) {
        is LLMParams.Schema.JSON -> OpenAIResponseFormat.JsonObject()
      }
    }
  }

  override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
    staticLogger.warn { "Moderation is not supported by DeepSeek API" }
    throw UnsupportedOperationException("Moderation is not supported by DeepSeek API.")
  }

  override suspend fun models(): List<LLModel> {
    return DeepSeekModels.models
  }

  override suspend fun embed(text: String, model: LLModel): List<Double> {
    staticLogger.warn { "Embedding is not supported by DeepSeek API" }
    throw UnsupportedOperationException("Embedding is not supported by DeepSeek API.")
  }

  override suspend fun embed(inputs: List<String>, model: LLModel): List<List<Double>> {
    staticLogger.warn { "Embedding is not supported by DeepSeek API" }
    throw UnsupportedOperationException("Embedding is not supported by DeepSeek API.")
  }

  private suspend fun <T> retryDeepSeekTransient(block: suspend () -> T): T {
    var attempt = 0
    var delayMillis = DEEPSEEK_TRANSIENT_RETRY_INITIAL_DELAY_MS
    while (true) {
      try {
        return block()
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        if (!isTransientDeepSeekClientFailure(error) || attempt >= DEEPSEEK_TRANSIENT_RETRY_COUNT) {
          throw error
        }
        attempt += 1
        staticLogger.warn(error) {
          "Transient DeepSeek client failure; retrying request attempt=$attempt/$DEEPSEEK_TRANSIENT_RETRY_COUNT"
        }
        delay(delayMillis)
        delayMillis *= 2
      }
    }
  }

  companion object {
    private const val DEEPSEEK_CLIENT_NAME = "FloveraDeepSeekLLMClient"
    private const val DEEPSEEK_TRANSIENT_RETRY_COUNT = 2
    private const val DEEPSEEK_TRANSIENT_RETRY_INITIAL_DELAY_MS = 750L
    private val staticLogger = KotlinLogging.logger { }
  }
}

internal fun isTransientDeepSeekClientFailure(error: Throwable): Boolean {
  var current: Throwable? = error
  while (current != null) {
    if (current is IOException) return true
    val className = current.javaClass.name.lowercase()
    val message = current.message.orEmpty().lowercase()
    if (
      className.contains("closedbytechannel") ||
      message.contains("software caused connection abort") ||
      message.contains("connection reset") ||
      message.contains("closedbytechannel") ||
      message.contains("closed byte channel") ||
      message.contains("timeout")
    ) {
      return true
    }
    current = current.cause
  }
  return false
}

data class FloveraDeepSeekRequestSettings(
  val thinking: FloveraDeepSeekThinking? = FloveraDeepSeekThinking("enabled"),
  val reasoningEffort: String? = "high",
) {
  companion object {
    fun from(settings: AppSettings): FloveraDeepSeekRequestSettings {
      return when (settings.deepSeekThinkingEffort) {
        "off" -> FloveraDeepSeekRequestSettings(
          thinking = FloveraDeepSeekThinking("disabled"),
          reasoningEffort = null,
        )
        "max" -> FloveraDeepSeekRequestSettings(
          thinking = FloveraDeepSeekThinking("enabled"),
          reasoningEffort = "max",
        )
        else -> FloveraDeepSeekRequestSettings(
          thinking = FloveraDeepSeekThinking("enabled"),
          reasoningEffort = "high",
        )
      }
    }
  }
}

@Serializable
data class FloveraDeepSeekThinking(
  val type: String,
)

private data class FloveraDeepSeekParams(
  val temperature: Double?,
  val maxTokens: Int?,
  val schema: LLMParams.Schema?,
  val toolChoice: LLMParams.ToolChoice?,
  val additionalProperties: Map<String, JsonElement>?,
  val frequencyPenalty: Double?,
  val presencePenalty: Double?,
  val logprobs: Boolean?,
  val stop: List<String>?,
  val topLogprobs: Int?,
  val topP: Double?,
)

private fun LLMParams.toFloveraDeepSeekParams(): FloveraDeepSeekParams {
  if (this is DeepSeekParams) {
    return FloveraDeepSeekParams(
      temperature = temperature,
      maxTokens = maxTokens,
      schema = schema,
      toolChoice = toolChoice,
      additionalProperties = additionalProperties,
      frequencyPenalty = frequencyPenalty,
      presencePenalty = presencePenalty,
      logprobs = logprobs,
      stop = stop,
      topLogprobs = topLogprobs,
      topP = topP,
    )
  }
  return FloveraDeepSeekParams(
    temperature = temperature,
    maxTokens = maxTokens,
    schema = schema,
    toolChoice = toolChoice,
    additionalProperties = additionalProperties,
    frequencyPenalty = null,
    presencePenalty = null,
    logprobs = null,
    stop = null,
    topLogprobs = null,
    topP = null,
  )
}

@Serializable
private data class FloveraDeepSeekChatCompletionRequest(
  val messages: List<OpenAIMessage>,
  val model: String,
  val stream: Boolean? = null,
  val temperature: Double? = null,
  val tools: List<OpenAITool>? = null,
  val toolChoice: OpenAIToolChoice? = null,
  val topP: Double? = null,
  val topLogprobs: Int? = null,
  val maxTokens: Int? = null,
  val frequencyPenalty: Double? = null,
  val presencePenalty: Double? = null,
  val responseFormat: OpenAIResponseFormat? = null,
  val stop: List<String>? = null,
  val logprobs: Boolean? = null,
  val streamOptions: OpenAIStreamOptions? = null,
  val thinking: FloveraDeepSeekThinking? = null,
  @SerialName("reasoning_effort")
  val reasoningEffort: String? = null,
  val additionalProperties: Map<String, JsonElement>? = null,
)

private object FloveraDeepSeekChatCompletionRequestSerializer :
  AdditionalPropertiesFlatteningSerializer<FloveraDeepSeekChatCompletionRequest>(
    FloveraDeepSeekChatCompletionRequest.serializer(),
  )
