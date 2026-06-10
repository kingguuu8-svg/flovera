package com.flovera.app.workspace

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class WorkspaceDocumentPreviewPage(
  val title: String,
  val content: String,
)

data class WorkspaceDocumentPreview(
  val format: String,
  val pages: List<WorkspaceDocumentPreviewPage>,
)

object WorkspaceDocumentPreviewParser {
  private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
  private const val MAX_PAGES = 300
  private const val DOCX_READING_PAGE_CHARS = 1_800

  fun parse(path: String, input: InputStream): WorkspaceDocumentPreview {
    val format = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    val entries = readRelevantEntries(format, input)
    val pages = when (format) {
      "docx" -> parseDocx(entries["word/document.xml"].orEmpty())
      "pptx" -> parsePptx(entries)
      "xlsx" -> parseXlsx(entries)
      else -> emptyList()
    }
    return WorkspaceDocumentPreview(
      format = format,
      pages = pages.ifEmpty {
        listOf(WorkspaceDocumentPreviewPage(title = "Page 1", content = "No readable text content."))
      },
    )
  }

  private fun readRelevantEntries(format: String, input: InputStream): Map<String, String> {
    val result = linkedMapOf<String, String>()
    ZipInputStream(input.buffered()).use { zip ->
      while (result.size < MAX_PAGES + 2) {
        val entry = zip.nextEntry ?: break
        if (!entry.isDirectory && isRelevantEntry(format, entry.name)) {
          result[entry.name] = zip.readEntryText(MAX_ENTRY_BYTES)
        }
        zip.closeEntry()
      }
    }
    return result
  }

  private fun isRelevantEntry(format: String, name: String): Boolean {
    return when (format) {
      "docx" -> name == "word/document.xml"
      "pptx" -> PPTX_SLIDE_PATH.matches(name)
      "xlsx" -> name == "xl/sharedStrings.xml" || XLSX_SHEET_PATH.matches(name)
      else -> false
    }
  }

  private fun parseDocx(xml: String): List<WorkspaceDocumentPreviewPage> {
    if (xml.isBlank()) return emptyList()
    val text = buildString {
      DOCX_TOKEN.findAll(xml).forEach { match ->
        when {
          match.groups["text"] != null -> append(decodeXml(match.groups["text"]?.value.orEmpty()))
          match.groups["tab"] != null -> append('\t')
          match.groups["page"] != null -> append('\u000C')
          match.groups["paragraph"] != null -> append('\n')
        }
      }
    }
    val readingPages = text
      .split('\u000C')
      .flatMap { paginateText(it, DOCX_READING_PAGE_CHARS) }
      .filter { it.isNotBlank() }
      .take(MAX_PAGES)
    return readingPages.mapIndexed { index, content ->
      WorkspaceDocumentPreviewPage(
        title = "Reading page ${index + 1}",
        content = content,
      )
    }
  }

  private fun parsePptx(entries: Map<String, String>): List<WorkspaceDocumentPreviewPage> {
    return entries
      .filterKeys(PPTX_SLIDE_PATH::matches)
      .toList()
      .sortedBy { slideNumber(it.first) }
      .take(MAX_PAGES)
      .mapIndexed { index, (_, xml) ->
        WorkspaceDocumentPreviewPage(
          title = "Slide ${index + 1}",
          content = extractParagraphText(xml, "a").ifBlank { "No readable text content." },
        )
      }
  }

  private fun parseXlsx(entries: Map<String, String>): List<WorkspaceDocumentPreviewPage> {
    val sharedStrings = extractTextNodes(entries["xl/sharedStrings.xml"].orEmpty(), "t")
    return entries
      .filterKeys(XLSX_SHEET_PATH::matches)
      .toList()
      .sortedBy { sheetNumber(it.first) }
      .take(MAX_PAGES)
      .mapIndexed { index, (_, xml) ->
        WorkspaceDocumentPreviewPage(
          title = "Sheet ${index + 1}",
          content = extractSheetText(xml, sharedStrings).ifBlank { "No readable cell content." },
        )
      }
  }

  private fun extractSheetText(xml: String, sharedStrings: List<String>): String {
    return ROW_PATTERN.findAll(xml)
      .map { rowMatch ->
        CELL_PATTERN.findAll(rowMatch.groupValues[1])
          .map { cellMatch ->
            val attributes = cellMatch.groupValues[1]
            val body = cellMatch.groupValues[2]
            when {
              Regex("""\bt\s*=\s*["']s["']""").containsMatchIn(attributes) -> {
                val index = VALUE_PATTERN.find(body)?.groupValues?.getOrNull(1)?.trim()?.toIntOrNull()
                index?.let(sharedStrings::getOrNull).orEmpty()
              }
              Regex("""\bt\s*=\s*["']inlineStr["']""").containsMatchIn(attributes) -> {
                extractTextNodes(body, "t").joinToString("")
              }
              else -> decodeXml(VALUE_PATTERN.find(body)?.groupValues?.getOrNull(1).orEmpty().trim())
            }
          }
          .filter(String::isNotBlank)
          .joinToString(" | ")
      }
      .filter(String::isNotBlank)
      .joinToString("\n")
  }

