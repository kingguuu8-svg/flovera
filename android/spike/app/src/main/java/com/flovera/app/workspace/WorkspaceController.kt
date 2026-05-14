package com.flovera.app.workspace

import android.content.Context
import android.net.Uri
import com.flovera.app.config.AppSettings
import java.io.File

data class WorkspaceSnapshot(
  val files: String,
  val tree: WorkspaceFileNode,
  val htmlFiles: List<String>,
  val selectedHtmlPath: String,
  val selectedHtmlUrl: String?,
  val workspaceRootUrl: String,
  val snapshots: List<WorkspaceSnapshotRecord>,
  val settingsProposals: List<WorkspaceSettingsProposal>,
)

class WorkspaceController(context: Context, workspaceId: String) {
  private val workspace = WorkspaceManager(context, workspaceId)

  fun ensureSeedFiles() {
    workspace.ensureSeedFiles()
  }

  fun runtimeWorkspace(): WorkspaceManager = workspace

  fun readAgentRules(): String = workspace.readAgentRules()

  fun writeAgentRules(content: String): String = workspace.writeFile("AGENT.md", content)

  fun syncFloveraSettings(settings: AppSettings) {
    workspace.ensureFloveraMetadata(
      FloveraSettingsView(
        provider = settings.provider,
        model = settings.model,
        activeWorkspaceId = settings.activeWorkspaceId,
        activeSessionId = settings.activeSessionId,
        selectedHtmlPath = settings.selectedHtmlPath,
        pinnedHtmlPaths = settings.pinnedHtmlPaths,
        maxAgentIterations = settings.maxAgentIterations,
        networkEnabled = settings.networkEnabled,
        webSearchEnabled = settings.webSearchEnabled,
        language = settings.language,
        themeMode = settings.themeMode,
        themeColor = settings.themeColor,
        authorityMode = settings.agentAuthorityMode,
        apiKeyRef = if (settings.apiKeyFor(settings.provider).isBlank()) "" else "${settings.provider}.default",
        braveSearchApiKeyRef = if (settings.braveSearchApiKey.isBlank()) "" else "brave.default",
      ),
    )
  }

  fun importSharedFile(uri: Uri): String = workspace.importUriToRoot(uri)

  fun rename(path: String, newName: String): String = workspace.rename(path, newName)

  fun exportableFile(path: String): File? = workspace.exportableFile(path)

  fun mimeType(path: String): String = workspace.mimeType(path)

  fun displayUrl(path: String): String? = workspace.displayUrl(path)

  fun createSnapshot(name: String, selectedHtmlPath: String): WorkspaceSnapshotRecord {
    return workspace.createManualSnapshot(name, selectedHtmlPath)
  }

  fun restoreSnapshot(id: String): WorkspaceSnapshotRecord? = workspace.restoreSnapshot(id)

  fun deleteSnapshot(id: String): Boolean = workspace.deleteSnapshot(id)

  fun listSettingsProposals(): List<WorkspaceSettingsProposal> = workspace.listSettingsProposals()

  fun deleteSettingsProposal(path: String): Boolean = workspace.deleteSettingsProposal(path)

  fun snapshot(currentSelectedHtmlPath: String): WorkspaceSnapshot {
    val htmlFiles = workspace.listHtmlFiles()
    val selectedHtmlPath = chooseHtmlPath(currentSelectedHtmlPath, htmlFiles)
    return WorkspaceSnapshot(
      files = workspace.listFiles("."),
      tree = workspace.fileTree(),
      htmlFiles = htmlFiles,
      selectedHtmlPath = selectedHtmlPath,
      selectedHtmlUrl = workspace.displayUrl(selectedHtmlPath),
      workspaceRootUrl = workspace.rootUrl(),
      snapshots = workspace.listSnapshots(),
      settingsProposals = workspace.listSettingsProposals(),
    )
  }

  private fun chooseHtmlPath(current: String, htmlFiles: List<String>): String {
    return when {
      current in htmlFiles -> current
      else -> ""
    }
  }
}
