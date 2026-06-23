package com.flovera.app.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteMultipleTools
import ai.koog.agents.core.dsl.extension.onMultipleAssistantMessages
import ai.koog.agents.core.dsl.extension.onMultipleToolCalls
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.result
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponses
import ai.koog.utils.io.use
import com.flovera.app.agent.AgentRunEvent
import com.flovera.app.agent.AgentRunGuidanceProvider
import com.flovera.app.agent.AgentRunEventSink
import com.flovera.app.agent.AgentRunEventType
import com.flovera.app.config.AGENT_ITERATIONS_INTERNAL_GUARD
import com.flovera.app.config.AppSettings
import com.flovera.app.config.agentAllowedSecretEnvironment
import com.flovera.app.session.AgentSession
import com.flovera.app.workspace.WorkspaceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

interface AgentRuntime {
  suspend fun run(
    input: String,
    agentRunId: String,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
    recorder: ToolEventRecorder,
  ): String

  suspend fun runStreaming(
    input: String,
    agentRunId: String,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
    recorder: ToolEventRecorder,
    eventSink: AgentRunEventSink,
    guidanceProvider: AgentRunGuidanceProvider = AgentRunGuidanceProvider.None,
  ): String {
    return run(
      input = input,
      agentRunId = agentRunId,
      settings = settings,
      session = session,
      workspace = workspace,
      recorder = recorder,
    )
  }
}

class KoogAgentRuntime(
  private val clientFactory: (ModelProviderSpec, String, AppSettings) -> LLMClient = ModelProviderCatalog::createClient,
) : AgentRuntime {
  override suspend fun run(
    input: String,
    agentRunId: String,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
    recorder: ToolEventRecorder,
  ): String {
    return runAgent(
      input = input,
      agentRunId = agentRunId,
      settings = settings,
      session = session,
      workspace = workspace,
      recorder = recorder,
      frameForwarder = null,
      streaming = false,
    )
  }

  override suspend fun runStreaming(
    input: String,
    agentRunId: String,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
    recorder: ToolEventRecorder,
    eventSink: AgentRunEventSink,
    guidanceProvider: AgentRunGuidanceProvider,
  ): String {
    val frameForwarder = AgentRunStreamFrameForwarder(eventSink)
    return try {
      runAgent(
        input = input,
        agentRunId = agentRunId,
        settings = settings,
        session = session,
        workspace = workspace,
        recorder = recorder,
        frameForwarder = frameForwarder,
        guidanceProvider = guidanceProvider,
        streaming = true,
      )
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      if (frameForwarder.modelTextDeltaCount == 0 && isStreamingUnsupported(error)) {
        run(
          input = input,
          agentRunId = agentRunId,
          settings = settings,
          session = session,
          workspace = workspace,
          recorder = recorder,
        )
      } else {
        throw error
      }
    }
  }

  private suspend fun runAgent(
    input: String,
    agentRunId: String,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
    recorder: ToolEventRecorder,
    frameForwarder: AgentRunStreamFrameForwarder?,
    guidanceProvider: AgentRunGuidanceProvider = AgentRunGuidanceProvider.None,
    streaming: Boolean,
  ): String {
    val provider = ModelProviderCatalog.requireProvider(settings.provider)
    val apiKey = settings.apiKeyFor(provider.id)
    require(apiKey.isNotBlank()) { "${provider.label} API key is not configured." }
    val webSearchAvailable = settings.networkEnabled && settings.webSearchEnabled && settings.braveSearchApiKey.isNotBlank()
    val secretEnvironment = settings.agentAllowedSecretEnvironment()
    val modelContext = ModelProviderCatalog.contextFor(settings)
    val client = clientFactory(provider, apiKey, settings)
    val requestContext = AgentRequestContextAssembler.build(
      input = input,
      settings = settings,
      session = session,
      workspace = workspace,
    )

    val agent = AIAgent(
      promptExecutor = MultiLLMPromptExecutor(client),
      llmModel = provider.createModel(settings.model, modelContext),
      strategy = if (streaming) {
        floveraStreamingSingleRunStrategy(frameForwarder, guidanceProvider)
      } else {
        singleRunStrategy()
      },
      toolRegistry = workspaceToolRegistry(
        workspace = workspace,
        recorder = recorder,
        networkEnabled = settings.networkEnabled,
        pythonRunToolFallbackEnabled = settings.pythonRunToolFallbackEnabled,
        authorityMode = settings.agentAuthorityMode,
        webSearchEnabled = webSearchAvailable,
        braveSearchApiKey = settings.braveSearchApiKey,
        secretEnvironment = secretEnvironment,
      ),
      systemPrompt = requestContext.systemPrompt,
      maxIterations = AGENT_ITERATIONS_INTERNAL_GUARD,
    )

    return agent.use {
      it.run(
        requestContext.userPrompt,
        sessionId = agentRunId,
      )
    }
  }

  private fun isStreamingUnsupported(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
      val message = current.message.orEmpty()
      if (
        current is UnsupportedOperationException ||
        (current is IllegalStateException && message.contains("Not implemented", ignoreCase = true))
      ) {
        return true
      }
      current = current.cause
    }
    return false
  }
}

