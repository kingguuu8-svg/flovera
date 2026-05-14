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
    val webSearchAvailable = settings.networkEnabled && settings.webSearchEnabled && settings.braveSearchApiKey.isNotBlank()

    val agent = AIAgent(
      promptExecutor = MultiLLMPromptExecutor(provider.createClient(apiKey)),
      llmModel = provider.createModel(settings.model),
      toolRegistry = workspaceToolRegistry(
        workspace = workspace,
        recorder = recorder,
        networkEnabled = settings.networkEnabled,
        webSearchEnabled = webSearchAvailable,
        braveSearchApiKey = settings.braveSearchApiKey,
      ),
      systemPrompt = buildSystemPrompt(
        agentRules = workspace.readAgentRules(),
        networkEnabled = settings.networkEnabled,
        webSearchAvailable = webSearchAvailable,
      ),
      maxIterations = settings.maxAgentIterations,
    )

    return agent.use {
      it.run(buildUserInput(input, session), sessionId = session.id)
    }
  }

  private fun buildSystemPrompt(agentRules: String, networkEnabled: Boolean, webSearchAvailable: Boolean): String {
    return """
      You are an Android-local workspace agent.
      You can talk with the user and use tools to inspect or modify the current workspace.
      Only create or edit files through the provided workspace tools.
      Keep all file paths relative to the workspace root.
      For web projects, prefer plain HTML, CSS, JS, and JSON files. Do not assume Python, npm, git, bash, or Linux tools exist.
      Workspace HTML is displayed inside flovera WebView and can call controlled app events through window.Flovera when available:
      - window.Flovera.toast("message")
      - window.Flovera.notify(JSON.stringify({ title: "Title", body: "Body" }))
      - window.Flovera.postEvent(JSON.stringify({ type: "notification", title: "Title", body: "Body" }))
      Always guard these calls with if (window.Flovera) and make the behavior clear in the UI.
      Flovera app metadata is exposed under .flovera/.
      - Read .flovera/settings-view.json to understand non-secret app settings.
      - Read .flovera/capabilities.json to understand available app capabilities.
      - Do not edit app behavior directly. If you need an app setting changed, write a JSON proposal under .flovera/proposals/.
      - Proposal schema: {"type":"settings","title":"Short title","reason":"Why this helps","changes":{"themeColor":"#76C4D8","networkEnabled":true,"selectedHtmlPath":"index.html","maxAgentIterations":30}}
      Network tools are ${if (networkEnabled) "enabled. Use fetch_url and download_file only when they directly help the user's request." else "disabled for this run."}
      Web search is ${if (webSearchAvailable) "enabled through web_search. Use it when current public information is needed." else "disabled for this run."}
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
