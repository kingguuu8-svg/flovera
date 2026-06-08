package com.flovera.app.session

import java.security.MessageDigest

object PromptContextLedger {
  const val KIND_USER_MESSAGE = "user_message"
  const val KIND_PRIMARY_RESPONSE = "primary_response"
  const val KIND_RUN_CONTEXT = "run_context"
  const val KIND_DETAIL_CONTEXT_RAW = "detail_context_raw"
  const val KIND_DETAIL_CONTEXT_SUMMARY = "detail_context_summary"
  const val KIND_ASSISTANT_SUMMARY = "assistant_summary"

  private const val DETAIL_SUMMARY_DISTANCE_THRESHOLD = 2
  private const val COMPRESSION_RESTART_RECENT_RUN_THRESHOLD = 3
  const val ASSISTANT_SUMMARY_TRIGGER_USAGE_PERMILLE = 900

  data class RunRecord(
    val index: Int,
    val userMessageIndex: Int?,
    val messageIndexes: List<Int>,
    val primaryMessageIndexes: List<Int>,
    val hasCompressionDivider: Boolean,
  )

  data class AssistantSummaryCandidate(
    val run: RunRecord,
    val userContent: String,
    val assistantRole: String,
    val assistantContent: String,
    val sourceMessageIndex: Int,
  )

  fun runIndexForMessage(existingMessages: List<SessionMessage>, message: SessionMessage): Int {
    return when (message.role) {
      "user" -> existingMessages.count { it.role == "user" }
      else -> (existingMessages.count { it.role == "user" } - 1).coerceAtLeast(0)
    }
  }

  fun withBackfilledBlocks(session: AgentSession): List<PromptContextBlock> {
    val existing = session.promptContextBlocks
    val synthetic = existing.filter { it.kind == KIND_ASSISTANT_SUMMARY }
    val messageDerived = if (existing.all(::isCurrentSchemaBlock)) {
      existing.filter { it.kind != KIND_ASSISTANT_SUMMARY }
    } else {
      emptyList()
    }
    val rebuilt = rebuildMessageDerivedBlocks(session.messages)
    val merged = if (messageDerived.isEmpty()) rebuilt else mergeMessageDerivedBlocks(messageDerived, rebuilt)
    return merged + synthetic
  }

  fun blocksForMessage(
    message: SessionMessage,
    sourceMessageIndex: Int,
    runIndex: Int,
  ): List<PromptContextBlock> {
    if (message.role == SESSION_ROLE_COMPRESSION) return emptyList()
    if (message.role == "user") {
      return listOf(
        newBlock(
          sourceMessageIndex = sourceMessageIndex,
          sourceTimestampMillis = message.timestampMillis,
          runIndex = runIndex,
          role = "user",
          content = message.content,
          origin = "message",
          kind = KIND_USER_MESSAGE,
        ),
      )
    }

    val blocks = mutableListOf<PromptContextBlock>()
    if ((message.role == "assistant" || message.role == "error") && message.content.isNotEmpty()) {
      blocks += newBlock(
        sourceMessageIndex = sourceMessageIndex,
        sourceTimestampMillis = message.timestampMillis,
        runIndex = runIndex,
        role = message.role,
        content = message.content,
        origin = "message",
        kind = KIND_PRIMARY_RESPONSE,
      )
    }

    buildRunContext(message).takeIf { it.isNotBlank() }?.let { content ->
      blocks += newBlock(
        sourceMessageIndex = sourceMessageIndex,
        sourceTimestampMillis = message.timestampMillis,
        runIndex = runIndex,
        role = "run_context",
        content = content,
        origin = "run",
        kind = KIND_RUN_CONTEXT,
      )
    }

    val detailRaw = buildDetailContextRaw(message)
    if (detailRaw.isNotBlank()) {
      val rawBlock = newBlock(
        sourceMessageIndex = sourceMessageIndex,
        sourceTimestampMillis = message.timestampMillis,
        runIndex = runIndex,
        role = "detail_context",
        content = detailRaw,
        origin = "detail",
        kind = KIND_DETAIL_CONTEXT_RAW,
      )
      blocks += rawBlock
      buildDetailContextSummary(message).takeIf { it.isNotBlank() }?.let { summary ->
        blocks += newBlock(
          sourceMessageIndex = sourceMessageIndex,
          sourceTimestampMillis = message.timestampMillis,
          runIndex = runIndex,
          role = "detail_context_summary",
          content = summary,
          origin = "detail",
          kind = KIND_DETAIL_CONTEXT_SUMMARY,
          summaryOfBlockId = rawBlock.id,
        )
      }
    }

    return blocks
  }

