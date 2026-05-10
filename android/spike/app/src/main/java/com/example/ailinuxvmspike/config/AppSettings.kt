package com.example.ailinuxvmspike.config

import kotlinx.serialization.Serializable

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
  val maxAgentIterations: Int = 20,
  val networkEnabled: Boolean = false,
) {
  fun apiKeyFor(providerId: String = provider): String {
    val keyed = providerApiKeys[providerId].orEmpty()
    if (keyed.isNotBlank()) return keyed
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
}
