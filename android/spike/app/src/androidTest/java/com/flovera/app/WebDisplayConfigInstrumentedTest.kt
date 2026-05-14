package com.flovera.app

import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.config.AppSettings
import com.flovera.app.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDisplayConfigInstrumentedTest {
  @Test
  fun defaultSettingsDoNotAutoOpenHtml() {
    val settings = AppSettings()

    assertEquals("", settings.selectedHtmlPath)
  }

  @Test
  fun blankHtmlSelectionHasNoDisplayUrlEvenWhenWorkspaceHasHtml() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "web-display-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }

    assertTrue(workspace.listHtmlFiles().contains("index.html"))
    assertNull(workspace.displayUrl(""))
  }
}
