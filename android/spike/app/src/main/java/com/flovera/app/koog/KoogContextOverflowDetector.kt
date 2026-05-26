package com.flovera.app.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.utils.io.use
import com.flovera.app.agent.ContextOverflowDetection
import com.flovera.app.agent.ContextOverflowDetector
import com.flovera.app.config.AppSettings

class KoogContextOverflowDetector : ContextOverflowDetector {
  override suspend fun detect(settings: AppSettings, error: Throwable): ContextOverflowDetection {
    val errorText = error.stackTraceToString().take(ERROR_TEXT_LIMIT)
    detectByLocalRules(errorText)?.let { return it }
    return detectByLlm(settings, errorText).getOrElse { fallbackError ->
      ContextOverflowDetection(
        isOverflow = false,
        source = "local_unknown_llm_failed",
        reason = oneLine(fallbackError.message ?: fallbackError.toString(), 300),
      )
    }
  }

  private fun detectByLocalRules(errorText: String): ContextOverflowDetection? {
    val normalized = errorText.lowercase()
    val directOverflow = listOf(
      "context length exceeded",
      "maximum context length",
      "max context length",
      "context window",
      "too many tokens",
      "input too long",
      "prompt too long",
      "request too large",
      "payload too large",
      "maximum number of tokens",
      "exceeds the token limit",
      "exceeded token limit",
      "tokens exceeded",
      "context_length_exceeded",
    )
    directOverflow.firstOrNull { normalized.contains(it) }?.let { phrase ->
      return ContextOverflowDetection(
        isOverflow = true,
        source = "local_rule",
        reason = "matched '$phrase'",
      )
    }
    val hasContextSignal = normalized.contains("context") ||
      normalized.contains("token") ||
      normalized.contains("prompt") ||
      normalized.contains("input")
    val hasLimitSignal = normalized.contains("maximum") ||
      normalized.contains("limit") ||
      normalized.contains("too long") ||
      normalized.contains("too large") ||
      normalized.contains("exceed")
    if (hasContextSignal && hasLimitSignal) {
      return ContextOverflowDetection(
        isOverflow = true,
        source = "local_rule",
        reason = "matched generic context/token limit signals",
      )
    }
    return null
  }

  private suspend fun detectByLlm(settings: AppSettings, errorText: String): Result<ContextOverflowDetection> {
    return runCatching {
      val provider = ModelProviderCatalog.requireProvider(settings.provider)
      val apiKey = settings.apiKeyFor(provider.id)
      require(apiKey.isNotBlank()) { "${provider.label} API key is not configured." }
      val modelContext = ModelProviderCatalog.contextFor(settings)
      val agent = AIAgent(
        promptExecutor = MultiLLMPromptExecutor(ModelProviderCatalog.createClient(provider, apiKey, settings)),
        llmModel = provider.createModel(settings.model, modelContext),
        toolRegistry = ToolRegistry {},
        systemPrompt = """
          You classify provider errors for an Android-local agent runtime.
          Return exactly one compact line:
          overflow=true reason=<short reason>
          or
          overflow=false reason=<short reason>
          Mark overflow=true only when the error likely means the request exceeded context length, prompt length, input size, or token budget.
        """.trimIndent(),
        maxIterations = 1,
      )
      val output = agent.use {
        it.run(
          agentInput = """
            Provider: ${settings.provider}
            Model: ${settings.model}

            Error:
            ${errorText.take(LLM_ERROR_TEXT_LIMIT)}
          """.trimIndent(),
          sessionId = "context-overflow-detection-${System.currentTimeMillis()}",
        )
      }
      val normalized = output.lowercase()
      ContextOverflowDetection(
        isOverflow = normalized.contains("overflow=true"),
        source = "llm_classifier",
        reason = oneLine(output, 300),
      )
    }
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

  private companion object {
    const val ERROR_TEXT_LIMIT = 8_000
    const val LLM_ERROR_TEXT_LIMIT = 3_000
  }
}