  fun buildAssistantSummaryBlock(
    sourceMessageIndex: Int,
    runIndex: Int,
    role: String,
    summary: String,
    sourceTimestampMillis: Long,
  ): PromptContextBlock {
    return newBlock(
      sourceMessageIndex = sourceMessageIndex,
      sourceTimestampMillis = sourceTimestampMillis,
      runIndex = runIndex,
      role = role,
      content = summary,
      origin = "summary",
      kind = KIND_ASSISTANT_SUMMARY,
    )
  }

  fun promptBlocks(
    session: AgentSession,
    currentInput: String = "",
    currentVisibleInput: String = currentInput,
    @Suppress("UNUSED_PARAMETER")
    maxMessages: Int,
  ): List<PromptContextBlock> {
    val excludedIndexes = currentInputExcludedMessageIndexes(session.messages, currentInput, currentVisibleInput)
    val blocks = withBackfilledBlocks(session)
      .filterNot { it.sourceMessageIndex in excludedIndexes }
    if (blocks.isEmpty()) return emptyList()

    val runs = deriveRuns(session.messages, excludedIndexes)
    if (runs.isEmpty()) return emptyList()
    val latestCompressionRunIndex = runs.lastOrNull { it.hasCompressionDivider }?.index
    val blocksByRun = runs.associateWith { run ->
      blocks.filter { it.sourceMessageIndex in run.messageIndexes }
    }

    return buildList {
      runs.forEachIndexed { ordinal, run ->
        val runBlocks = blocksByRun[run].orEmpty()
        val beforeCompression = latestCompressionRunIndex != null && run.index < latestCompressionRunIndex
        val keepRecentCompressionDetails = beforeCompression &&
          (latestCompressionRunIndex - run.index) <= COMPRESSION_RESTART_RECENT_RUN_THRESHOLD
        val distanceFromNewest = runs.lastIndex - ordinal

        addAll(runBlocks.filter { it.kind == KIND_USER_MESSAGE })

        val assistantSummary = runBlocks.filter { it.kind == KIND_ASSISTANT_SUMMARY }
        val primaryResponse = runBlocks.filter { it.kind == KIND_PRIMARY_RESPONSE }
        if (beforeCompression && !keepRecentCompressionDetails && assistantSummary.isNotEmpty()) {
          addAll(assistantSummary)
        } else {
          addAll(primaryResponse)
        }

        val includeRunContext = !beforeCompression || keepRecentCompressionDetails
        if (includeRunContext) {
          addAll(runBlocks.filter { it.kind == KIND_RUN_CONTEXT })
        }

        when {
          beforeCompression && keepRecentCompressionDetails -> {
            addAll(runBlocks.filter { it.kind == KIND_DETAIL_CONTEXT_RAW })
          }
          beforeCompression -> Unit
          distanceFromNewest > DETAIL_SUMMARY_DISTANCE_THRESHOLD -> {
            val detailSummary = runBlocks.filter { it.kind == KIND_DETAIL_CONTEXT_SUMMARY }
            if (detailSummary.isNotEmpty()) {
              addAll(detailSummary)
            } else {
              addAll(runBlocks.filter { it.kind == KIND_DETAIL_CONTEXT_RAW })
            }
          }
          else -> {
            addAll(runBlocks.filter { it.kind == KIND_DETAIL_CONTEXT_RAW })
          }
        }
      }
    }
  }

