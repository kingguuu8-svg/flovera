package com.flovera.app.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock

open class FloveraCodexResponsesLLMClient(
  apiKey: String,
  settings: OpenAIClientSettings,
  private val providerIdentity: LLMProvider = LLMProvider.OpenAI,
  private val requestSettings: FloveraCodexResponsesRequestSettings = FloveraCodexResponsesRequestSettings(),
  baseClient: HttpClient = HttpClient(),
  clock: Clock = Clock.System,
) : OpenAILLMClient(
  apiKey = apiKey,
  settings = settings,
  baseClient = baseClient,
  clock = clock,
) {
  override fun llmProvider(): LLMProvider = providerIdentity

  override suspend fun execute(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): List<Message.Response> {
    return super.execute(prompt.withCodexResponsesParams(model.id), model, tools)
  }

  override suspend fun executeMultipleChoices(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): List<List<Message.Response>> {
    return super.executeMultipleChoices(prompt.withCodexResponsesParams(model.id), model, tools)
  }

  override fun executeStreaming(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): Flow<StreamFrame> {
    return super.executeStreaming(prompt.withCodexResponsesParams(model.id), model, tools)
  }

  override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
    throw UnsupportedOperationException("Moderation is not supported by Codex Responses provider ${requestSettings.providerId}.")
  }

  private fun Prompt.withCodexResponsesParams(modelId: String): Prompt {
    return withParams(requestSettings.toResponsesParams(params, modelId))
  }
}

data class FloveraCodexResponsesRequestSettings(
  val providerId: String = "",
  val sessionId: String? = null,
  val reasoningEffort: String = "",
)

fun FloveraCodexResponsesRequestSettings.toResponsesParams(
  params: LLMParams,
  modelId: String,
): OpenAIResponsesParams {
  val normalizedSessionId = sessionId?.trim()?.takeIf { it.isNotEmpty() }
  val reasoning = codexResponsesReasoningConfig(
    providerId = providerId,
    modelId = modelId,
    requestedEffort = reasoningEffort,
  )
  return OpenAIResponsesParams(
    temperature = params.temperature,
    maxTokens = params.maxTokens,
    numberOfChoices = params.numberOfChoices,
    speculation = params.speculation,
    schema = params.schema,
    toolChoice = params.toolChoice,
    user = params.user,
    additionalProperties = params.additionalProperties,
    include = emptyList<OpenAIInclude>(),
    parallelToolCalls = true,
    reasoning = reasoning,
    promptCacheKey = normalizedSessionId,
    store = false,
    topP = null,
  )
}

fun codexResponsesReasoningConfig(
  providerId: String,
  modelId: String,
  requestedEffort: String,
): ReasoningConfig? {
  val effort = normalizedCodexResponsesReasoningEffort(requestedEffort) ?: return null
  if (providerId == "xai" && !grokSupportsReasoningEffort(modelId)) return null
  return ReasoningConfig(effort = effort, summary = null)
}

fun normalizedCodexResponsesReasoningEffort(requestedEffort: String): ReasoningEffort? {
  return when (requestedEffort.trim().lowercase()) {
    "none" -> null
    "minimal", "low" -> ReasoningEffort.LOW
    "high", "xhigh" -> ReasoningEffort.HIGH
    else -> ReasoningEffort.MEDIUM
  }
}

fun grokSupportsReasoningEffort(modelId: String): Boolean {
  val normalized = modelId
    .trim()
    .lowercase()
    .substringAfterLast("/")
  return listOf(
    "grok-3-mini",
    "grok-4.20-multi-agent",
    "grok-4.3",
  ).any { normalized.startsWith(it) }
}
