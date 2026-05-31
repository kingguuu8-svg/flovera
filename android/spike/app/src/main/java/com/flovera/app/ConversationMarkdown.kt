package com.flovera.app

import java.nio.charset.StandardCharsets

private val numberedListMarker = Regex("^\\s*\\d{1,3}[.)]\\s+")
private val unorderedListMarker = Regex("^\\s*[-*+]\\s+")
private val commonMojibakeSignals = listOf("Ã", "Â", "â", "ä", "å", "æ", "ç", "è", "é")

internal data class MarkdownListItem(val marker: String, val text: String)

internal fun normalizeConversationMarkdownContent(content: String): String {
  if (content.isEmpty()) return content
  val lineNormalized = repairLikelyUtf8Mojibake(
    content
      .replace("\r\n", "\n")
      .replace('\r', '\n'),
  )
  return buildString(lineNormalized.length) {
    lineNormalized.forEach { char ->
        when {
          char == '\uFEFF' -> Unit
          char == '\u0000' -> Unit
          char == '\n' || char == '\t' -> append(char)
          char.code < 0x20 -> append(' ')
          char.code in 0x7F..0x9F -> append(' ')
          else -> append(char)
        }
      }
  }.replace(Regex("[ \\t]+\\n"), "\n")
}

internal fun stripMarkdownListMarker(line: String): String? {
  return parseMarkdownListItem(line)?.text
}

internal fun parseMarkdownListItem(line: String): MarkdownListItem? {
  val trimmed = line.trim()
  val unordered = unorderedListMarker.find(trimmed)
  if (unordered != null) {
    return MarkdownListItem(marker = unordered.value.trim(), text = trimmed.drop(unordered.value.length).trim())
  }
  val ordered = numberedListMarker.find(line) ?: return null
  val marker = ordered.value.trim()
  return MarkdownListItem(marker = marker, text = numberedListMarker.replace(line, "").trim())
}

private fun repairLikelyUtf8Mojibake(content: String): String {
  if (content.none { it.code in 0x80..0xFF }) return content
  if (commonMojibakeSignals.none { content.contains(it) }) return content
  val decoded = runCatching {
    String(content.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)
  }.getOrNull() ?: return content
  if (decoded.contains('\uFFFD')) return content
  return if (mojibakeScore(decoded) + 4 < mojibakeScore(content)) decoded else content
}

private fun mojibakeScore(value: String): Int {
  var score = 0
  commonMojibakeSignals.forEach { signal ->
    score += value.windowed(signal.length).count { it == signal } * 3
  }
  score += value.count { it == '\uFFFD' } * 12
  score -= value.count { it in '\u4E00'..'\u9FFF' || it in '\u3040'..'\u30FF' || it in '\uAC00'..'\uD7AF' } * 2
  return score
}
