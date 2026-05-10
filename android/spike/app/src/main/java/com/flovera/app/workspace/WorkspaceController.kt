package com.flovera.app.workspace

import android.content.Context
import java.io.File

data class WorkspaceSnapshot(
  val files: String,
  val tree: WorkspaceFileNode,
  val htmlFiles: List<String>,
  val selectedHtmlPath: String,
  val selectedHtmlUrl: String?,
)

class WorkspaceController(context: Context, workspaceId: String) {
  private val workspace = WorkspaceManager(context, workspaceId)

  fun ensureSeedFiles() {
    workspace.ensureSeedFiles()
  }

  fun runtimeWorkspace(): WorkspaceManager = workspace

  fun readAgentRules(): String = workspace.readAgentRules()

  fun writeAgentRules(content: String): String = workspace.writeFile("AGENT.md", content)

  fun rename(path: String, newName: String): String = workspace.rename(path, newName)

  fun exportableFile(path: String): File? = workspace.exportableFile(path)

  fun mimeType(path: String): String = workspace.mimeType(path)

  fun displayUrl(path: String): String? = workspace.displayUrl(path)

  fun snapshot(currentSelectedHtmlPath: String): WorkspaceSnapshot {
    val htmlFiles = workspace.listHtmlFiles()
    val selectedHtmlPath = chooseHtmlPath(currentSelectedHtmlPath, htmlFiles)
    return WorkspaceSnapshot(
      files = workspace.listFiles("."),
      tree = workspace.fileTree(),
      htmlFiles = htmlFiles,
      selectedHtmlPath = selectedHtmlPath,
      selectedHtmlUrl = workspace.displayUrl(selectedHtmlPath),
    )
  }

  private fun chooseHtmlPath(current: String, htmlFiles: List<String>): String {
    return when {
      current in htmlFiles -> current
      else -> ""
    }
  }
}
