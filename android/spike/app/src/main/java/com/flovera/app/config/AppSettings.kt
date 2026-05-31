package com.flovera.app.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val AGENT_ITERATIONS_UNLIMITED = 0
const val AGENT_ITERATIONS_INTERNAL_GUARD = 1_000_000

@Serializable
data class ModelContextOverride(
  val contextWindowTokens: Int? = null,
  val compressionThresholdPercent: Int? = null,
)

@Serializable
data class CustomOpenAIProviderSettings(
  // User-editable profile override for the controlled OpenAI-compatible provider slot.
  val baseUrl: String = "",
  val chatCompletionsPath: String = "/v1/chat/completions",
  val compatibilityMode: String = "generic",
)

@Serializable
data class OpenRouterProviderSettings(
  val providerPreferences: JsonObject = JsonObject(emptyMap()),
  val minCodingScore: Double? = null,
)

@Serializable
data class AppSettings(
  val provider: String = "deepseek",
  val model: String = "deepseek-v4-pro",
  // Legacy single-provider key. Kept so existing settings.json can migrate without data loss.
  val apiKey: String = "",
  val providerApiKeys: Map<String, String> = emptyMap(),
  val activeWorkspaceId: String = "default",
  val activeSessionId: String? = null,
  val selectedHtmlPath: String = "",
  val pinnedHtmlPaths: List<String> = emptyList(),
  val recentHtmlPaths: List<String> = emptyList(),
  val maxAgentIterations: Int = AGENT_ITERATIONS_UNLIMITED,
  val networkEnabled: Boolean = false,
  val webSearchEnabled: Boolean = false,
  val braveSearchApiKey: String = "",
  val language: String = "en",
  val themeMode: String = "light",
  val themeColor: String = "#127089",
  val agentAuthorityMode: String = "safe",
  val deepSeekThinkingEffort: String = "high",
  val reasoningEffort: String = "",
  val modelContextOverrides: Map<String, ModelContextOverride> = emptyMap(),
  val customOpenAIProvider: CustomOpenAIProviderSettings = CustomOpenAIProviderSettings(),
  val openRouterProvider: OpenRouterProviderSettings = OpenRouterProviderSettings(),
) {
  fun apiKeyFor(providerId: String = provider): String {
    val keyed = providerApiKeys[providerId].orEmpty()
    if (keyed.isNotBlank()) return keyed
    if (providerId == "lmstudio") return LMSTUDIO_NOAUTH_PLACEHOLDER
    return if (providerId == "deepseek") apiKey else ""
  }

  fun withApiKey(providerId: String, value: String): AppSettings {
    val normalized = value.trim()
    val updatedKeys = if (normalized.isBlank()) {
      providerApiKeys - providerId
    } else {
      providerApiKeys + (providerId to normalized)
    }
    return copy(
      apiKey = if (providerId == "deepseek") normalized else apiKey,
      providerApiKeys = updatedKeys,
    )
  }

  fun modelContextOverrideFor(providerId: String = provider, modelId: String = model): ModelContextOverride? {
    return modelContextOverrides[modelContextOverrideKey(providerId, modelId)]
  }

  fun withModelContextOverride(
    providerId: String = provider,
    modelId: String = model,
    override: ModelContextOverride?,
  ): AppSettings {
    val key = modelContextOverrideKey(providerId, modelId)
    val updated = if (override == null || override == ModelContextOverride()) {
      modelContextOverrides - key
    } else {
      modelContextOverrides + (key to override)
    }
    return copy(modelContextOverrides = updated)
  }

  companion object {
    const val LMSTUDIO_NOAUTH_PLACEHOLDER = "dummy-lm-api-key"

    fun modelContextOverrideKey(providerId: String, modelId: String): String {
      return "${providerId.trim()}:${modelId.trim()}"
    }
  }
}
