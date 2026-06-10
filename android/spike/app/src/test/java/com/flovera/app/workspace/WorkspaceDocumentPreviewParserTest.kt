package com.flovera.app.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDocumentPreviewParserTest {
  @Test
  fun docxUsesExplicitPageBreaksAndDecodesText() {
    val document = """
      <w:document xmlns:w="word">
        <w:body>
          <w:p><w:r><w:t>First &amp; page</w:t></w:r></w:p>
          <w:p><w:r><w:br w:type="page"/></w:r></w:p>
          <w:p><w:r><w:t>Second page</w:t></w:r></w:p>
        </w:body>
      </w:document>
    """.trimIndent()

    val preview = WorkspaceDocumentPreviewParser.parse(
      "report.docx",
      officeZip("word/document.xml" to document),
    )

    assertEquals("docx", preview.format)
    assertEquals(2, preview.pages.size)
    assertEquals("First & page", preview.pages[0].content)
    assertEquals("Second page", preview.pages[1].content)
  }

  @Test
  fun pptxSortsSlidesNumericallyAndKeepsParagraphOrder() {
    val preview = WorkspaceDocumentPreviewParser.parse(
      "deck.pptx",
      officeZip(
        "ppt/slides/slide10.xml" to slideXml("Tenth"),
        "ppt/slides/slide2.xml" to slideXml("Second"),
        "ppt/slides/slide1.xml" to slideXml("First", "Subtitle"),
      ),
    )

    assertEquals(listOf("Slide 1", "Slide 2", "Slide 3"), preview.pages.map { it.title })
    assertEquals("First\nSubtitle", preview.pages[0].content)
    assertEquals("Second", preview.pages[1].content)
    assertEquals("Tenth", preview.pages[2].content)
  }

  @Test
  fun xlsxResolvesSharedStringsAndNumericCells() {
    val sharedStrings = """
      <sst><si><t>Name</t></si><si><t>Flovera</t></si></sst>
    """.trimIndent()
    val sheet = """
      <worksheet><sheetData>
        <row><c t="s"><v>0</v></c><c t="s"><v>1</v></c></row>
        <row><c><v>42</v></c></row>
      </sheetData></worksheet>
    """.trimIndent()

    val preview = WorkspaceDocumentPreviewParser.parse(
      "table.xlsx",
      officeZip(
        "xl/sharedStrings.xml" to sharedStrings,
        "xl/worksheets/sheet1.xml" to sheet,
      ),
    )

    assertEquals(1, preview.pages.size)
    assertTrue(preview.pages.single().content.contains("Name | Flovera"))
    assertTrue(preview.pages.single().content.contains("42"))
  }

  private fun slideXml(vararg paragraphs: String): String {
    return paragraphs.joinToString(
      prefix = "<p:sld xmlns:p=\"presentation\" xmlns:a=\"drawing\">",
      postfix = "</p:sld>",
    ) { "<a:p><a:r><a:t>$it</a:t></a:r></a:p>" }
  }

  private fun officeZip(vararg entries: Pair<String, String>): ByteArrayInputStream {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
      entries.forEach { (path, content) ->
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
      }
    }
    return ByteArrayInputStream(bytes.toByteArray())
  }
}
