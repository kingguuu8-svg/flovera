package com.flovera.app.config

import android.content.Context
import com.flovera.app.storage.readUtf8Text
import com.flovera.app.storage.writeUtf8TextAtomically
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
      json.decodeFromString<AppSettings>(readUtf8Text(settingsFile))
    }.getOrDefault(AppSettings())
  }

  fun save(settings: AppSettings) {
    writeUtf8TextAtomically(settingsFile, json.encodeToString(settings))
  }
}
