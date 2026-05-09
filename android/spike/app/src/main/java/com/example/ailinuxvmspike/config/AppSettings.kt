package com.example.ailinuxvmspike.config

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
  val provider: String = "deepseek",
  val model: String = "deepseek-v4-pro",
  val apiKey: String = "",
  val activeWorkspaceId: String = "default",
  val activeSessionId: String? = null,
  val selectedHtmlPath: String = "index.html",
  val maxAgentIterations: Int = 20,
)