private class AgentRunStreamFrameForwarder(
  private val delegate: AgentRunEventSink,
) {
  var modelTextDeltaCount: Int = 0
    private set
  private var currentResponseHasTextDelta: Boolean = false
  private var toolBoundaryEmittedForCurrentResponse: Boolean = false

  fun emitStreamFrame(frame: StreamFrame) {
    when (frame) {
      is StreamFrame.TextDelta -> {
        if (frame.text.isNotEmpty()) {
          modelTextDeltaCount += 1
          currentResponseHasTextDelta = true
          toolBoundaryEmittedForCurrentResponse = false
          delegate.emit(
            AgentRunEvent(
              type = AgentRunEventType.MODEL_TEXT_DELTA,
              modelTextDelta = frame.text,
            ),
          )
        }
      }
      is StreamFrame.ToolCallDelta,
      is StreamFrame.ToolCallComplete -> {
        if (currentResponseHasTextDelta && !toolBoundaryEmittedForCurrentResponse) {
          toolBoundaryEmittedForCurrentResponse = true
          currentResponseHasTextDelta = false
          delegate.emit(AgentRunEvent(type = AgentRunEventType.STREAM_BOUNDARY))
        }
      }
      else -> Unit
    }
  }

  fun emitMissingResponseText(frames: List<StreamFrame>, responses: List<Message.Response>) {
    if (frames.any { it is StreamFrame.ToolCallDelta || it is StreamFrame.ToolCallComplete }) return
    val frameText = frames.filterIsInstance<StreamFrame.TextDelta>().joinToString("") { it.text }
    val responseText = responses.joinToString("\n") { it.content }.trimEnd()
    if (responseText.isBlank() || responseText == frameText) return
    val missingText = if (frameText.isNotBlank() && responseText.startsWith(frameText)) {
      responseText.removePrefix(frameText)
    } else if (frameText.isBlank()) {
      responseText
    } else {
      return
    }
    if (missingText.isBlank()) return
    modelTextDeltaCount += 1
    delegate.emit(
      AgentRunEvent(
        type = AgentRunEventType.MODEL_TEXT_DELTA,
        modelTextDelta = missingText,
      ),
    )
  }
}

@OptIn(InternalAgentsApi::class)
private fun floveraStreamingSingleRunStrategy(
  frameForwarder: AgentRunStreamFrameForwarder?,
  guidanceProvider: AgentRunGuidanceProvider,
): AIAgentGraphStrategy<String, String> =
  strategy("flovera_streaming_single_run") {
    val nodeAppendUser by node<String, String>("append_user") { message ->
      llm.writeSession {
        appendPrompt {
          user(message)
        }
      }
      message
    }
    val nodeCallLLM by nodeLLMRequestStreamingAndSendResults("stream_llm", frameForwarder)
    val nodeExecuteTool by nodeExecuteMultipleTools(parallelTools = false)
    val nodeSendToolResult by nodeLLMSendMultipleToolResultsStreaming(
      name = "stream_after_tools",
      frameForwarder = frameForwarder,
      guidanceProvider = guidanceProvider,
    )

    edge(nodeStart forwardTo nodeAppendUser)
    edge(nodeAppendUser forwardTo nodeCallLLM)
    edge(nodeCallLLM forwardTo nodeExecuteTool onMultipleToolCalls { true })
    edge(
      nodeCallLLM forwardTo nodeFinish
        onMultipleAssistantMessages { true }
        transformed { messages -> messages.joinToString("\n") { message -> message.content } },
    )

    edge(nodeExecuteTool forwardTo nodeSendToolResult)
    edge(nodeSendToolResult forwardTo nodeExecuteTool onMultipleToolCalls { true })
    edge(
      nodeSendToolResult forwardTo nodeFinish
        onMultipleAssistantMessages { true }
        transformed { messages -> messages.joinToString("\n") { message -> message.content } },
    )
  }

@OptIn(InternalAgentsApi::class)
private fun nodeLLMRequestStreamingAndSendResults(
  name: String? = null,
  frameForwarder: AgentRunStreamFrameForwarder?,
) = node<String, List<Message.Response>>(name) {
  val frames = llm.writeSession {
    val collected = mutableListOf<StreamFrame>()
    requestLLMStreaming().collect { frame ->
      collected += frame
      frameForwarder?.emitStreamFrame(frame)
    }
    collected
  }
  val responses = frames.toMessageResponses()
  frameForwarder?.emitMissingResponseText(frames, responses)
  llm.writeSession {
    appendPrompt {
      messages(responses)
    }
  }
  responses
}

@OptIn(InternalAgentsApi::class)
private fun nodeLLMSendMultipleToolResultsStreaming(
  name: String? = null,
  frameForwarder: AgentRunStreamFrameForwarder?,
  guidanceProvider: AgentRunGuidanceProvider,
) = node<List<ReceivedToolResult>, List<Message.Response>>(name) { results ->
  val guidance = guidanceProvider.consumePendingGuidance()
  val frames = llm.writeSession {
    appendPrompt {
      tool {
        results.forEach { result(it) }
      }
      if (guidance.isNotEmpty()) {
        user(guidance.toModelGuidanceMessage())
      }
    }
    val collected = mutableListOf<StreamFrame>()
    requestLLMStreaming().collect { frame ->
      collected += frame
      frameForwarder?.emitStreamFrame(frame)
    }
    collected
  }
  val responses = frames.toMessageResponses()
  frameForwarder?.emitMissingResponseText(frames, responses)
  llm.writeSession {
    appendPrompt {
      messages(responses)
    }
  }
  responses
}

private fun List<String>.toModelGuidanceMessage(): String {
  return joinToString(separator = "\n\n") { guidance ->
    """
      User guidance received while this run was active:
      $guidance

      Apply this guidance now, after the tool result above, while continuing the current task. Do not wait for a separate follow-up run.
    """.trimIndent()
  }
}
