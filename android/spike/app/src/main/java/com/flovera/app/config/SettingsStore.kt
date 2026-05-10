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

class SettingsStore(context: Context) {
  private val settingsFile = File(context.filesDir, "settings.json")
  private val settingsCipher = SettingsCipher()
  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun load(): AppSettings {
    if (!settingsFile.exists()) return AppSettings()
    val stored = readUtf8Text(settingsFile)
    decodeEncryptedSettings(stored)?.let { return it }
    val plaintext = runCatching { json.decodeFromString<AppSettings>(stored) }.getOrNull()
    if (plaintext != null) {
      save(plaintext)
      return plaintext
    }
    return AppSettings()
  }

  fun save(settings: AppSettings) {
    val encrypted = settingsCipher.encrypt(json.encodeToString(settings))
    writeUtf8TextAtomically(settingsFile, json.encodeToString(encrypted))
  }

  private fun decodeEncryptedSettings(stored: String): AppSettings? {
    return runCatching {
      val encrypted = json.decodeFromString<EncryptedSettingsFile>(stored)
      json.decodeFromString<AppSettings>(settingsCipher.decrypt(encrypted))
    }.getOrNull()
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
