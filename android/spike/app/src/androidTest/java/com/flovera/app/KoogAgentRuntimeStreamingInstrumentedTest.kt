package com.flovera.app

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.agent.AgentRunEvent
import com.flovera.app.agent.AgentRunEventSink
import com.flovera.app.agent.AgentRunEventType
import com.flovera.app.config.AppSettings
import com.flovera.app.koog.KoogAgentRuntime
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.workspace.WorkspaceManager
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KoogAgentRuntimeStreamingInstrumentedTest {
  @Test
  fun runStreamingForwardsTextDeltasBeforeProviderStreamCompletes() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "streaming-live-forward-${System.currentTimeMillis()}"
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    val session = AgentSessionStore(context).create("Streaming live forward")
    val fakeClient = FakeStreamingClient(
      streams = listOf(
        listOf(
          StreamFrame.TextDelta("early "),
          StreamFrame.TextDelta("late"),
          StreamFrame.End("stop"),
        ),
      ),
      fallbackText = "fallback should not be used",
      delayAfterFirstFrameMillis = 350,
    )
    val runtime = KoogAgentRuntime(clientFactory = { _, _, _ -> fakeClient })
    val firstDelta = CompletableDeferred<String>()

    val output = async {
      runtime.runStreaming(
        input = "Say early late.",
        agentRunId = "${session.id}-streaming-live-forward",
        settings = AppSettings(apiKey = "fake-key", activeWorkspaceId = workspaceId, activeSessionId = session.id),
        session = session,
        workspace = workspace,
        recorder = ToolEventRecorder(),
        eventSink = AgentRunEventSink { event ->
          if (event.type == AgentRunEventType.MODEL_TEXT_DELTA && !firstDelta.isCompleted) {
            firstDelta.complete(event.modelTextDelta)
          }
        },
      )
    }

    assertEquals("early ", withTimeout(1_000) { firstDelta.await() })
    assertFalse("runtime should still be waiting for later stream frames", output.isCompleted)
    assertEquals("early late", output.await())
  }

  @Test
  fun runStreamingEmitsProviderTextDeltasThroughAgentRunEvents() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "streaming-final-${System.currentTimeMillis()}"
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    val session = AgentSessionStore(context).create("Streaming final response")
    val fakeClient = FakeStreamingClient(
      streams = listOf(
        listOf(
          StreamFrame.TextDelta("hello "),
          StreamFrame.TextDelta("world"),
          StreamFrame.End("stop"),
        ),
      ),
      fallbackText = "fallback should not be used",
    )
    val runtime = KoogAgentRuntime(clientFactory = { _, _, _ -> fakeClient })
    val events = mutableListOf<AgentRunEvent>()

    val output = runtime.runStreaming(
      input = "Say hello world.",
      agentRunId = "${session.id}-streaming-final",
      settings = AppSettings(apiKey = "fake-key", activeWorkspaceId = workspaceId, activeSessionId = session.id),
      session = session,
      workspace = workspace,
      recorder = ToolEventRecorder(),
      eventSink = AgentRunEventSink { event -> events += event },
    )

    assertEquals("hello world", output)
    assertEquals(
      listOf("hello ", "world"),
      events.filter { it.type == AgentRunEventType.MODEL_TEXT_DELTA }.map { it.modelTextDelta },
    )
    assertEquals(1, fakeClient.streamingCallCount)
    assertEquals(0, fakeClient.nonStreamingChoiceCallCount)
  }

  @Test
  fun runStreamingKeepsWorkspaceToolLoopAndStreamsFinalTextAfterToolResult() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "streaming-tool-${System.currentTimeMillis()}"
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    val session = AgentSessionStore(context).create("Streaming tool loop")
    val fakeClient = FakeStreamingClient(
      streams = listOf(
        listOf(
          StreamFrame.ToolCallDelta(
            id = "call_write_file",
            name = "write_file",
            content = """{"path":"streaming-tool.txt","content":"OK"}""",
          ),
          StreamFrame.ToolCallComplete(
            id = "call_write_file",
            name = "write_file",
            content = """{"path":"streaming-tool.txt","content":"OK"}""",
          ),
          StreamFrame.End("tool_calls"),
        ),
        listOf(
          StreamFrame.TextDelta("created "),
          StreamFrame.TextDelta("file"),
          StreamFrame.End("stop"),
        ),
      ),
      fallbackText = "fallback should not be used",
    )
    val runtime = KoogAgentRuntime(clientFactory = { _, _, _ -> fakeClient })
    val eventOrder = mutableListOf<String>()
    val recorder = ToolEventRecorder { toolEvents ->
      eventOrder += "tool:${toolEvents.last().name}"
    }
    val events = mutableListOf<AgentRunEvent>()

    val output = runtime.runStreaming(
      input = "Create streaming-tool.txt with OK.",
      agentRunId = "${session.id}-streaming-tool",
      settings = AppSettings(apiKey = "fake-key", activeWorkspaceId = workspaceId, activeSessionId = session.id),
      session = session,
      workspace = workspace,
      recorder = recorder,
      eventSink = AgentRunEventSink { event ->
        events += event
        if (event.type == AgentRunEventType.MODEL_TEXT_DELTA) {
          eventOrder += "text:${event.modelTextDelta}"
        }
      },
    )

    assertEquals("created file", output)
    assertEquals("OK", File(workspace.root, "streaming-tool.txt").readText())
    assertTrue(recorder.snapshot().any { it.name == "write_file" })
    assertEquals(
      listOf("created ", "file"),
      events.filter { it.type == AgentRunEventType.MODEL_TEXT_DELTA }.map { it.modelTextDelta },
    )
    assertEquals(2, fakeClient.streamingCallCount)
    assertEquals(0, fakeClient.nonStreamingChoiceCallCount)
  }

  @Test
  fun runStreamingExposesTextBeforeAndAfterToolCallFrames() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "streaming-interleaved-${System.currentTimeMillis()}"
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    val session = AgentSessionStore(context).create("Streaming interleaved tool loop")
    val fakeClient = FakeStreamingClient(
      streams = listOf(
        listOf(
          StreamFrame.TextDelta("I will create it. "),
          StreamFrame.ToolCallDelta(
            id = "call_write_file",
            name = "write_file",
            content = """{"path":"interleaved-tool.txt","content":"OK"}""",
          ),
          StreamFrame.ToolCallComplete(
            id = "call_write_file",
            name = "write_file",
            content = """{"path":"interleaved-tool.txt","content":"OK"}""",
          ),
          StreamFrame.End("tool_calls"),
        ),
        listOf(
          StreamFrame.TextDelta("Created interleaved-tool.txt."),
          StreamFrame.End("stop"),
        ),
      ),
      fallbackText = "fallback should not be used",
    )
    val runtime = KoogAgentRuntime(clientFactory = { _, _, _ -> fakeClient })
    val eventOrder = mutableListOf<String>()
    val recorder = ToolEventRecorder { toolEvents ->
      eventOrder += "tool:${toolEvents.last().name}"
    }
    val events = mutableListOf<AgentRunEvent>()

    val output = runtime.runStreaming(
      input = "Create interleaved-tool.txt with OK.",
      agentRunId = "${session.id}-streaming-interleaved",
      settings = AppSettings(apiKey = "fake-key", activeWorkspaceId = workspaceId, activeSessionId = session.id),
      session = session,
      workspace = workspace,
      recorder = recorder,
      eventSink = AgentRunEventSink { event ->
        events += event
        if (event.type == AgentRunEventType.MODEL_TEXT_DELTA) {
          eventOrder += "text:${event.modelTextDelta}"
        }
      },
    )

    assertEquals("Created interleaved-tool.txt.", output)
    assertEquals("OK", File(workspace.root, "interleaved-tool.txt").readText())
    assertTrue(recorder.snapshot().any { it.name == "write_file" })
    assertEquals(
      listOf("I will create it. ", "Created interleaved-tool.txt."),
      events.filter { it.type == AgentRunEventType.MODEL_TEXT_DELTA }.map { it.modelTextDelta },
    )
    assertEquals(
      listOf("text:I will create it. ", "tool:write_file", "text:Created interleaved-tool.txt."),
      eventOrder,
    )
    assertEquals(2, fakeClient.streamingCallCount)
    assertEquals(0, fakeClient.nonStreamingChoiceCallCount)
  }

  @Test
  fun runStreamingFallsBackToNonStreamingWhenClientDoesNotSupportStreaming() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "streaming-fallback-${System.currentTimeMillis()}"
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    val session = AgentSessionStore(context).create("Streaming fallback")
    val fakeClient = FakeStreamingClient(
      streams = emptyList(),
      fallbackText = "non-streaming fallback",
      streamingUnsupported = true,
    )
    val runtime = KoogAgentRuntime(clientFactory = { _, _, _ -> fakeClient })
    val events = mutableListOf<AgentRunEvent>()

    val output = runtime.runStreaming(
      input = "Use fallback.",
      agentRunId = "${session.id}-streaming-fallback",
      settings = AppSettings(apiKey = "fake-key", activeWorkspaceId = workspaceId, activeSessionId = session.id),
      session = session,
      workspace = workspace,
      recorder = ToolEventRecorder(),
      eventSink = AgentRunEventSink { event -> events += event },
    )

    assertEquals("non-streaming fallback", output)
    assertTrue(events.none { it.type == AgentRunEventType.MODEL_TEXT_DELTA })
    assertEquals(1, fakeClient.streamingCallCount)
    assertEquals(1, fakeClient.nonStreamingCallCount)
  }
}

