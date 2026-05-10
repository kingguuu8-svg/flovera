package com.flovera.app.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.utils.io.use
import com.flovera.app.config.AppSettings
import com.flovera.app.session.AgentSession
import com.flovera.app.workspace.WorkspaceManager

interface AgentRuntime {
  suspend fun run(
    input: String,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
    recorder: ToolEventRecorder,
  ): String
}

class KoogAgentRuntime : AgentRuntime {
  override suspend fun run(
    input: String,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
    recorder: ToolEventRecorder,
  ): String {
    val provider = ModelProviderCatalog.requireProvider(settings.provider)
    val apiKey = settings.apiKeyFor(provider.id)
    require(apiKey.isNotBlank()) { "${provider.label} API key is not configured." }

    val agent = AIAgent(
      promptExecutor = MultiLLMPromptExecutor(provider.createClient(apiKey)),
      llmModel = provider.createModel(settings.model),
      toolRegistry = workspaceToolRegistry(workspace, recorder, networkEnabled = settings.networkEnabled),
      systemPrompt = buildSystemPrompt(workspace.readAgentRules(), settings.networkEnabled),
      maxIterations = settings.maxAgentIterations,
    )

    return agent.use {
      it.run(buildUserInput(input, session), sessionId = session.id)
    }
  }

  private fun buildSystemPrompt(agentRules: String, networkEnabled: Boolean): String {
    return """
      You are an Android-local workspace agent.
      You can talk with the user and use tools to inspect or modify the current workspace.
      Only create or edit files through the provided workspace tools.
      Keep all file paths relative to the workspace root.
      For web projects, prefer plain HTML, CSS, JS, and JSON files. Do not assume Python, npm, git, bash, or Linux tools exist.
      Network tools are ${if (networkEnabled) "enabled. Use fetch_url and download_file only when they directly help the user's request." else "disabled for this run."}
      When the user asks you to create files, call the tools and then summarize the files changed.

      Workspace AGENT.md:
      ${agentRules.ifBlank { "(empty)" }}
    """.trimIndent()
  }

  private fun buildUserInput(input: String, session: AgentSession): String {
    val history = session.messages.takeLast(12).joinToString("\n") { message ->
      "${message.role}: ${message.content.take(1_500)}"
    }
    return """
      Recent session history:
      ${history.ifBlank { "(empty)" }}

      Current user request:
      $input
    """.trimIndent()
  }
}
