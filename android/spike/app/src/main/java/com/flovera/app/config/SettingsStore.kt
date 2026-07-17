package com.flovera.app.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.flovera.app.storage.readUtf8Text
import com.flovera.app.storage.writeUtf8TextAtomically
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SettingsLoadResult(
  val settings: AppSettings,
  val warning: String? = null,
)

class SettingsStore(
  context: Context,
  private val settingsFile: File = File(context.filesDir, "settings.json"),
) {
  private val settingsCipher = SettingsCipher()
  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun load(): AppSettings {
    return loadResult().settings
  }

  @Synchronized
  fun loadResult(): SettingsLoadResult {
    if (!settingsFile.exists()) return SettingsLoadResult(AppSettings())
    val stored = runCatching { readUtf8Text(settingsFile) }.getOrElse {
      return SettingsLoadResult(AppSettings(), "Settings could not be read; defaults were loaded.")
    }

    val encrypted = decodeEncryptedSettings(stored)
    if (encrypted != null) {
      return encrypted.fold(
        onSuccess = { SettingsLoadResult(it) },
        onFailure = {
          SettingsLoadResult(AppSettings(), "Settings could not be decrypted; defaults were loaded.")
        },
      )
    }

    val plaintext = runCatching { json.decodeFromString<AppSettings>(stored) }
    plaintext.getOrNull()?.let {
      save(it)
      return SettingsLoadResult(it)
    }

    return SettingsLoadResult(AppSettings(), "Settings file is invalid; defaults were loaded.")
  }

  @Synchronized
  fun save(settings: AppSettings) {
    val encrypted = settingsCipher.encrypt(json.encodeToString(settings))
    writeUtf8TextAtomically(settingsFile, json.encodeToString(encrypted))
  }

  @Synchronized
  fun update(fallback: AppSettings, transform: (AppSettings) -> AppSettings): AppSettings {
    val current = if (settingsFile.exists()) loadResult().settings else fallback
    val updated = transform(current)
    if (updated != current) save(updated)
    return updated
  }

  @Synchronized
  fun loadAndUpdate(transform: (AppSettings) -> AppSettings): SettingsLoadResult {
    val result = loadResult()
    val updated = transform(result.settings)
    if (updated != result.settings) save(updated)
    return result.copy(settings = updated)
  }

  private fun decodeEncryptedSettings(stored: String): Result<AppSettings>? {
    val encrypted = runCatching {
      json.decodeFromString<EncryptedSettingsFile>(stored)
    }.getOrNull() ?: return null
    return runCatching {
      json.decodeFromString<AppSettings>(settingsCipher.decrypt(encrypted))
    }
  }
}

@Serializable
private data class EncryptedSettingsFile(
  val version: Int = 1,
  val algorithm: String = "AES/GCM/NoPadding",
  val iv: String,
  val cipherText: String,
)

private class SettingsCipher {
  fun encrypt(plaintext: String): EncryptedSettingsFile {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    val cipherText = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    return EncryptedSettingsFile(
      iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
      cipherText = Base64.encodeToString(cipherText, Base64.NO_WRAP),
    )
  }

  fun decrypt(encrypted: EncryptedSettingsFile): String {
    require(encrypted.version == 1) { "Unsupported settings version: ${encrypted.version}" }
    require(encrypted.algorithm == TRANSFORMATION) { "Unsupported settings algorithm: ${encrypted.algorithm}" }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    val iv = Base64.decode(encrypted.iv, Base64.NO_WRAP)
    cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
    val plaintext = cipher.doFinal(Base64.decode(encrypted.cipherText, Base64.NO_WRAP))
    return plaintext.toString(Charsets.UTF_8)
  }

  private fun getOrCreateKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
    if (existing != null) return existing.secretKey

    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val spec = KeyGenParameterSpec.Builder(
      KEY_ALIAS,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setRandomizedEncryptionRequired(true)
      .build()
    keyGenerator.init(spec)
    return keyGenerator.generateKey()
  }

  private companion object {
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "flovera_settings_v1"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_BITS = 128
  }
}
