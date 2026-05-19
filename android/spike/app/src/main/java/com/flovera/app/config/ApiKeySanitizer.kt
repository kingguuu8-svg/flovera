package com.flovera.app.config

private val apiKeyTokenPattern = Regex("[A-Za-z0-9_-]{20,}")

fun normalizeBraveSearchApiKey(raw: String): String {
  val trimmed = raw.trim()
  if (trimmed.isBlank()) return ""
  return apiKeyTokenPattern.find(trimmed)?.value ?: trimmed.lineSequence().firstOrNull().orEmpty().trim()
}
