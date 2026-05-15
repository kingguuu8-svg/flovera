package com.flovera.app.koog

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolCall

internal fun prepareDeepSeekMessagesForFlovera(
  messages: List<OpenAIMessage>,
  reasoningByToolCallId: Map<String, String>,
  addJsonResponseHint: Boolean = false,
): List<OpenAIMessage> {
  val preparedMessages = mutableListOf<OpenAIMessage>()
  var pendingReasoning: OpenAIMessage.Assistant? = null

  for (message in messages) {
    if (message is OpenAIMessage.Assistant &&
      message.reasoningContent != null &&
      message.toolCalls.isNullOrEmpty()
    ) {
      flushPendingReasoning(preparedMessages, pendingReasoning)
      pendingReasoning = message
      continue
    }

    if (message is OpenAIMessage.Assistant && !message.toolCalls.isNullOrEmpty()) {
      preparedMessages += message.withDeepSeekReasoning(
        reasoningMessage = pendingReasoning,
        cachedReasoning = cachedReasoningFor(message.toolCalls.orEmpty(), reasoningByToolCallId),
      )
      pendingReasoning = null
      continue
    }

    flushPendingReasoning(preparedMessages, pendingReasoning)
    pendingReasoning = null
    preparedMessages += message
  }

  flushPendingReasoning(preparedMessages, pendingReasoning)

  if (addJsonResponseHint) {
    preparedMessages += OpenAIMessage.Assistant(Content.Text("Respond with JSON"))
  }

  return preparedMessages
}

private fun flushPendingReasoning(
  preparedMessages: MutableList<OpenAIMessage>,
  pendingReasoning: OpenAIMessage.Assistant?,
) {
  if (pendingReasoning != null) preparedMessages += pendingReasoning
}

private fun cachedReasoningFor(
  toolCalls: List<OpenAIToolCall>,
  reasoningByToolCallId: Map<String, String>,
): String? {
  val cached = toolCalls.mapNotNull { toolCall -> reasoningByToolCallId[toolCall.id] }.distinct()
  return cached.singleOrNull()
}

private fun OpenAIMessage.Assistant.withDeepSeekReasoning(
  reasoningMessage: OpenAIMessage.Assistant?,
  cachedReasoning: String?,
): OpenAIMessage.Assistant {
  val reasoning = reasoningContent ?: reasoningMessage?.reasoningContent ?: cachedReasoning
  if (reasoning == null) return this
  return OpenAIMessage.Assistant(
    content = reasoningMessage?.content ?: content,
    reasoningContent = reasoning,
    audio = audio ?: reasoningMessage?.audio,
    name = name ?: reasoningMessage?.name,
    refusal = refusal ?: reasoningMessage?.refusal,
    toolCalls = toolCalls,
    annotations = annotations ?: reasoningMessage?.annotations,
  )
}
