package com.flovera.app

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.workspace.WorkspaceController
import com.flovera.app.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
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
}