private class FakeStreamingClient(
  private val streams: List<List<StreamFrame>>,
  private val fallbackText: String,
  private val streamingUnsupported: Boolean = false,
  private val delayAfterFirstFrameMillis: Long = 0,
) : LLMClient() {
  var streamingCallCount: Int = 0
    private set
  var nonStreamingCallCount: Int = 0
    private set
  var nonStreamingChoiceCallCount: Int = 0
    private set

  override val clientName: String = "FakeStreamingClient"

  override fun llmProvider(): LLMProvider = LLMProvider.DeepSeek

  override suspend fun execute(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): List<Message.Response> {
    nonStreamingCallCount += 1
    return listOf(assistant(fallbackText))
  }

  override suspend fun executeMultipleChoices(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): List<LLMChoice> {
    nonStreamingCallCount += 1
    nonStreamingChoiceCallCount += 1
    return listOf(listOf(assistant(fallbackText)))
  }

  override fun executeStreaming(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): Flow<StreamFrame> = flow {
    streamingCallCount += 1
    if (streamingUnsupported) {
      throw IllegalStateException("Not implemented for this client")
    }
    val frames = streams.getOrElse(streamingCallCount - 1) {
      error("No fake streaming frames configured for call $streamingCallCount")
    }
    frames.forEachIndexed { index, frame ->
      emit(frame)
      if (index == 0 && delayAfterFirstFrameMillis > 0) {
        delay(delayAfterFirstFrameMillis)
      }
    }
  }

  override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
    throw UnsupportedOperationException("Fake client does not support moderation.")
  }

  override suspend fun models(): List<LLModel> = emptyList()

  override fun close() = Unit

  private fun assistant(text: String): Message.Assistant {
    return Message.Assistant(
      content = text,
      metaInfo = ResponseMetaInfo.Empty,
    )
  }
}
