package com.flovera.app

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIFunction
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolChoice
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolCall
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import com.flovera.app.koog.FloveraDeepSeekLLMClient
import com.flovera.app.koog.FloveraDeepSeekRequestSettings
import com.flovera.app.koog.FloveraDeepSeekThinking
import com.flovera.app.koog.ModelProviderCatalog
import com.flovera.app.koog.isTransientDeepSeekClientFailure
import com.flovera.app.koog.prepareDeepSeekMessagesForFlovera
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloveraDeepSeekMessagePreparerInstrumentedTest {
  @Test
  fun mergesReasoningIntoParallelToolCalls() {
    val prepared = prepareDeepSeekMessagesForFlovera(
      messages = listOf(
        OpenAIMessage.User(Content.Text("inspect")),
        OpenAIMessage.Assistant(
          content = Content.Text("hidden reasoning"),
          reasoningContent = "hidden reasoning",
        ),
        OpenAIMessage.Assistant(
          toolCalls = listOf(
            toolCall("call_1", "read_file"),
            toolCall("call_2", "list_files"),
            toolCall("call_3", "read_file"),
          ),
        ),
        OpenAIMessage.Tool(Content.Text("one"), "call_1"),
        OpenAIMessage.Tool(Content.Text("two"), "call_2"),
        OpenAIMessage.Tool(Content.Text("three"), "call_3"),
      ),
      reasoningByToolCallId = emptyMap(),
    )

    assertEquals(5, prepared.size)
    val assistant = prepared[1] as OpenAIMessage.Assistant
    assertEquals("hidden reasoning", assistant.reasoningContent)
    assertEquals(3, assistant.toolCalls?.size)
    assertEquals("call_1", assistant.toolCalls?.get(0)?.id)
    assertEquals("call_2", assistant.toolCalls?.get(1)?.id)
    assertEquals("call_3", assistant.toolCalls?.get(2)?.id)
  }

  @Test
  fun restoresReasoningFromToolCallCacheWhenKoogHistoryDropsReasoningMessage() {
    val prepared = prepareDeepSeekMessagesForFlovera(
      messages = listOf(
        OpenAIMessage.Assistant(
          toolCalls = listOf(toolCall("call_1", "read_file"), toolCall("call_2", "list_files")),
        ),
        OpenAIMessage.Tool(Content.Text("one"), "call_1"),
        OpenAIMessage.Tool(Content.Text("two"), "call_2"),
      ),
      reasoningByToolCallId = mapOf(
        "call_1" to "cached reasoning",
        "call_2" to "cached reasoning",
      ),
    )

    val assistant = prepared.first() as OpenAIMessage.Assistant
    assertEquals("cached reasoning", assistant.reasoningContent)
    assertEquals(2, assistant.toolCalls?.size)
  }

  @Test
  fun leavesToolCallsUnchangedWhenReasoningIsUnknown() {
    val prepared = prepareDeepSeekMessagesForFlovera(
      messages = listOf(OpenAIMessage.Assistant(toolCalls = listOf(toolCall("call_1", "read_file")))),
      reasoningByToolCallId = emptyMap(),
    )

    val assistant = prepared.single() as OpenAIMessage.Assistant
    assertNull(assistant.reasoningContent)
    assertEquals("call_1", assistant.toolCalls?.single()?.id)
  }

  @Test
  fun serializesDeepSeekThinkingEffort() {
    val model = ModelProviderCatalog.requireProvider("deepseek").createModel("deepseek-v4-pro")
    val client = TestDeepSeekClient(
      requestSettings = FloveraDeepSeekRequestSettings(
        thinking = FloveraDeepSeekThinking("enabled"),
        reasoningEffort = "max",
      ),
    )

    val request = client.serializeProviderChatRequest(
      messages = listOf(OpenAIMessage.User(Content.Text("hello"))),
      model = model,
      tools = null,
      toolChoice = null,
      params = LLMParams(),
      stream = false,
    )

    assertTrue(request.contains("\"thinking\""))
    assertTrue(request.contains("\"type\":\"enabled\""))
    assertTrue(request.contains("\"reasoning_effort\":\"max\""))
  }

  @Test
  fun serializesDisabledDeepSeekThinkingWithoutReasoningEffort() {
    val model = ModelProviderCatalog.requireProvider("deepseek").createModel("deepseek-v4-pro")
    val client = TestDeepSeekClient(
      requestSettings = FloveraDeepSeekRequestSettings(
        thinking = FloveraDeepSeekThinking("disabled"),
        reasoningEffort = null,
      ),
    )

    val request = client.serializeProviderChatRequest(
      messages = listOf(OpenAIMessage.User(Content.Text("hello"))),
      model = model,
      tools = null,
      toolChoice = null,
      params = LLMParams(),
      stream = false,
    )

    assertTrue(request.contains("\"type\":\"disabled\""))
    assertFalse(request.contains("\"reasoning_effort\""))
  }

  @Test
  fun detectsTransientDeepSeekSocketFailuresWithoutRetryingApiValidationErrors() {
    assertTrue(isTransientDeepSeekClientFailure(IOException("Software caused connection abort")))
    assertTrue(
      isTransientDeepSeekClientFailure(
        RuntimeException("Error from client: FloveraDeepSeekLLMClient", IOException("connection reset")),
      ),
    )
    assertFalse(
      isTransientDeepSeekClientFailure(
        RuntimeException("Error from client: FloveraDeepSeekLLMClient\nStatus code: 400\nreasoning_content must be passed back"),
      ),
    )
  }

  private fun toolCall(id: String, name: String): OpenAIToolCall {
    return OpenAIToolCall(
      id = id,
      function = OpenAIFunction(name = name, arguments = "{}"),
    )
  }

  private class TestDeepSeekClient(
    requestSettings: FloveraDeepSeekRequestSettings,
  ) : FloveraDeepSeekLLMClient(apiKey = "test", requestSettings = requestSettings) {
    public override fun serializeProviderChatRequest(
      messages: List<OpenAIMessage>,
      model: LLModel,
      tools: List<OpenAITool>?,
      toolChoice: OpenAIToolChoice?,
      params: LLMParams,
      stream: Boolean,
    ): String {
      return super.serializeProviderChatRequest(messages, model, tools, toolChoice, params, stream)
    }
  }
}
