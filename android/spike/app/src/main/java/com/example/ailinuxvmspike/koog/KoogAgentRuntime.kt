package com.example.ailinuxvmspike.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.utils.io.use
import com.example.ailinuxvmspike.config.AppSettings
import com.example.ailinuxvmspike.session.AgentSession
import com.example.ailinuxvmspike.workspace.WorkspaceManager

class KoogAgentRuntime {
  suspend fun run(
    input: String,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
    recorder: ToolEventRecorder,
  ): String {
    require(settings.provider == "deepseek") { "Only DeepSeek is enabled in this phase." }
    require(settings.model == "deepseek-v4-pro") { "Only deepseek-v4-pro is enabled in this phase." }
    require(settings.apiKey.isNotBlank()) { "DeepSeek API key is not configured." }

    val agent = AIAgent(
      promptExecutor = MultiLLMPromptExecutor(DeepSeekLLMClient(settings.apiKey)),
      llmModel = deepSeekV4Pro,
      toolRegistry = workspaceToolRegistry(workspace, recorder),
      systemPrompt = buildSystemPrompt(workspace.readAgentRules()),
      maxIterations = settings.maxAgentIterations,
    )

    return agent.use {
      it.run(buildUserInput(input, session), sessionId = session.id)
    }
  }

  private fun buildSystemPrompt(agentRules: String): String {
    return """
      You are an Android-local workspace agent.
      You can talk with the user and use tools to inspect or modify the current workspace.
      Only create or edit files through the provided workspace tools.
      Keep all file paths relative to the workspace root.
      For web projects, prefer plain HTML, CSS, JS, and JSON files. Do not assume Python, npm, git, bash, or Linux tools exist.
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

  private val deepSeekV4Pro = LLModel(
    provider = LLMProvider.DeepSeek,
    id = "deepseek-v4-pro",
    capabilities = listOf(
      LLMCapability.Completion,
      LLMCapability.Temperature,
      LLMCapability.Tools,
      LLMCapability.ToolChoice,
      LLMCapability.Schema.JSON.Basic,
      LLMCapability.Schema.JSON.Standard,
      LLMCapability.MultipleChoices,
      LLMCapability.Thinking,
    ),
    contextLength = 1_000_000,
    maxOutputTokens = 384_000,
  )
}
