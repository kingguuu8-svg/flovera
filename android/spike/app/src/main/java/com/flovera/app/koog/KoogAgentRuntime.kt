package com.flovera.app.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.utils.io.use
import com.flovera.app.config.AGENT_ITERATIONS_INTERNAL_GUARD
import com.flovera.app.config.AppSettings
import com.flovera.app.agent.AgentRunEventSink
import com.flovera.app.session.AgentSession
import com.flovera.app.workspace.WorkspaceManager

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

class KoogAgentRuntime : AgentRuntime {
  override suspend fun run(
    input: String,
    agentRunId: String,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
    recorder: ToolEventRecorder,
  ): String {
    val provider = ModelProviderCatalog.requireProvider(settings.provider)
    val apiKey = settings.apiKeyFor(provider.id)
    require(apiKey.isNotBlank()) { "${provider.label} API key is not configured." }
    val webSearchAvailable = settings.networkEnabled && settings.webSearchEnabled && settings.braveSearchApiKey.isNotBlank()
    val modelContext = ModelProviderCatalog.contextFor(settings)
    val workspaceUserRules = workspace.readAgentRules()

    val agent = AIAgent(
      promptExecutor = MultiLLMPromptExecutor(ModelProviderCatalog.createClient(provider, apiKey, settings)),
      llmModel = provider.createModel(settings.model, modelContext),
      toolRegistry = workspaceToolRegistry(
        workspace = workspace,
        recorder = recorder,
        networkEnabled = settings.networkEnabled,
        webSearchEnabled = webSearchAvailable,
        braveSearchApiKey = settings.braveSearchApiKey,
      ),
      systemPrompt = AgentPromptBuilder.systemPrompt(
        networkEnabled = settings.networkEnabled,
        webSearchAvailable = webSearchAvailable,
        authorityMode = settings.agentAuthorityMode,
      ),
      maxIterations = AGENT_ITERATIONS_INTERNAL_GUARD,
    )

    return agent.use {
      it.run(
        AgentPromptBuilder.userInput(
          input = input,
          session = session,
          workspaceUserRules = workspaceUserRules,
        ),
        sessionId = agentRunId,
      )
    }
  }
}
