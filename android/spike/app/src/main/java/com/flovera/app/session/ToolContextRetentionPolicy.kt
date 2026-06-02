package com.flovera.app.session

data class ToolRetentionDecision(
  val success: Boolean,
  val resultKind: String,
  val retentionPriority: String,
  val retentionReason: String,
)

object ToolContextRetentionPolicy {
  const val RETENTION_ACTIVE_CRITICAL = "active_critical"
  const val RETENTION_RECENT_FULL = "recent_full"
  const val RETENTION_STRUCTURED_MEMORY = "structured_memory"
  const val RETENTION_SUMMARY_ONLY = "summary_only"
  const val RETENTION_UI_ONLY = "ui_only"

  private const val ACTIVE_CRITICAL_LIMIT = 2_200
  private const val RECENT_FULL_LIMIT = 1_400
  private const val STRUCTURED_MEMORY_LIMIT = 700
  private const val SUMMARY_LIMIT = 360
  private const val ARG_LIMIT = 420
  private const val RECENT_FULL_MESSAGE_DISTANCE = 1

  fun classify(name: String, args: String, result: String): ToolRetentionDecision {
    val normalizedName = name.lowercase()
    val success = !looksLikeFailure(result)
    if (!success) {
      return ToolRetentionDecision(
        success = false,
        resultKind = kindFor(normalizedName),
        retentionPriority = RETENTION_ACTIVE_CRITICAL,
        retentionReason = "failed tool output must remain available for retry and diagnosis",
      )
    }

    val kind = kindFor(normalizedName)
    val priority = when (kind) {
      "file_write",
      "artifact_validation" -> RETENTION_STRUCTURED_MEMORY
      "command",
      "file_read",
      "search",
      "network" -> RETENTION_RECENT_FULL
      "status" -> RETENTION_UI_ONLY
      else -> RETENTION_SUMMARY_ONLY
    }
    return ToolRetentionDecision(
      success = true,
      resultKind = kind,
      retentionPriority = priority,
      retentionReason = reasonFor(priority, kind, args),
    )
  }

  fun slicesForMessage(message: SessionMessage, distanceFromNewestMessage: Int): List<RuntimeHistoryEntry> {
    if (message.toolEvents.isEmpty()) return emptyList()
    return message.toolEvents.mapNotNull { event ->
      val priority = event.retentionPriority.ifBlank { RETENTION_SUMMARY_ONLY }
      val normalized = normalizePriority(priority, distanceFromNewestMessage)
      if (normalized == RETENTION_UI_ONLY) return@mapNotNull null
      RuntimeHistoryEntry(
        role = "tool_context",
        content = formatToolContext(event, normalized),
      )
    }
  }

  private fun normalizePriority(priority: String, distanceFromNewestMessage: Int): String {
    return when (priority) {
      RETENTION_ACTIVE_CRITICAL -> RETENTION_ACTIVE_CRITICAL
      RETENTION_RECENT_FULL -> {
        if (distanceFromNewestMessage <= RECENT_FULL_MESSAGE_DISTANCE) {
          RETENTION_RECENT_FULL
        } else {
          RETENTION_SUMMARY_ONLY
        }
      }
      RETENTION_STRUCTURED_MEMORY -> RETENTION_STRUCTURED_MEMORY
      RETENTION_UI_ONLY -> RETENTION_UI_ONLY
      else -> RETENTION_SUMMARY_ONLY
    }
  }

  private fun formatToolContext(event: ToolEvent, priority: String): String {
    val limit = when (priority) {
      RETENTION_ACTIVE_CRITICAL -> ACTIVE_CRITICAL_LIMIT
      RETENTION_RECENT_FULL -> RECENT_FULL_LIMIT
      RETENTION_STRUCTURED_MEMORY -> STRUCTURED_MEMORY_LIMIT
      else -> SUMMARY_LIMIT
    }
    return buildString {
      append("tool=")
      append(event.name)
      append(", priority=")
      append(priority)
      append(", success=")
      append(event.success)
      append(", kind=")
      append(event.resultKind)
      if (event.outputTruncated) {
        append(", storedOutput=truncated/${event.outputChars} chars")
      }
      if (event.retentionReason.isNotBlank()) {
        append(", reason=")
        append(event.retentionReason)
      }
      append(", args=")
      append(event.args.compact(ARG_LIMIT))
      append(", result=")
      append(event.result.compact(limit))
    }
  }

  private fun kindFor(name: String): String {
    return when {
      name in setOf("write_file", "edit_file") -> "file_write"
      name == "read_file" -> "file_read"
      name == "workspace_search" -> "search"
      name == "artifact_inspect" || name == "artifact_diagnose" -> "artifact_validation"
      name == "workspace_command_run" || name == "python_run" || name == "python_package_install" -> "command"
      name == "web_search" || name == "fetch_url" || name == "download_url" -> "network"
      name.contains("status") || name.contains("progress") -> "status"
      else -> "generic"
    }
  }

  private fun reasonFor(priority: String, kind: String, args: String): String {
    return when (priority) {
      RETENTION_STRUCTURED_MEMORY -> "successful $kind records changed artifacts or validation facts"
      RETENTION_RECENT_FULL -> "recent $kind output may be needed by the next model request"
      RETENTION_SUMMARY_ONLY -> "successful generic output is kept as a bounded summary"
      RETENTION_UI_ONLY -> "status-only output is display state, not model context"
      else -> args.takeIf { it.isNotBlank() } ?: "classified by tool kind"
    }
  }

  private fun looksLikeFailure(result: String): Boolean {
    val lower = result.lowercase()
    return lower.contains("\"success\": false") ||
      lower.contains("exitcode=") && !lower.contains("exitcode=0") ||
      lower.contains("error category:") ||
      lower.contains("exception") ||
      lower.contains("traceback") ||
      lower.contains("failed") ||
      lower.contains("failure") ||
      lower.contains("permission denied") ||
      lower.contains("not found")
  }

  private fun String.compact(limit: Int): String {
    val normalized = lineSequence()
      .joinToString(" ") { it.trim() }
      .replace(Regex("\\s+"), " ")
      .trim()
    if (normalized.length <= limit) return normalized
    return normalized.take(limit).trimEnd() + "..."
  }
}
