package com.flovera.app

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.koog.ArtifactInspectTool
import com.flovera.app.koog.PythonRunTool
import com.flovera.app.koog.PythonPackageInstallTool
import com.flovera.app.config.AppSettings
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.koog.WorkspaceSearchTool
import com.flovera.app.workspace.WorkspaceController
import com.flovera.app.workspace.FloveraSettingsView
import com.flovera.app.workspace.WorkspaceManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFileTreeInstrumentedTest {
  @Test
  fun workspaceTreeIncludesNestedFilesAndSupportsRename() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "tree-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    workspace.writeFile("nested/page.html", "<!doctype html><title>Nested</title>")
    workspace.writeFile("nested/data.json", "{}")

    val nested = workspace.fileTree().children.firstOrNull { it.path == "nested" }
    assertNotNull(nested)
    assertTrue(nested!!.isDirectory)
    assertTrue(nested.children.any { it.path == "nested/page.html" })
    assertEquals("text/html", workspace.mimeType("nested/page.html"))

    val uri = FileProvider.getUriForFile(
      context,
      "${context.packageName}.workspacefiles",
      workspace.exportableFile("nested/page.html")!!,
    )
    assertEquals("content", uri.scheme)

    val result = workspace.rename("nested/page.html", "home.html")
    assertTrue(result.startsWith("Renamed"))
    assertTrue(workspace.listHtmlFiles().contains("nested/home.html"))

    val deleteResult = workspace.deletePath("nested/home.html")
    assertEquals("Deleted nested/home.html", deleteResult)
    assertEquals("File does not exist: nested/home.html", workspace.readFile("nested/home.html"))
  }

  @Test
  fun workspaceControllerSnapshotNormalizesHtmlSelection() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val controller = WorkspaceController(context, "workspace-controller-${System.currentTimeMillis()}")
      .also { it.ensureSeedFiles() }

    val initial = controller.snapshot("index.html")
    assertEquals("index.html", initial.selectedHtmlPath)
    assertNotNull(initial.selectedHtmlUrl)
    assertTrue(initial.htmlFiles.contains("index.html"))

    val missing = controller.snapshot("missing.html")
    assertEquals("", missing.selectedHtmlPath)
    assertEquals(null, missing.selectedHtmlUrl)
  }

  @Test
  fun workspaceWritesTextEditsAndBytesAtomically() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "atomic-workspace-${System.currentTimeMillis()}")
    val textFile = File(workspace.root, "notes/today.md")
    val bytesFile = File(workspace.root, "assets/icon.bin")

    workspace.writeFile("notes/today.md", "alpha")
    workspace.editFile("notes/today.md", "alpha", "beta")
    workspace.writeBytes("assets/icon.bin", byteArrayOf(1, 2, 3))

    assertEquals("beta", workspace.readFile("notes/today.md"))
    assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), bytesFile.readBytes().toList())
    listOf(textFile, bytesFile).forEach { file ->
      assertTrue(file.isFile)
      assertFalse(File(file.absolutePath + ".new").exists())
      assertFalse(File(file.absolutePath + ".bak").exists())
    }
  }

  @Test
  fun workspaceReadPreviewTruncatesLargeText() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "preview-workspace-${System.currentTimeMillis()}")
    workspace.writeFile("large.txt", "a".repeat(128))

    val preview = workspace.readFilePreview("large.txt", maxChars = 16)

    assertTrue(preview.startsWith("a".repeat(16)))
    assertTrue(preview.contains("[truncated: showing first 16 chars"))
  }

  @Test
  fun workspaceSearchFindsTextAndRecordsToolEvent() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "search-workspace-${System.currentTimeMillis()}")
    val recorder = ToolEventRecorder()
    workspace.writeFile("src/ProviderRoutes.kt", "fun routeCopilot() = \"Codex Responses transport\"")
    workspace.writeFile("notes.md", "Provider routing notes")

    val result = WorkspaceSearchTool(workspace, recorder).execute(
      WorkspaceSearchTool.Args(query = "copilot responses", topK = 5),
    )

    assertTrue(result.contains("src/ProviderRoutes.kt:1"))
    assertTrue(result.contains("Codex Responses transport"))
    assertTrue(recorder.snapshot().any { it.name == "workspace_search" })
  }

  @Test
  fun workspaceSearchScopesFloveraMetadataByPermission() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "search-scope-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val tool = WorkspaceSearchTool(workspace, ToolEventRecorder())
    workspace.writeFile(
      ".flovera/proposals/search-tool.json",
      """{"type":"tool","name":"workspace_search","description":"metadataonlymarker"}""",
      createAutoSnapshot = false,
    )
    workspace.writeFile(".flovera/internal/debug.txt", "internalonlymarker", createAutoSnapshot = false)
    workspace.writeFile(".flovera/retrieval/index.json", """{"text":"retrievalonlymarker"}""", createAutoSnapshot = false)

    val publicResult = tool.execute(WorkspaceSearchTool.Args(query = "metadataonlymarker"))
    val metadataResult = tool.execute(
      WorkspaceSearchTool.Args(query = "metadataonlymarker", scope = "workspace_app_metadata"),
    )
    val internalMetadataResult = tool.execute(
      WorkspaceSearchTool.Args(query = "internalonlymarker", scope = "workspace_app_metadata"),
    )
    val internalResult = tool.execute(
      WorkspaceSearchTool.Args(query = "internalonlymarker", scope = "workspace_internal"),
    )
    val retrievalResult = tool.execute(
      WorkspaceSearchTool.Args(query = "retrievalonlymarker", scope = "workspace_internal"),
    )

    assertTrue(publicResult.contains("No matches"))
    assertTrue(metadataResult.contains(".flovera/proposals/search-tool.json:1"))
    assertTrue(internalMetadataResult.contains("No matches"))
    assertTrue(internalResult.contains(".flovera/internal/debug.txt:1"))
    assertTrue(retrievalResult.contains("No matches"))
  }

  @Test
  fun workspaceSearchSkipsBinaryAndLargeFiles() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "search-filter-${System.currentTimeMillis()}")
    val tool = WorkspaceSearchTool(workspace, ToolEventRecorder())
    workspace.writeBytes("assets/blob.bin", byteArrayOf(0, 1, 2, 3, 4), createAutoSnapshot = false)
    workspace.writeFile("large.txt", "needle\n" + "x".repeat(600 * 1024), createAutoSnapshot = false)
    workspace.writeFile(".secret.txt", "needle", createAutoSnapshot = false)
    workspace.writeFile("small.txt", "needle is here", createAutoSnapshot = false)

    val result = tool.execute(WorkspaceSearchTool.Args(query = "needle", topK = 10))

    assertTrue(result.contains("small.txt:1"))
    assertFalse(result.contains("large.txt"))
    assertFalse(result.contains("blob.bin"))
    assertFalse(result.contains(".secret.txt"))
  }

  @Test
  fun workspaceSearchL2OptionsNarrowAndShapeResults() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "search-l2-${System.currentTimeMillis()}")
    val tool = WorkspaceSearchTool(workspace, ToolEventRecorder())
    workspace.writeFile(
      "src/main/Routes.kt",
      """
      package demo
      fun routeProvider() {
        val transport = "Codex Responses"
      }
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    workspace.writeFile("src/test/RoutesTest.kt", "val transport = \"test\"")
    workspace.writeFile("docs/routes.md", "transport docs")

    val scoped = tool.execute(
      WorkspaceSearchTool.Args(query = "transport", path = "src/main", topK = 10),
    )
    val includeGlob = tool.execute(
      WorkspaceSearchTool.Args(query = "transport", includeGlob = "src/**/*.kt", excludeGlob = "src/test/**", topK = 10),
    )
    val fileTypeGlob = tool.execute(
      WorkspaceSearchTool.Args(query = "transport", includeGlob = "*.kt", topK = 10),
    )
    val contextResult = tool.execute(
      WorkspaceSearchTool.Args(query = "transport", path = "src/main/Routes.kt", contextLines = 1),
    )

    assertTrue(scoped.contains("src/main/Routes.kt:3"))
    assertFalse(scoped.contains("src/test/RoutesTest.kt"))
    assertFalse(scoped.contains("docs/routes.md"))
    assertTrue(includeGlob.contains("src/main/Routes.kt:3"))
    assertFalse(includeGlob.contains("src/test/RoutesTest.kt"))
    assertFalse(includeGlob.contains("docs/routes.md"))
    assertTrue(fileTypeGlob.contains("src/main/Routes.kt:3"))
    assertTrue(fileTypeGlob.contains("src/test/RoutesTest.kt:1"))
    assertFalse(fileTypeGlob.contains("docs/routes.md"))
    assertTrue(contextResult.contains(" 2: fun routeProvider()"))
    assertTrue(contextResult.contains(">3: val transport"))
    assertTrue(contextResult.contains(" 4: }"))
  }

  @Test
  fun workspaceSearchSupportsRegexAndCaseSensitivity() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "search-regex-${System.currentTimeMillis()}")
    val tool = WorkspaceSearchTool(workspace, ToolEventRecorder())
    workspace.writeFile("code.kt", "val ProviderTransport = \"ok\"\nval providertransport = \"lower\"", createAutoSnapshot = false)

    val regex = tool.execute(
      WorkspaceSearchTool.Args(query = "Provider[A-Za-z]+", mode = "regex"),
    )
    val caseSensitive = tool.execute(
      WorkspaceSearchTool.Args(query = "ProviderTransport", caseSensitive = true, topK = 10),
    )
    val invalidRegex = tool.execute(
      WorkspaceSearchTool.Args(query = "[", mode = "regex"),
    )

    assertTrue(regex.contains("code.kt:1"))
    assertTrue(caseSensitive.contains("code.kt:1"))
    assertFalse(caseSensitive.contains("code.kt:2"))
    assertTrue(invalidRegex.contains("Invalid regex"))
  }

  @Test
  fun workspaceSearchL3RespectsIgnoreFilesAndCanDisableThem() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "search-l3-ignore-${System.currentTimeMillis()}")
    val tool = WorkspaceSearchTool(workspace, ToolEventRecorder())
    workspace.writeFile(
      ".gitignore",
      """
      build/
      *.log
      !important.log
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    workspace.writeFile("src/App.kt", "needle in source", createAutoSnapshot = false)
    workspace.writeFile("build/generated.txt", "needle in build", createAutoSnapshot = false)
    workspace.writeFile("debug.log", "needle in log", createAutoSnapshot = false)
    workspace.writeFile("important.log", "needle in important log", createAutoSnapshot = false)

    val respected = tool.execute(WorkspaceSearchTool.Args(query = "needle", topK = 10))
    val disabled = tool.execute(WorkspaceSearchTool.Args(query = "needle", respectIgnoreFiles = false, topK = 10))

    assertTrue(respected.contains("src/App.kt:1"))
    assertTrue(respected.contains("important.log:1"))
    assertFalse(respected.contains("build/generated.txt"))
    assertFalse(respected.contains("debug.log"))
    assertTrue(disabled.contains("build/generated.txt:1"))
    assertTrue(disabled.contains("debug.log:1"))
  }

  @Test
  fun workspaceSearchL3SupportsFilesCountAndScanBudget() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "search-l3-output-${System.currentTimeMillis()}")
    val tool = WorkspaceSearchTool(workspace, ToolEventRecorder())
    workspace.writeFile("src/A.kt", "needle one\nneedle two", createAutoSnapshot = false)
    workspace.writeFile("src/B.kt", "needle one", createAutoSnapshot = false)
    workspace.writeFile("src/C.kt", "needle one", createAutoSnapshot = false)

    val files = tool.execute(WorkspaceSearchTool.Args(query = "needle", path = "src", output = "files", topK = 10))
    val count = tool.execute(WorkspaceSearchTool.Args(query = "needle", path = "src", output = "count", topK = 10))
    val budget = tool.execute(WorkspaceSearchTool.Args(query = "needle", path = "src", maxFiles = 1, topK = 10))

    assertTrue(files.contains("Found 3 files"))
    assertTrue(files.contains("src/A.kt"))
    assertFalse(files.contains("score="))
    assertTrue(count.contains("Found 4 matches in 3 files"))
    assertTrue(count.contains("src/A.kt count=2"))
    assertTrue(budget.contains("stoppedAfterMaxFiles=1"))
    assertFalse(budget.contains("src/B.kt"))
    assertFalse(budget.contains("src/C.kt"))
  }

  @Test
  fun workspaceSearchL35GroupsContextAndKeepsDebugOptIn() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "search-l35-output-${System.currentTimeMillis()}")
    val tool = WorkspaceSearchTool(workspace, ToolEventRecorder())
    workspace.writeFile(
      "src/Merged.kt",
      """
      fun before() = Unit
      val firstNeedle = "alpha"
      val secondNeedle = "beta"
      fun after() = Unit
      """.trimIndent(),
      createAutoSnapshot = false,
    )

    val compact = tool.execute(
      WorkspaceSearchTool.Args(query = "Needle", path = "src/Merged.kt", contextLines = 1, topK = 10),
    )
    val debug = tool.execute(
      WorkspaceSearchTool.Args(query = "Needle", path = "src/Merged.kt", contextLines = 1, topK = 10, debug = true),
    )

    assertTrue(compact.contains("src/Merged.kt:2,3"))
    assertEquals(compact.indexOf(">2: val firstNeedle"), compact.lastIndexOf(">2: val firstNeedle"))
    assertEquals(compact.indexOf(">3: val secondNeedle"), compact.lastIndexOf(">3: val secondNeedle"))
    assertTrue(compact.contains(" 1: fun before()"))
    assertTrue(compact.contains(" 4: fun after()"))
    assertFalse(compact.contains("score="))
    assertFalse(compact.contains("scannedFiles="))
    assertTrue(debug.contains("maxScore="))
    assertTrue(debug.contains("scannedFiles="))
  }

  @Test
  fun pythonRunCalculatesAndWritesWorkspaceFiles() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "python-run-${System.currentTimeMillis()}")
    val tool = PythonRunTool(workspace, ToolEventRecorder())

    val result = tool.execute(
      PythonRunTool.Args(
        code = """
        import json
        import math
        import os

        os.makedirs("out", exist_ok=True)
        with open("out/result.json", "w", encoding="utf-8") as handle:
            json.dump({"hypot": math.hypot(3, 4)}, handle)
        print("computed", math.factorial(5))
        """.trimIndent(),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Python status=ok exitCode=0"))
    assertTrue(result, result.contains("computed 120"))
    assertTrue(result, workspace.readFile("out/result.json").contains("\"hypot\": 5.0"))
  }

  @Test
  fun pythonRunSupportsXmlAndDocxDocumentProcessing() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "python-docs-${System.currentTimeMillis()}")
    val tool = PythonRunTool(workspace, ToolEventRecorder())

    val result = tool.execute(
      PythonRunTool.Args(
        code = """
        import os
        from docx import Document
        from lxml import etree

        os.makedirs("docs", exist_ok=True)

        root = etree.Element("report")
        etree.SubElement(root, "title").text = "Flovera Python Document"
        etree.SubElement(root, "status").text = "ready"
        with open("docs/report.xml", "w", encoding="utf-8") as handle:
            handle.write(etree.tostring(root, encoding="unicode", pretty_print=True))

        document = Document()
        document.add_heading("Flovera Python Document", level=1)
        document.add_paragraph("lxml and python-docx are available.")
        table = document.add_table(rows=2, cols=2)
        table.cell(0, 0).text = "Capability"
        table.cell(0, 1).text = "Status"
        table.cell(1, 0).text = "document-processing"
        table.cell(1, 1).text = "ready"
        document.save("docs/report.docx")

        loaded = Document("docs/report.docx")
        parsed = etree.parse("docs/report.xml")
        print(loaded.paragraphs[0].text)
        print(parsed.getroot().findtext("status"))
        """.trimIndent(),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Python status=ok exitCode=0"))
    assertTrue(result, result.contains("Flovera Python Document"))
    assertTrue(result, result.contains("ready"))
    assertTrue(workspace.readFile("docs/report.xml").contains("<status>ready</status>"))
    assertTrue(workspace.exportableFile("docs/report.docx")!!.length() > 0)
  }

  @Test
  fun pythonRunSupportsProductionPackagesForOfficePdfAndHtml() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "python-production-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val tool = PythonRunTool(workspace, ToolEventRecorder())

    val result = tool.execute(
      PythonRunTool.Args(
        code = """
        import json
        import markdown
        import openpyxl
        import xlsxwriter
        from jinja2 import Template
        from pypdf import PdfWriter

        workbook = openpyxl.Workbook()
        sheet = workbook.active
        sheet.title = "Data"
        sheet["A1"] = "value"
        sheet["A2"] = 42
        workbook.save("production-openpyxl.xlsx")

        xlsx = xlsxwriter.Workbook("production-xlsxwriter.xlsx")
        worksheet = xlsx.add_worksheet("Report")
        worksheet.write("A1", "metric")
        worksheet.write("B1", "score")
        xlsx.close()

        html = Template("<h1>{{ title }}</h1>{{ body }}").render(
            title="Generated",
            body=markdown.markdown("**ready**"),
        )
        open("production.html", "w", encoding="utf-8").write(html)
        open("production.json", "w", encoding="utf-8").write(json.dumps({"ready": True}))

        writer = PdfWriter()
        writer.add_blank_page(width=72, height=72)
        with open("production.pdf", "wb") as handle:
            writer.write(handle)

        print("production packages ready")
        """.trimIndent(),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Python status=ok exitCode=0"))
    assertTrue(result, result.contains("production packages ready"))
    assertTrue(workspace.exportableFile("production-openpyxl.xlsx")!!.length() > 0)
    assertTrue(workspace.exportableFile("production-xlsxwriter.xlsx")!!.length() > 0)
    assertTrue(workspace.readFile("production.html").contains("<strong>ready</strong>"))
    assertTrue(workspace.exportableFile("production.pdf")!!.length() > 0)
  }

  @Test
  fun artifactInspectValidatesCommonWorkspaceArtifacts() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "artifact-inspect-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val python = PythonRunTool(workspace, ToolEventRecorder())
    val inspector = ArtifactInspectTool(workspace, ToolEventRecorder())

    val createResult = python.execute(
      PythonRunTool.Args(
        code = """
        import json
        import openpyxl
        from docx import Document
        from pypdf import PdfWriter

        open("artifact.html", "w", encoding="utf-8").write("<!doctype html><title>Artifact</title><h1>Ready</h1>")
        open("artifact.json", "w", encoding="utf-8").write(json.dumps({"status": "ready", "items": [1, 2]}))

        doc = Document()
        doc.add_heading("Artifact Document", level=1)
        doc.add_paragraph("Generated for inspection.")
        doc.save("artifact.docx")

        workbook = openpyxl.Workbook()
        sheet = workbook.active
        sheet.title = "Summary"
        sheet["A1"] = "status"
        sheet["B1"] = "=1+1"
        workbook.save("artifact.xlsx")

        writer = PdfWriter()
        writer.add_blank_page(width=72, height=72)
        with open("artifact.pdf", "wb") as handle:
            writer.write(handle)
        """.trimIndent(),
        snapshotBeforeRun = false,
      ),
    )
    assertTrue(createResult, createResult.contains("Python status=ok exitCode=0"))

    val json = inspector.execute(ArtifactInspectTool.Args(path = "artifact.json"))
    val html = inspector.execute(ArtifactInspectTool.Args(path = "artifact.html"))
    val docx = inspector.execute(ArtifactInspectTool.Args(path = "artifact.docx"))
    val xlsx = inspector.execute(ArtifactInspectTool.Args(path = "artifact.xlsx"))
    val pdf = inspector.execute(ArtifactInspectTool.Args(path = "artifact.pdf"))

    assertTrue(json, json.contains("\"format\":\"json\""))
    assertTrue(json, json.contains("\"status\":\"ok\""))
    assertTrue(html, html.contains("\"format\":\"html\""))
    assertTrue(html, html.contains("Artifact"))
    assertTrue(docx, docx.contains("\"format\":\"docx\""))
    assertTrue(docx, docx.contains("Artifact Document"))
    assertTrue(xlsx, xlsx.contains("\"format\":\"xlsx\""))
    assertTrue(xlsx, xlsx.contains("\"formulaCount\":1"))
    assertTrue(pdf, pdf.contains("\"format\":\"pdf\""))
    assertTrue(pdf, pdf.contains("\"pageCount\":1"))
  }

  @Test
  fun pythonPackageCatalogAndToolManifestAreExposed() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "python-catalog-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val installer = PythonPackageInstallTool(workspace, ToolEventRecorder())

    val installResult = installer.execute(PythonPackageInstallTool.Args(packageName = "openpyxl"))

    assertTrue(installResult, installResult.contains("\"status\":\"ok\""))
    assertTrue(installResult, installResult.contains("Package already available"))
    assertTrue(workspace.readFile(".flovera/python/wheel-catalog.json").contains("\"name\": \"openpyxl\""))
    assertTrue(workspace.readFile(".flovera/tools/manifest.json").contains("\"tools\""))
  }

  @Test
  fun pythonRunKeepsConversationSessionState() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "python-session-${System.currentTimeMillis()}")
    val tool = PythonRunTool(workspace, ToolEventRecorder())

    tool.execute(
      PythonRunTool.Args(code = "value = 41", sessionId = "conversation-1", snapshotBeforeRun = false),
    )
    val result = tool.execute(
      PythonRunTool.Args(code = "print(value + 1)", sessionId = "conversation-1", snapshotBeforeRun = false),
    )

    assertTrue(result, result.contains("session=conversation-1"))
    assertTrue(result, result.contains("42"))
  }

  @Test
  fun pythonRunRejectsEscapesAndBackgroundEntrypoints() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "python-boundary-${System.currentTimeMillis()}")
    val tool = PythonRunTool(workspace, ToolEventRecorder())

    val escape = tool.execute(
      PythonRunTool.Args(
        code = """open("/data/data/com.flovera.app/files/not-workspace.txt", "w").write("bad")""",
        snapshotBeforeRun = false,
      ),
    )
    val thread = tool.execute(
      PythonRunTool.Args(
        code = """
        import threading
        threading.Thread(target=lambda: None).start()
        """.trimIndent(),
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(escape, escape.contains("Path escapes workspace"))
    assertTrue(thread, thread.contains("threading.Thread.start is disabled"))
  }

  @Test
  fun pythonRunTimesOutBlockingSleep() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "python-timeout-${System.currentTimeMillis()}")
    val tool = PythonRunTool(workspace, ToolEventRecorder())

    val result = tool.execute(
      PythonRunTool.Args(
        code = """
        import time
        time.sleep(5)
        print("unreachable")
        """.trimIndent(),
        timeoutMs = 1000,
        snapshotBeforeRun = false,
      ),
    )

    assertTrue(result, result.contains("Python status=timeout exitCode=124"))
    assertFalse(result, result.contains("unreachable"))
  }

  @Test
  fun workspaceImportsSharedFilesToRootWithUniqueNames() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "shared-import-${System.currentTimeMillis()}")
    val shared = File(context.cacheDir, "shared-note.txt").apply { writeText("from another app") }

    val first = workspace.importUriToRoot(Uri.fromFile(shared))
    val second = workspace.importUriToRoot(Uri.fromFile(shared))

    assertEquals("Imported shared-note.txt", first)
    assertEquals("Imported shared-note (1).txt", second)
    assertEquals("from another app", workspace.readFile("shared-note.txt"))
    assertEquals("from another app", workspace.readFile("shared-note (1).txt"))
  }

  @Test
  fun workspaceSnapshotsRestoreFilesAndMetadata() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "snapshot-restore-${System.currentTimeMillis()}").also {
      it.ensureSeedFiles()
      it.ensureFloveraMetadata(FloveraSettingsView(provider = "deepseek", model = "deepseek-v4-pro", apiKeyRef = "deepseek.default"))
    }

    workspace.writeFile("notes/today.md", "alpha")
    val snapshot = workspace.createManualSnapshot("baseline", selectedHtmlPath = "index.html")
    workspace.writeFile("notes/today.md", "beta")
    workspace.writeFile(".flovera/settings-view.json", """{"provider":"changed"}""")

    val restored = workspace.restoreSnapshot(snapshot.id)

    assertEquals(snapshot.id, restored?.id)
    assertEquals("alpha", workspace.readFile("notes/today.md"))
    assertTrue(workspace.readFile(".flovera/settings-view.json").contains("deepseek-v4-pro"))
    assertTrue(workspace.deleteSnapshot(snapshot.id))
  }

  @Test
  fun manualSnapshotAfterRestoreCountsCurrentWorkspaceFiles() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "snapshot-count-${System.currentTimeMillis()}").also {
      it.ensureSeedFiles()
      it.writeFile("only-in-baseline.txt", "baseline")
    }
    val baseline = workspace.createManualSnapshot("baseline", selectedHtmlPath = "index.html")
    workspace.writeFile("new-a.txt", "a")
    workspace.writeFile("new-b.txt", "b")

    workspace.restoreSnapshot(baseline.id)
    val expectedFileCount = workspace.root.walkTopDown().count { it.isFile }
    val afterRestore = workspace.createManualSnapshot("after restore", selectedHtmlPath = "index.html")

    assertEquals(expectedFileCount, afterRestore.fileCount)
    assertEquals(baseline.fileCount, afterRestore.fileCount)
    assertEquals("File does not exist: new-a.txt", workspace.readFile("new-a.txt"))
    assertEquals("baseline", workspace.readFile("only-in-baseline.txt"))
  }

  @Test
  fun workspaceFloveraMetadataExposesCapabilitiesAndSettingsProposals() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "metadata-${System.currentTimeMillis()}"
    val controller = WorkspaceController(context, workspaceId).also {
      it.ensureSeedFiles()
      it.syncFloveraSettings(
        AppSettings(
          activeWorkspaceId = workspaceId,
          networkEnabled = true,
          webSearchEnabled = true,
          agentAuthorityMode = "assisted",
        ),
      )
    }
    val workspace = controller.runtimeWorkspace()

    workspace.writeFile(
      ".flovera/proposals/theme.json",
      """
      {
        "type": "settings",
        "title": "Use softer theme",
        "reason": "Match current workspace page",
        "changes": {
          "themeColor": "#C989B8",
          "selectedHtmlPath": "index.html"
        }
      }
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    workspace.writeFile(
      ".flovera/proposals/search-tool.json",
      """
      {
        "type": "tool",
        "title": "Add workspace search",
        "reason": "Find files without scanning manually",
        "name": "workspace_search",
        "description": "Search workspace file contents",
        "requestedCapabilities": ["filesystem"],
        "permissions": ["read workspace"]
      }
      """.trimIndent(),
      createAutoSnapshot = false,
    )

    val capabilities = workspace.readFile(".flovera/capabilities.json")
    val settingsView = workspace.readFile(".flovera/settings-view.json")
    val proposals = workspace.listSettingsProposals()
    val toolProposals = workspace.listControlledToolProposals()

    assertTrue(capabilities.contains("\"networkTools\": true"))
    assertTrue(capabilities.contains("\"pythonRuntime\": true"))
    assertTrue(capabilities.contains("\"pythonPackageInstall\": true"))
    assertTrue(capabilities.contains("\"pythonPackageCatalogPath\""))
    assertTrue(capabilities.contains("\"pythonBuiltInPackages\""))
    assertTrue(capabilities.contains("\"openpyxl\""))
    assertTrue(capabilities.contains("\"artifactInspect\": true"))
    assertTrue(capabilities.contains("\"artifactInspectFormats\""))
    assertTrue(capabilities.contains("\"workspaceSearch\": true"))
    assertTrue(capabilities.contains("\"workspaceSearchScopes\""))
    assertTrue(capabilities.contains("\"workspace_app_metadata\""))
    assertTrue(capabilities.contains("\"webSearch\": true"))
    assertTrue(capabilities.contains("\"previewFormats\""))
    assertTrue(capabilities.contains("\"json\""))
    assertTrue(capabilities.contains("\"csv\""))
    assertTrue(capabilities.contains("\"pdf\""))
    assertTrue(capabilities.contains("\"modelContextOverrides\": true"))
    assertTrue(capabilities.contains("\"reasoningEffort\": true"))
    assertTrue(capabilities.contains("\"directSettingsWrite\": false"))
    assertTrue(capabilities.contains("\"supportedAuthorityModes\""))
    assertTrue(capabilities.contains("\"full\""))
    assertTrue(capabilities.contains("\"pendingAuthorityModes\": []"))
    assertTrue(capabilities.contains("\"customOpenAICompatibleProvider\": true"))
    assertTrue(capabilities.contains("\"openRouterRouting\": true"))
    assertTrue(capabilities.contains("\"customUrlRouting\": true"))
    assertTrue(capabilities.contains("\"providerProfiles\": true"))
    assertTrue(capabilities.contains("\"providerProfileCatalog\""))
    assertTrue(capabilities.contains("\"id\": \"alibaba\""))
    assertTrue(capabilities.contains("\"id\": \"moonshot\""))
    assertTrue(capabilities.contains("\"id\": \"zai\""))
    assertTrue(capabilities.contains("\"id\": \"huggingface\""))
    assertTrue(capabilities.contains("\"id\": \"ollama-cloud\""))
    assertTrue(capabilities.contains("\"id\": \"ai-gateway\""))
    assertTrue(capabilities.contains("\"id\": \"opencode-go\""))
    assertTrue(capabilities.contains("\"id\": \"xiaomi\""))
    assertTrue(capabilities.contains("\"id\": \"lmstudio\""))
    assertTrue(capabilities.contains("\"id\": \"tencent-tokenhub\""))
    assertTrue(capabilities.contains("\"id\": \"bedrock\""))
    assertTrue(capabilities.contains("\"id\": \"gemini\""))
    assertTrue(capabilities.contains("\"id\": \"google-gemini-cli\""))
    assertTrue(capabilities.contains("\"id\": \"azure-foundry\""))
    assertTrue(capabilities.contains("\"id\": \"xai\""))
    assertTrue(capabilities.contains("\"id\": \"copilot\""))
    assertTrue(capabilities.contains("\"id\": \"copilot-acp\""))
    assertTrue(capabilities.contains("\"id\": \"openai-codex\""))
    assertTrue(capabilities.contains("\"id\": \"nous\""))
    assertTrue(capabilities.contains("\"id\": \"qwen-oauth\""))
    assertTrue(capabilities.contains("\"id\": \"minimax\""))
    assertTrue(capabilities.contains("\"id\": \"minimax-cn\""))
    assertTrue(capabilities.contains("\"id\": \"minimax-oauth\""))
    assertTrue(capabilities.contains("\"id\": \"custom-openai\""))
    assertTrue(capabilities.contains("\"aliases\""))
    assertTrue(capabilities.contains("\"suggestedModels\""))
    assertTrue(capabilities.contains("\"modelContexts\""))
    assertTrue(capabilities.contains("\"supportsReasoning\""))
    assertTrue(capabilities.contains("\"qwen3-coder-plus\""))
    assertTrue(capabilities.contains("\"contextWindowTokens\": 1000000"))
    assertTrue(capabilities.contains("\"source\": \"hermes_model_metadata\""))
    assertTrue(capabilities.contains("\"baseUrl\": \"https://dashscope-intl.aliyuncs.com/compatible-mode/v1\""))
    assertTrue(capabilities.contains("\"baseUrl\": \"https://ai-gateway.vercel.sh/v1\""))
    assertTrue(capabilities.contains("\"defaultHeaderNames\""))
    assertTrue(capabilities.contains("\"HTTP-Referer\""))
    assertTrue(capabilities.contains("\"X-Title\""))
    assertTrue(capabilities.contains("\"requestCompatibilityModes\""))
    assertTrue(capabilities.contains("\"requestHooks\""))
    assertTrue(capabilities.contains("\"omit_request_fields\""))
    assertTrue(capabilities.contains("\"add_request_fields\""))
    assertTrue(capabilities.contains("\"inject_kimi_thinking\""))
    assertTrue(capabilities.contains("\"inject_openrouter_routing\""))
    assertTrue(capabilities.contains("\"inject_lmstudio_reasoning\""))
    assertTrue(capabilities.contains("\"inject_tencent_tokenhub_reasoning\""))
    assertTrue(capabilities.contains("\"inject_nous_portal_reasoning\""))
    assertTrue(capabilities.contains("\"inject_qwen_portal_request_shape\""))
    assertTrue(capabilities.contains("\"inject_copilot_reasoning\""))
    assertTrue(capabilities.contains("\"transport\""))
    assertTrue(capabilities.contains("\"flovera_openai_compatible_chat_completions\""))
    assertTrue(capabilities.contains("\"flovera_codex_responses\""))
    assertTrue(capabilities.contains("\"koog_google_gemini_native\""))
    assertTrue(capabilities.contains("\"koog_bedrock_converse\""))
    assertTrue(capabilities.contains("\"flovera_anthropic_messages\""))
    assertTrue(capabilities.contains("\"flovera_google_cloud_code_assist\""))
    assertTrue(capabilities.contains("\"flovera_external_process\""))
    assertTrue(capabilities.contains("\"omittedRequestFields\""))
    assertTrue(capabilities.contains("\"addedRequestFields\""))
    assertTrue(capabilities.contains("\"reasoning\""))
    assertTrue(capabilities.contains("\"temperature\""))
    assertTrue(capabilities.contains("\"ollama\""))
    assertTrue(capabilities.contains("\"anthropic_messages\""))
    assertTrue(capabilities.contains("\"bedrock_converse\""))
    assertTrue(capabilities.contains("\"codex_responses\""))
    assertTrue(capabilities.contains("\"responsesPath\""))
    assertTrue(capabilities.contains("\"responses\""))
    assertTrue(capabilities.contains("\"authType\": \"oauth_device_code\""))
    assertTrue(capabilities.contains("\"authType\": \"oauth_external\""))
    assertTrue(capabilities.contains("\"authType\": \"copilot\""))
    assertTrue(capabilities.contains("\"authType\": \"external_process\""))
    assertTrue(capabilities.contains("\"providerRequestHooks\": true"))
    assertTrue(capabilities.contains("\"customRequestBody\": false"))
    assertTrue(capabilities.contains("\"controlledToolProposals\": true"))
    assertTrue(capabilities.contains("\"controlledMcpProposals\": true"))
    assertTrue(capabilities.contains("\"directToolInstall\": false"))
    assertTrue(capabilities.contains("\"directMcpInstall\": false"))
    assertTrue(settingsView.contains("\"modelContextSource\""))
    assertTrue(settingsView.contains("\"providerApiMode\""))
    assertTrue(settingsView.contains("\"providerTransport\""))
    assertTrue(settingsView.contains("\"flovera_deepseek_chat_completions\""))
    assertTrue(settingsView.contains("\"providerBaseUrl\""))
    assertTrue(settingsView.contains("\"providerModelsUrl\""))
    assertTrue(settingsView.contains("\"providerMessagesPath\""))
    assertTrue(settingsView.contains("\"providerResponsesPath\""))
    assertTrue(settingsView.contains("\"providerModelsPath\""))
    assertTrue(settingsView.contains("\"providerAuthType\""))
    assertTrue(settingsView.contains("\"providerDefaultHeaderNames\""))
    assertTrue(settingsView.contains("\"providerSupportsHealthCheck\""))
    assertTrue(settingsView.contains("\"customOpenAICompatibilityMode\""))
    assertTrue(settingsView.contains("\"reasoningEffort\""))
    assertTrue(settingsView.contains("\"openRouterProviderPreferences\""))
    assertTrue(settingsView.contains("\"openRouterMinCodingScore\""))
    assertTrue(settingsView.contains("\"providerInjectsOllamaNumCtx\""))
    assertTrue(settingsView.contains("\"providerInjectsOpenRouterRouting\""))
    assertTrue(settingsView.contains("\"providerRequestHookIds\""))
    assertTrue(settingsView.contains("\"providerRequestOmittedFields\""))
    assertTrue(settingsView.contains("\"providerRequestAddedFields\""))
    assertTrue(settingsView.contains("\"modelSupportsReasoning\""))
    assertTrue(settingsView.contains("\"compressionThresholdPercent\""))
    assertTrue(settingsView.contains("\"customOpenAIBaseUrl\""))
    assertTrue(settingsView.contains("\"customOpenAIChatCompletionsPath\""))
    assertTrue(settingsView.contains("\"recentHtmlPaths\""))
    assertEquals(1, proposals.size)
    assertEquals("Use softer theme", proposals.first().title)
    assertEquals("#C989B8", proposals.first().changes.themeColor)
    assertEquals(1, toolProposals.size)
    assertEquals("tool", toolProposals.first().type)
    assertEquals("workspace_search", toolProposals.first().name)
    assertFalse(workspace.deleteSettingsProposal(".flovera/proposals/search-tool.json"))
    assertTrue(workspace.deleteControlledToolProposal(".flovera/proposals/search-tool.json"))
    assertTrue(workspace.listControlledToolProposals().isEmpty())
  }

  @Test
  fun fullAuthorityCapabilitiesExposeDirectSettingsWrite() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "test-full-authority-capabilities-${System.currentTimeMillis()}")
    workspace.ensureFloveraMetadata(FloveraSettingsView(authorityMode = "full"))

    val capabilities = workspace.readFile(".flovera/capabilities.json")

    assertTrue(capabilities.contains("\"authorityMode\": \"full\""))
    assertTrue(capabilities.contains("\"directSettingsWrite\": true"))
    assertTrue(capabilities.contains("\"supportedAuthorityModes\""))
    assertTrue(capabilities.contains("\"full\""))
    assertTrue(capabilities.contains("\"pendingAuthorityModes\": []"))
  }

  @Test
  fun workspaceAutomaticSnapshotsKeepLatestThreeBeforeFileChanges() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "snapshot-auto-${System.currentTimeMillis()}")

    workspace.writeFile("counter.txt", "one")
    workspace.writeFile("counter.txt", "two")
    workspace.writeFile("counter.txt", "three")
    workspace.writeFile("counter.txt", "four")

    val automatic = workspace.listSnapshots().filter { it.kind == "auto" }

    assertEquals(3, automatic.size)
    assertEquals("three", workspace.restoreSnapshot(automatic.first().id)?.let { workspace.readFile("counter.txt") })
  }
}