  fun assistantSummaryCandidates(
    session: AgentSession,
    currentInput: String = "",
    currentVisibleInput: String = currentInput,
  ): List<AssistantSummaryCandidate> {
    val excludedIndexes = currentInputExcludedMessageIndexes(session.messages, currentInput, currentVisibleInput)
    val runs = deriveRuns(session.messages, excludedIndexes)
    val latestCompressionRunIndex = runs.lastOrNull { it.hasCompressionDivider }?.index ?: return emptyList()
    val blocks = withBackfilledBlocks(session)
    return runs
      .filter { run ->
        run.index < latestCompressionRunIndex &&
          (latestCompressionRunIndex - run.index) > COMPRESSION_RESTART_RECENT_RUN_THRESHOLD
      }
      .sortedBy { it.index }
      .mapNotNull { run ->
        val primaryMessageIndex = run.primaryMessageIndexes.lastOrNull() ?: return@mapNotNull null
        val primaryMessage = session.messages.getOrNull(primaryMessageIndex) ?: return@mapNotNull null
        if ((primaryMessage.role != "assistant" && primaryMessage.role != "error") || primaryMessage.content.isBlank()) {
          return@mapNotNull null
        }
        val alreadySummarized = blocks.any {
          it.sourceMessageIndex == primaryMessageIndex && it.kind == KIND_ASSISTANT_SUMMARY
        }
        if (alreadySummarized) return@mapNotNull null
        AssistantSummaryCandidate(
          run = run,
          userContent = session.messages.getOrNull(run.userMessageIndex ?: -1)?.content.orEmpty(),
          assistantRole = primaryMessage.role,
          assistantContent = primaryMessage.content,
          sourceMessageIndex = primaryMessageIndex,
        )
      }
  }

  private fun mergeMessageDerivedBlocks(
    existing: List<PromptContextBlock>,
    rebuilt: List<PromptContextBlock>,
  ): List<PromptContextBlock> {
    val existingKeys = existing.map { it.sourceMessageIndex to it.kind }.toSet()
    val missing = rebuilt.filter { (it.sourceMessageIndex to it.kind) !in existingKeys }
    return existing + missing
  }

  private fun rebuildMessageDerivedBlocks(messages: List<SessionMessage>): List<PromptContextBlock> {
    val runs = deriveRuns(messages)
    val runIndexByMessageIndex = runs.flatMap { run ->
      run.messageIndexes.map { index -> index to run.index }
    }.toMap()
    return messages.flatMapIndexed { index, message ->
      val runIndex = runIndexByMessageIndex[index] ?: runIndexForMessage(messages.take(index), message)
      blocksForMessage(message, index, runIndex)
    }
  }

  private fun isCurrentSchemaBlock(block: PromptContextBlock): Boolean {
    return block.kind.isNotBlank() && block.runIndex >= 0
  }

  private fun deriveRuns(
    messages: List<SessionMessage>,
    excludedIndexes: Set<Int> = emptySet(),
  ): List<RunRecord> {
    data class MutableRun(
      val index: Int,
      var userMessageIndex: Int? = null,
      val messageIndexes: MutableList<Int> = mutableListOf(),
      val primaryMessageIndexes: MutableList<Int> = mutableListOf(),
      var hasCompressionDivider: Boolean = false,
    )

    val runs = mutableListOf<MutableRun>()
    var current: MutableRun? = null
    var nextRunIndex = 0
    messages.forEachIndexed { index, message ->
      if (index in excludedIndexes) return@forEachIndexed
      if (message.role == "user") {
        current = MutableRun(index = nextRunIndex++, userMessageIndex = index).also { runs += it }
      } else if (current == null) {
        current = MutableRun(index = nextRunIndex++).also { runs += it }
      }
      val activeRun = current ?: return@forEachIndexed
      activeRun.messageIndexes += index
      when (message.role) {
        SESSION_ROLE_COMPRESSION -> activeRun.hasCompressionDivider = true
        "assistant", "error" -> activeRun.primaryMessageIndexes += index
      }
    }
    return runs.map { run ->
      RunRecord(
        index = run.index,
        userMessageIndex = run.userMessageIndex,
        messageIndexes = run.messageIndexes.toList(),
        primaryMessageIndexes = run.primaryMessageIndexes.toList(),
        hasCompressionDivider = run.hasCompressionDivider,
      )
    }
  }

