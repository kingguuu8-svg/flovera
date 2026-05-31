package com.flovera.app.koog

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingRegistry
import com.knuddels.jtokkit.api.EncodingType

data class AgentPayloadTokenEstimate(
  val tokens: Int,
  val source: String,
)

object AgentPayloadTokenEstimator {
  private val registry: EncodingRegistry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    Encodings.newLazyEncodingRegistry()
  }

  fun estimate(
    payloadJson: String,
    transportOverheadChars: Int,
    model: String,
  ): AgentPayloadTokenEstimate {
    val tokenizerEstimate = runCatching {
      val encoding = encodingFor(model)
      val payloadTokens = encoding.countTokens(payloadJson)
      val overheadTokens = estimateByChars(transportOverheadChars)
      AgentPayloadTokenEstimate(
        tokens = payloadTokens + overheadTokens,
        source = "tokenizer_jtokkit_${encoding.getName()}_with_overhead_estimate",
      )
    }.getOrNull()
    if (tokenizerEstimate != null) return tokenizerEstimate

    return AgentPayloadTokenEstimate(
      tokens = estimateByText(payloadJson) + estimateByChars(transportOverheadChars),
      source = "payload_char_estimate_fallback",
    )
  }

  private fun encodingFor(model: String): Encoding {
    return registry.getEncodingForModel(model).orElseGet {
      registry.getEncoding(EncodingType.O200K_BASE)
    }
  }

  private fun estimateByChars(chars: Int): Int {
    if (chars <= 0) return 0
    return ((chars + 3) / 4).coerceAtLeast(1)
  }

  private fun estimateByText(text: String): Int {
    if (text.isBlank()) return 0
    var cjk = 0
    var asciiAlphaNumeric = 0
    var otherNonWhitespace = 0
    text.forEach { char ->
      when {
        char.isWhitespace() -> Unit
        char.isCjkLike() -> cjk += 1
        char.code < 128 && char.isLetterOrDigit() -> asciiAlphaNumeric += 1
        else -> otherNonWhitespace += 1
      }
    }
    return cjk +
      ((asciiAlphaNumeric + 3) / 4) +
      ((otherNonWhitespace + 2) / 3)
  }

  private fun Char.isCjkLike(): Boolean {
    return code in 0x3400..0x4DBF ||
      code in 0x4E00..0x9FFF ||
      code in 0xF900..0xFAFF ||
      code in 0x3040..0x30FF ||
      code in 0xAC00..0xD7AF
  }
}
