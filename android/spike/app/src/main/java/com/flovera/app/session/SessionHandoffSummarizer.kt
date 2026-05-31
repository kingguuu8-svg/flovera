package com.flovera.app.session

import com.flovera.app.agent.InterruptedRunHandoff

object SessionHandoffSummarizer {
  fun summarize(
    session: AgentSession,
    record: ContextUsageRecord,
    interruptedRun: InterruptedRunHandoff? = null,
  ): String {
    val messages = session.messages
      .filterNot { it.role == SESSION_ROLE_COMPRESSION }
    val recent = messages.takeLast(12)
    val userRequests = recent
      .filter { it.role == "user" }
      .takeLast(5)
      .map { "- ${oneLine(it.content, 180)}" }
    val assistantResults = recent
      .filter { it.role == "assistant" || it.role == "error" }
      .takeLast(5)
      .map { "- ${it.role}: ${oneLine(it.content, 180)}" }
    val toolEvents = recent
      .flatMap { it.toolEvents }
      .takeLast(8)
      .map { "- ${it.name}: ${oneLine(it.result, 160)}" }

    return buildString {
      appendLine("# Handoff Summary")
      appendLine()
      appendLine("This summary was generated locally so the next context can continue without replaying the full history.")
      appendLine()
      appendLine("## Session")
      appendLine()
      appendLine("- title: ${oneLine(session.title, 120)}")
      appendLine("- messagesBeforeCompression: ${messages.size}")
      appendLine("- provider: ${record.provider.ifBlank { "unknown" }}")
      appendLine("- model: ${record.model.ifBlank { "unknown" }}")
      appendLine("- approximateTokens: ${record.approximateTokens}")
      appendLine("- budgetStatus: ${record.contextBudgetStatus}")
      appendLine()
      appendLine("## Recent User Requests")
      appendLine()
      if (userRequests.isEmpty()) appendLine("- none") else userRequests.forEach(::appendLine)
      appendLine()
      appendLine("## Recent Assistant Results")
      appendLine()
      if (assistantResults.isEmpty()) appendLine("- none") else assistantResults.forEach(::appendLine)
      appendLine()
      appendLine("## Recent Tool Activity")
      appendLine()
      if (toolEvents.isEmpty()) appendLine("- none") else toolEvents.forEach(::appendLine)
      if (interruptedRun != null) {
        appendLine()
        appendLine("## Interrupted Run State")
        appendLine()
        appendLine("- recoveryMode: retry the same interrupted run")
        appendLine("- failureStage: ${oneLine(interruptedRun.failureStage, 160)}")
        appendLine("- providerError: ${oneLine(interruptedRun.providerError, 240)}")
        appendLine("- originalInput: ${oneLine(interruptedRun.originalInput, 240)}")
        appendLine("- assistantDraft: ${oneLine(interruptedRun.assistantDraft, 240)}")
        appendLine("- recoveryInstruction: ${oneLine(interruptedRun.recoveryInstruction, 240)}")
        appendLine()
        appendLine("## Interrupted Tool Activity")
        appendLine()
        val interruptedTools = interruptedRun.toolEvents
          .takeLast(8)
          .map { "- ${it.name}: ${oneLine(it.result, 180)}" }
        if (interruptedTools.isEmpty()) appendLine("- none") else interruptedTools.forEach(::appendLine)
      }
    }.trim()
  }

  private fun oneLine(value: String, maxChars: Int): String {
    val normalized = value
      .lineSequence()
      .joinToString(" ") { it.trim() }
      .replace(Regex("\\s+"), " ")
      .trim()
    if (normalized.length <= maxChars) return normalized
    return normalized.take(maxChars).trimEnd() + "..."
  }
}