  private fun currentInputExcludedMessageIndexes(
    messages: List<SessionMessage>,
    currentInput: String,
    currentVisibleInput: String,
  ): Set<Int> {
    val normalizedInput = currentInput.trim()
    val normalizedVisibleInput = currentVisibleInput.trim()
    if (normalizedInput.isBlank() && normalizedVisibleInput.isBlank()) return emptySet()
    val lastIndex = messages.lastIndex
    val last = messages.getOrNull(lastIndex) ?: return emptySet()
    if (last.role != "user") return emptySet()
    val lastContent = last.content.trim()
    val isCurrentInput = lastContent == normalizedInput ||
      lastContent == normalizedVisibleInput ||
      (lastContent.isNotBlank() && normalizedInput.contains(lastContent))
    return if (isCurrentInput) setOf(lastIndex) else emptySet()
  }

  private fun buildRunContext(message: SessionMessage): String {
    val sections = mutableListOf<String>()
    val events = message.runEvents
      .filter { it.isPromptRelevantRunEvent() }
      .map { event ->
        when {
          event.detail.isNotBlank() -> "${event.type}:${event.title}:${event.detail}"
          event.title.isNotBlank() -> "${event.type}:${event.title}"
          else -> event.type
        }
      }
    if (events.isNotEmpty()) {
      sections += "events: ${events.joinToString(" | ")}"
    }

    val promotedTranscript = message.transcriptEvents
      .filter { it.isPromptRelevantRunTranscriptEvent() }
      .map { event ->
        when {
          event.content.isNotBlank() -> "${event.type}:${event.role.ifBlank { "status" }}:${event.content}"
          event.detail.isNotBlank() -> "${event.type}:${event.title}:${event.detail}"
          event.title.isNotBlank() -> "${event.type}:${event.title}"
          else -> event.type
        }
      }
    if (promotedTranscript.isNotEmpty()) {
      sections += "transcript: ${promotedTranscript.joinToString(" | ")}"
    }

    val promotedTools = message.toolEvents
      .filter(::isPromotedToRunContext)
      .map(::formatPromotedToolEvent)
    if (promotedTools.isNotEmpty()) {
      sections += buildString {
        appendLine("promoted_tools:")
        promotedTools.forEach { appendLine("- $it") }
      }.trimEnd()
    }

    return sections.joinToString("\n")
  }

  private fun buildDetailContextRaw(message: SessionMessage): String {
    val transcript = message.transcriptEvents
      .filter { it.isDetailTranscriptEvent() }
      .map { event ->
        when {
          event.content.isNotBlank() -> "${event.type}:${event.role.ifBlank { "assistant" }}:${event.content}"
          event.detail.isNotBlank() -> "${event.type}:${event.title}:${event.detail}"
          event.title.isNotBlank() -> "${event.type}:${event.title}"
          else -> event.type
        }
      }
    val tools = message.toolEvents
      .filterNot(::isPromotedToRunContext)
      .map(::formatDetailToolEvent)
    return buildString {
      if (transcript.isNotEmpty()) {
        appendLine("transcript:")
        transcript.forEach { appendLine("- $it") }
      }
      if (tools.isNotEmpty()) {
        appendLine("tools:")
        tools.forEach { appendLine("- $it") }
      }
    }.trim()
  }

  private fun buildDetailContextSummary(message: SessionMessage): String {
    val detailTools = message.toolEvents.filterNot(::isPromotedToRunContext)
    val detailTranscript = message.transcriptEvents.filter { it.isDetailTranscriptEvent() }
    if (detailTools.isEmpty() && detailTranscript.isEmpty()) return ""

    val artifacts = linkedSetOf<String>()
    val facts = linkedSetOf<String>()
    val changes = linkedSetOf<String>()
    val verification = linkedSetOf<String>()
    val resumeHandles = linkedSetOf<String>()
    val pending = linkedSetOf<String>()

    detailTools.forEach { event ->
      extractArtifacts(event).forEach { artifacts += it }
      when (event.resultKind) {
        "file_read", "search", "network" -> facts += compactOneLine(event.result, 220)
        "file_write" -> changes += "${event.name}: ${compactOneLine(event.args, 160)}"
        "artifact_validation", "command" -> verification += "${event.name}: ${compactOneLine(event.result, 220)}"
        else -> facts += "${event.name}: ${compactOneLine(event.result, 180)}"
      }
      resumeHandles += "${event.name}: ${compactOneLine(event.args, 180)}"
      if (!event.success) {
        pending += "${event.name}: ${compactOneLine(event.result, 220)}"
      }
    }

    detailTranscript.forEach { event ->
      when (event.type) {
        "assistant_text" -> facts += compactOneLine(event.content, 180)
        "tool_call" -> resumeHandles += compactOneLine(event.detail.ifBlank { event.title }, 180)
        else -> pending += compactOneLine(event.content.ifBlank { event.detail.ifBlank { event.title } }, 180)
      }
    }

    return buildString {
      appendSummaryLine(this, "artifacts", artifacts)
      appendSummaryLine(this, "facts", facts)
      appendSummaryLine(this, "changes", changes)
      appendSummaryLine(this, "verification", verification)
      appendSummaryLine(this, "resume_handles", resumeHandles)
      appendSummaryLine(this, "pending", pending)
    }.trim()
  }

