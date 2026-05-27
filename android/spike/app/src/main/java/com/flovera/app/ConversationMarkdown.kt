package com.flovera.app

private val numberedListMarker = Regex("^\\s*\\d{1,3}[.)]\\s+")

internal fun normalizeConversationMarkdownContent(content: String): String {
  if (content.isEmpty()) return content
  return buildString(content.length) {
    content
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .forEach { char ->
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
  val trimmed = line.trim()
  return when {
    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> trimmed.drop(2).trim()
    numberedListMarker.containsMatchIn(line) -> numberedListMarker.replace(line, "").trim()
    else -> null
  }
}
