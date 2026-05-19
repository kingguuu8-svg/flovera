package com.flovera.app.koog

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolChoice
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.params.LLMParams
import io.ktor.client.HttpClient
import kotlin.time.Clock

open class FloveraOpenAICompatibleLLMClient(
  apiKey: String,
  settings: OpenAIClientSettings = OpenAIClientSettings(),
  private val providerIdentity: LLMProvider = LLMProvider.OpenAI,
  private val requestProfile: ProviderRequestProfile = ProviderRequestProfile(),
  private val modelContext: ModelContextSpec = ModelContextSpec(),
  private val requestContext: ProviderRequestContext = ProviderRequestContext(),
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
  override fun llmProvider(): LLMProvider = providerIdentity

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
      requestContext = requestContext.withModelId(model.id),
    )
  }
}

fun applyFloveraOpenAIRequestProfileToJson(
  requestJson: String,
  requestProfile: ProviderRequestProfile,
  modelContext: ModelContextSpec,
  requestContext: ProviderRequestContext = ProviderRequestContext(),
): String {
  return ProviderRequestHooks.apply(
    requestJson = requestJson,
    requestProfile = requestProfile,
    modelContext = modelContext,
    requestContext = requestContext,
  )
}