  private fun appendSummaryLine(
    builder: StringBuilder,
    label: String,
    values: LinkedHashSet<String>,
  ) {
    val normalized = values
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .distinct()
      .take(4)
    if (normalized.isEmpty()) return
    builder.append(label)
    builder.append(": ")
    builder.append(normalized.joinToString(" | "))
    builder.appendLine()
  }

  private fun isPromotedToRunContext(event: ToolEvent): Boolean {
    return !event.success || event.resultKind == "skill_read"
  }

  private fun formatPromotedToolEvent(event: ToolEvent): String {
    return buildString {
      append("tool=")
      append(event.name)
      append(", kind=")
      append(event.resultKind)
      append(", args=")
      append(event.args)
      append(", result=")
      append(event.result)
    }
  }

  private fun formatDetailToolEvent(event: ToolEvent): String {
    return buildString {
      append("tool=")
      append(event.name)
      append(", kind=")
      append(event.resultKind)
      append(", args=")
      append(event.args)
      append(", result=")
      append(event.result)
    }
  }

  private fun extractArtifacts(event: ToolEvent): List<String> {
    val values = linkedSetOf<String>()
    Regex("""(?:^|[, \[])(?:path|manifestPath|previewPath|inputPath|output|file|scriptPath)=([^,\]\s]+)""")
      .findAll(event.args)
      .forEach { values += it.groupValues[1] }
    Regex("""[\w./-]+\.(?:kt|kts|md|txt|json|xml|html|js|css|py|groovy|yaml|yml|java|sh)""")
      .findAll(event.args)
      .forEach { values += it.value }
    return values.toList()
  }

  private fun ConversationTranscriptEvent.isPromptRelevantRunTranscriptEvent(): Boolean {
    return when (type) {
      "run_failed",
      "run_interrupted",
      "guidance",
      "user_guidance",
      "user_text" -> true
      else -> false
    }
  }

  private fun ConversationTranscriptEvent.isDetailTranscriptEvent(): Boolean {
    return when (type) {
      "assistant_text",
      "error_text",
      "tool_call",
      "tool_omitted" -> true
      else -> false
    }
  }

  private fun AgentRunTimelineEvent.isPromptRelevantRunEvent(): Boolean {
    return when (type) {
      "context_checkpoint",
      "run_failed",
      "run_interrupted",
      "compression_started",
      "compression_completed" -> true
      else -> false
    }
  }

  private fun compactOneLine(value: String, maxChars: Int): String {
    val normalized = value
      .lineSequence()
      .joinToString(" ") { it.trim() }
      .replace(Regex("\\s+"), " ")
      .trim()
    if (normalized.length <= maxChars) return normalized
    return normalized.take(maxChars).trimEnd() + "..."
  }

  private fun newBlock(
    sourceMessageIndex: Int,
    sourceTimestampMillis: Long,
    runIndex: Int,
    role: String,
    content: String,
    origin: String,
    kind: String,
    summaryOfBlockId: String = "",
  ): PromptContextBlock {
    return PromptContextBlock(
      id = "m$sourceMessageIndex-$runIndex-$kind-${stableHash(content)}",
      sourceMessageIndex = sourceMessageIndex,
      sourceTimestampMillis = sourceTimestampMillis,
      runIndex = runIndex,
      role = role,
      content = content,
      origin = origin,
      kind = kind,
      summaryOfBlockId = summaryOfBlockId,
    )
  }

  private fun stableHash(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { "%02x".format(it) }
  }
}
