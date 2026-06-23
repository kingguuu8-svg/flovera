package com.flovera.app.koog

import com.flovera.app.config.AppSettings
import com.flovera.app.config.agentVisibleSecretRefs
import com.flovera.app.session.AgentSession
import com.flovera.app.session.RuntimeHistoryEntry
import com.flovera.app.session.RuntimeSessionHistory
import com.flovera.app.workspace.WorkspaceManager

data class AgentRequestContext(
  val systemPrompt: String,
  val userPrompt: String,
  val workspaceUserRules: String,
  val historyEntries: List<RuntimeHistoryEntry>,
  val workspaceMemory: String,
  val skillDescriptors: String,
  val secretRefs: String,
) {
  val historyChars: Int = historyEntries.sumOf { it.role.length + it.content.length + 2 }
  val workspaceMemoryChars: Int = workspaceMemory.length
}

object AgentRequestContextAssembler {
  fun build(
    input: String,
    currentVisibleInput: String = input,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
  ): AgentRequestContext {
    val webSearchAvailable = settings.networkEnabled && settings.webSearchEnabled && settings.braveSearchApiKey.isNotBlank()
    val workspaceUserRules = workspace.readAgentRules()
    val historyEntries = RuntimeSessionHistory.entries(
      session = session,
      currentInput = input,
      currentVisibleInput = currentVisibleInput,
    )
    val workspaceMemory = if (settings.workspaceMemoryEnabled) {
      workspace.readFloveraMemory()
    } else {
      ""
    }
    val skillDescriptors = workspace.readFloveraSkillPromptDescriptors()
    val secretRefs = settings.agentVisibleSecretRefs().joinToString("\n") { secret ->
      "- ${secret.normalizedName}: ${secret.displayLabel}"
    }
    return AgentRequestContext(
      systemPrompt = AgentPromptBuilder.systemPrompt(
        networkEnabled = settings.networkEnabled,
        webSearchAvailable = webSearchAvailable,
        authorityMode = settings.agentAuthorityMode,
        pythonRunToolFallbackEnabled = settings.pythonRunToolFallbackEnabled,
        workspaceMemoryEnabled = settings.workspaceMemoryEnabled,
        workspaceUserRules = workspaceUserRules,
      ),
      userPrompt = AgentPromptBuilder.userInput(
        input = input,
        session = session,
        workspaceUserRules = workspaceUserRules,
        currentVisibleInput = currentVisibleInput,
        floveraSkillDescriptors = skillDescriptors,
        secretRefs = secretRefs,
        workspaceMemoryEnabled = settings.workspaceMemoryEnabled,
        workspaceMemory = workspaceMemory,
        historyEntries = historyEntries,
      ),
      workspaceUserRules = workspaceUserRules,
      historyEntries = historyEntries,
      workspaceMemory = workspaceMemory,
      skillDescriptors = skillDescriptors,
      secretRefs = secretRefs,
    )
  }
}