  private fun extractParagraphText(xml: String, prefix: String): String {
    val paragraphPattern = Regex(
      """(?s)<$prefix:p\b[^>]*>(.*?)</$prefix:p\s*>""",
      RegexOption.IGNORE_CASE,
    )
    val paragraphs = paragraphPattern.findAll(xml)
      .map { extractTextNodes(it.groupValues[1], "$prefix:t").joinToString("") }
      .map(String::trim)
      .filter(String::isNotBlank)
      .toList()
    return if (paragraphs.isNotEmpty()) {
      paragraphs.joinToString("\n")
    } else {
      extractTextNodes(xml, "$prefix:t").joinToString("\n")
    }
  }

  private fun extractTextNodes(xml: String, tag: String): List<String> {
    if (xml.isBlank()) return emptyList()
    val escapedTag = Regex.escape(tag)
    return Regex(
      """(?s)<$escapedTag\b[^>]*>(.*?)</$escapedTag\s*>""",
      RegexOption.IGNORE_CASE,
    ).findAll(xml)
      .map { decodeXml(it.groupValues[1].replace(TAG_PATTERN, "")) }
      .toList()
  }

  private fun paginateText(text: String, maxChars: Int): List<String> {
    val paragraphs = text
      .lineSequence()
      .map(String::trim)
      .filter(String::isNotBlank)
      .toList()
    if (paragraphs.isEmpty()) return emptyList()

    val pages = mutableListOf<String>()
    val current = StringBuilder()
    paragraphs.forEach { paragraph ->
      val chunks = paragraph.chunked(maxChars)
      chunks.forEach { chunk ->
        val required = chunk.length + if (current.isEmpty()) 0 else 2
        if (current.isNotEmpty() && current.length + required > maxChars) {
          pages += current.toString()
          current.clear()
        }
        if (current.isNotEmpty()) current.appendLine()
        current.append(chunk)
      }
    }
    if (current.isNotEmpty()) pages += current.toString()
    return pages
  }

  private fun decodeXml(value: String): String {
    val numericDecoded = NUMERIC_ENTITY.replace(value) { match ->
      val raw = match.groupValues[1]
      val codePoint = if (raw.startsWith("x", ignoreCase = true)) {
        raw.drop(1).toIntOrNull(16)
      } else {
        raw.toIntOrNull()
      }
      codePoint?.let(Character::toChars)?.concatToString() ?: match.value
    }
    return numericDecoded
      .replace("&apos;", "'")
      .replace("&quot;", "\"")
      .replace("&gt;", ">")
      .replace("&lt;", "<")
      .replace("&amp;", "&")
  }

  private fun ZipInputStream.readEntryText(limit: Int): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (total < limit) {
      val read = read(buffer, 0, minOf(buffer.size, limit - total))
      if (read <= 0) break
      output.write(buffer, 0, read)
      total += read
    }
    return output.toByteArray().toString(Charsets.UTF_8)
  }

  private fun slideNumber(path: String): Int {
    return PPTX_SLIDE_PATH.matchEntire(path)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE
  }

  private fun sheetNumber(path: String): Int {
    return XLSX_SHEET_PATH.matchEntire(path)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE
  }

  private val PPTX_SLIDE_PATH = Regex("""ppt/slides/slide(\d+)\.xml""")
  private val XLSX_SHEET_PATH = Regex("""xl/worksheets/sheet(\d+)\.xml""")
  private val DOCX_TOKEN = Regex(
    """(?s)(?:<w:t\b[^>]*>(?<text>.*?)</w:t\s*>)|(?<tab><w:tab\b[^>]*/>)|(?<page><w:(?:lastRenderedPageBreak|br\b[^>]*w:type\s*=\s*["']page["'][^>]*)\s*/>)|(?<paragraph></w:p\s*>)""",
    RegexOption.IGNORE_CASE,
  )
  private val ROW_PATTERN = Regex("""(?s)<row\b[^>]*>(.*?)</row\s*>""", RegexOption.IGNORE_CASE)
  private val CELL_PATTERN = Regex("""(?s)<c\b([^>]*)>(.*?)</c\s*>""", RegexOption.IGNORE_CASE)
  private val VALUE_PATTERN = Regex("""(?s)<v\b[^>]*>(.*?)</v\s*>""", RegexOption.IGNORE_CASE)
  private val TAG_PATTERN = Regex("""<[^>]+>""")
  private val NUMERIC_ENTITY = Regex("""&#(x[0-9a-fA-F]+|\d+);""")
}
