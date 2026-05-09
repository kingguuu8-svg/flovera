package com.example.ailinuxvmspike.config

import android.content.Context
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SettingsStore(context: Context) {
  private val settingsFile = File(context.filesDir, "settings.json")
  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun load(): AppSettings {
    if (!settingsFile.exists()) return AppSettings()
    return runCatching {
      json.decodeFromString<AppSettings>(settingsFile.readText())
    }.getOrDefault(AppSettings())
  }

  fun save(settings: AppSettings) {
    settingsFile.parentFile?.mkdirs()
    settingsFile.writeText(json.encodeToString(settings))
  }
}
