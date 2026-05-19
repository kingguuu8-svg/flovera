package com.flovera.app.agent

data class ContextBudgetEvaluation(
  val status: String,
  val usagePermille: Int?,
  val thresholdPercent: Int?,
  val reason: String,
)

object AgentContextBudget {
  const val STATUS_UNKNOWN = "unknown"
  const val STATUS_SAFE = "safe"
  const val STATUS_WATCH = "watch"
  const val STATUS_COMPRESSION_RECOMMENDED = "compression_recommended"

  fun evaluate(
    tokens: Int,
    contextWindowTokens: Int?,
    compressionThresholdPercent: Int?,
  ): ContextBudgetEvaluation {
    val usagePermille = usagePermille(tokens, contextWindowTokens)
    if (usagePermille == null) {
      return ContextBudgetEvaluation(
        status = STATUS_UNKNOWN,
        usagePermille = null,
        thresholdPercent = compressionThresholdPercent,
        reason = "Model context window is unknown.",
      )
    }
    val threshold = compressionThresholdPercent?.coerceIn(1, 100)
      ?: return ContextBudgetEvaluation(
        status = STATUS_SAFE,
        usagePermille = usagePermille,
        thresholdPercent = null,
        reason = "No compression threshold is configured.",
      )
    val thresholdPermille = threshold * 10
    val watchPermille = (thresholdPermille * 8) / 10
    return when {
      usagePermille >= thresholdPermille -> ContextBudgetEvaluation(
        status = STATUS_COMPRESSION_RECOMMENDED,
        usagePermille = usagePermille,
        thresholdPercent = threshold,
        reason = "Context usage reached the compression threshold.",
      )
      usagePermille >= watchPermille -> ContextBudgetEvaluation(
        status = STATUS_WATCH,
        usagePermille = usagePermille,
        thresholdPercent = threshold,
        reason = "Context usage is approaching the compression threshold.",
      )
      else -> ContextBudgetEvaluation(
        status = STATUS_SAFE,
        usagePermille = usagePermille,
        thresholdPercent = threshold,
        reason = "Context usage is below the compression threshold.",
      )
    }
  }

  private fun usagePermille(tokens: Int, contextWindowTokens: Int?): Int? {
    if (contextWindowTokens == null || contextWindowTokens <= 0) return null
    return ((tokens.coerceAtLeast(0).toLong() * 1_000L) / contextWindowTokens).coerceIn(0L, 1_000L).toInt()
  }
}
