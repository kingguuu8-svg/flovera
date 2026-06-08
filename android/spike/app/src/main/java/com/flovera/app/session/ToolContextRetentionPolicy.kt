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

  fun classify(name: String, args: String, result: String): ToolRetentionDecision {
    val normalizedName = name.lowercase()
    val success = !looksLikeFailure(result)
    if (!success) {
      return ToolRetentionDecision(
        success = false,
        resultKind = kindFor(normalizedName, args),
        retentionPriority = RETENTION_ACTIVE_CRITICAL,
        retentionReason = "failed tool output must remain available for retry and diagnosis",
      )
    }

    val kind = kindFor(normalizedName, args)
    val priority = when (kind) {
      "skill_read" -> RETENTION_ACTIVE_CRITICAL
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

  private fun kindFor(name: String, args: String = ""): String {
    return when {
      name in setOf("write_file", "edit_file") -> "file_write"
      name == "read_file" && isSkillReadArgs(args) -> "skill_read"
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
      RETENTION_ACTIVE_CRITICAL -> if (kind == "skill_read") {
        "skill body read is active task guidance and should remain available while the task is current"
      } else {
        args.takeIf { it.isNotBlank() } ?: "active context is needed for recovery"
      }
      RETENTION_STRUCTURED_MEMORY -> "successful $kind records changed artifacts or validation facts"
      RETENTION_RECENT_FULL -> "recent $kind output may be needed by the next model request"
      RETENTION_SUMMARY_ONLY -> "successful generic output is kept as a bounded summary"
      RETENTION_UI_ONLY -> "status-only output is display state, not model context"
      else -> args.takeIf { it.isNotBlank() } ?: "classified by tool kind"
    }
  }

  private fun isSkillReadArgs(args: String): Boolean {
    val normalized = args.replace('\\', '/')
    return normalized.contains("path=.flovera/skills/") && normalized.contains("/SKILL.md")
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

}
