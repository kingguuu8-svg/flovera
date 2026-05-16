package com.flovera.app.workspace

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.flovera.app.config.SettingsProposalChanges
import com.flovera.app.storage.readUtf8Text
import com.flovera.app.storage.writeBytesAtomically
import com.flovera.app.storage.writeStreamAtomically
import com.flovera.app.storage.writeUtf8TextAtomically
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class WorkspaceFileNode(
  val name: String,
  val path: String,
  val isDirectory: Boolean,
  val sizeBytes: Long,
  val children: List<WorkspaceFileNode> = emptyList(),
)

class WorkspaceManager(context: Context, workspaceId: String = "default") {
  private val appContext = context.applicationContext
  private val workspacesRoot = File(context.filesDir, "workspaces")
  val root: File = File(workspacesRoot, workspaceId).apply { mkdirs() }
  private val snapshotStore = WorkspaceSnapshotStore(appContext, workspaceId, root)
  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun ensureSeedFiles() {
    writeFile(
      path = "README.md",
      content = """
        # Android Agent Workspace

        This workspace is owned by the Android app. The agent can read, write, and edit files here through approved tools.
      """.trimIndent(),
      overwrite = false,
      createAutoSnapshot = false,
    )
    writeFile(
      path = "AGENT.md",
      content = """
        # Agent Rules

        - Keep all file paths relative to this workspace.
        - Prefer plain HTML, CSS, JavaScript, Markdown, and JSON files.
        - Do not assume Python, npm, git, bash, or Linux tools exist on Android.
        - Do not use emoji unless the user explicitly asks for them.
        - Workspace HTML can call controlled Android app events through window.Flovera:
          - window.Flovera.toast("message")
          - window.Flovera.notify(JSON.stringify({ title: "Title", body: "Body" }))
          - window.Flovera.postEvent(JSON.stringify({ type: "notification", title: "Title", body: "Body" }))
        - Always check window.Flovera exists before calling it, and keep these calls user-visible and intentional.
      """.trimIndent(),
      overwrite = false,
      createAutoSnapshot = false,
    )
    writeFile(
      path = "index.html",
      content = """
        <!doctype html>
        <html>
          <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Android Workspace</title>
            <style>
              body {
                margin: 0;
                min-height: 100vh;
                display: grid;
                place-items: center;
                font-family: system-ui, sans-serif;
                color: #17202a;
                background: #f6f8fb;
              }
              main {
                width: min(720px, calc(100vw - 48px));
              }
              h1 {
                margin: 0 0 12px;
                font-size: 28px;
              }
              p {
                margin: 0;
                line-height: 1.6;
              }
            </style>
          </head>
          <body>
            <main>
              <h1>Android Workspace</h1>
              <p>Select an HTML file from the app menu, or ask the agent to create one in this workspace.</p>
            </main>
          </body>
        </html>
      """.trimIndent(),
      overwrite = false,
      createAutoSnapshot = false,
    )
    ensureFloveraMetadata()
  }

  fun ensureFloveraMetadata(
    settingsView: FloveraSettingsView = FloveraSettingsView(),
    providerProfileCatalog: List<FloveraProviderProfileView> = emptyList(),
  ) {
    writeFile(
      path = ".flovera/manifest.json",
      content = json.encodeToString(
        FloveraWorkspaceManifest(
          workspaceId = root.name,
          settingsViewPath = ".flovera/settings-view.json",
          capabilitiesPath = ".flovera/capabilities.json",
          proposalsPath = ".flovera/proposals",
        ),
      ),
      overwrite = false,
      createAutoSnapshot = false,
    )
    writeFile(
      path = ".flovera/settings-view.json",
      content = json.encodeToString(settingsView),
      overwrite = true,
      createAutoSnapshot = false,
    )
    writeFile(
      path = ".flovera/capabilities.json",
      content = json.encodeToString(FloveraCapabilities.fromSettings(settingsView, providerProfileCatalog)),
      overwrite = true,
      createAutoSnapshot = false,
    )
    safeFile(".flovera/proposals").mkdirs()
  }

  fun readAgentRules(): String = readFile("AGENT.md")

  fun listSnapshots(): List<WorkspaceSnapshotRecord> = snapshotStore.list()

  fun listSettingsProposals(): List<WorkspaceSettingsProposal> {
    val proposalsDir = safeFile(".flovera/proposals")
    return proposalsDir.listFiles()
      ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
      ?.mapNotNull { file ->
        runCatching {
          val decoded = json.decodeFromString<WorkspaceSettingsProposalFile>(readUtf8Text(file))
          if (!decoded.type.equals("settings", ignoreCase = true)) return@mapNotNull null
          WorkspaceSettingsProposal(
            path = relativeToRoot(file),
            title = decoded.title.ifBlank { file.nameWithoutExtension },
            reason = decoded.reason,
            changes = decoded.changes,
            createdAtMillis = file.lastModified(),
          )
        }.getOrNull()
      }
      ?.sortedByDescending { it.createdAtMillis }
      ?: emptyList()
  }

  fun listControlledToolProposals(): List<WorkspaceControlledToolProposal> {
    val proposalsDir = safeFile(".flovera/proposals")
    return proposalsDir.listFiles()
      ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
      ?.mapNotNull { file ->
        runCatching {
          val decoded = json.decodeFromString<WorkspaceControlledToolProposalFile>(readUtf8Text(file))
          val normalizedType = decoded.type.lowercase()
          if (normalizedType !in setOf("tool", "mcp")) return@mapNotNull null
          WorkspaceControlledToolProposal(
            path = relativeToRoot(file),
            type = normalizedType,
            title = decoded.title.ifBlank { file.nameWithoutExtension },
            reason = decoded.reason,
            name = decoded.name,
            description = decoded.description,
            command = decoded.command,
            endpoint = decoded.endpoint,
            requestedCapabilities = decoded.requestedCapabilities,
            permissions = decoded.permissions,
            createdAtMillis = file.lastModified(),
          )
        }.getOrNull()
      }
      ?.sortedByDescending { it.createdAtMillis }
      ?: emptyList()
  }

  fun deleteSettingsProposal(path: String): Boolean {
    val file = safeFile(path)
    if (!file.isFile || !relativeToRoot(file).startsWith(".flovera/proposals/")) return false
    val decoded = runCatching {
      json.decodeFromString<WorkspaceSettingsProposalFile>(readUtf8Text(file))
    }.getOrNull() ?: return false
    if (!decoded.type.equals("settings", ignoreCase = true)) return false
    return file.delete()
  }

  fun deleteControlledToolProposal(path: String): Boolean {
    val file = safeFile(path)
    if (!file.isFile || !relativeToRoot(file).startsWith(".flovera/proposals/")) return false
    val decoded = runCatching {
      json.decodeFromString<WorkspaceControlledToolProposalFile>(readUtf8Text(file))
    }.getOrNull() ?: return false
    if (decoded.type.lowercase() !in setOf("tool", "mcp")) return false
    return file.delete()
  }

  fun createManualSnapshot(name: String, selectedHtmlPath: String = ""): WorkspaceSnapshotRecord {
    return snapshotStore.createManual(name, selectedHtmlPath)
  }

  fun restoreSnapshot(id: String): WorkspaceSnapshotRecord? = snapshotStore.restore(id)

  fun deleteSnapshot(id: String): Boolean = snapshotStore.delete(id)

  fun listHtmlFiles(): List<String> {
    if (!root.exists()) return emptyList()
    return root.walkTopDown()
      .filter { it.isFile && it.extension.equals("html", ignoreCase = true) }
      .map { relativeToRoot(it) }
      .sorted()
      .toList()
  }

  fun fileTree(): WorkspaceFileNode {
    return toNode(root)
  }

  fun displayUrl(path: String): String? {
    val file = safeFile(path)
    if (!file.exists() || !file.isFile || !file.extension.equals("html", ignoreCase = true)) return null
    return file.toURI().toASCIIString()
  }

  fun rootUrl(): String = root.toURI().toASCIIString()

  fun exportableFile(path: String): File? {
    val file = safeFile(path)
    if (!file.exists() || !file.isFile) return null
    return file
  }

  fun mimeType(path: String): String {
    val extension = safeFile(path).extension.lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: when (extension) {
      "html", "htm" -> "text/html"
      "css" -> "text/css"
      "js" -> "text/javascript"
      "json" -> "application/json"
      "md", "txt" -> "text/plain"
      else -> "application/octet-stream"
    }
  }

  fun listFiles(path: String = "."): String {
    val dir = safeFile(path)
    if (!dir.exists()) return "Path does not exist: $path"
    if (!dir.isDirectory) return "${relativeToRoot(dir)} (${dir.length()} bytes)"
    return dir.listFiles()
      ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
      ?.joinToString("\n") { file ->
        val suffix = if (file.isDirectory) "/" else " (${file.length()} bytes)"
        relativeToRoot(file) + suffix
      }
      ?: ""
  }

  fun readFile(path: String): String {
    val file = safeFile(path)
    if (!file.exists()) return "File does not exist: $path"
    if (!file.isFile) return "Path is not a file: $path"
    return readUtf8Text(file)
  }

  fun readFilePreview(path: String, maxChars: Int): String {
    val file = safeFile(path)
    if (!file.exists()) return "File does not exist: $path"
    if (!file.isFile) return "Path is not a file: $path"
    file.reader(Charsets.UTF_8).use { reader ->
      val buffer = CharArray(maxChars + 1)
      val count = reader.read(buffer)
      if (count <= maxChars) return String(buffer, 0, count.coerceAtLeast(0))
      return String(buffer, 0, maxChars) +
        "\n\n[truncated: showing first $maxChars chars of ${relativeToRoot(file)}; file is ${file.length()} bytes]"
    }
  }

  fun writeFile(
    path: String,
    content: String,
    overwrite: Boolean = true,
    createAutoSnapshot: Boolean = true,
  ): String {
    val file = safeFile(path)
    if (file.exists() && !overwrite) return "File already exists: ${relativeToRoot(file)}"
    if (createAutoSnapshot) {
      snapshotStore.createAutomatic("write_file:${relativeToRoot(file)}")
    }
    writeUtf8TextAtomically(file, content)
    return "Wrote ${content.length} chars to ${relativeToRoot(file)}"
  }

  fun writeBytes(
    path: String,
    content: ByteArray,
    overwrite: Boolean = true,
    createAutoSnapshot: Boolean = true,
  ): String {
    val file = safeFile(path)
    if (file.exists() && !overwrite) return "File already exists: ${relativeToRoot(file)}"
    if (createAutoSnapshot) {
      snapshotStore.createAutomatic("write_bytes:${relativeToRoot(file)}")
    }
    writeBytesAtomically(file, content)
    return "Wrote ${content.size} bytes to ${relativeToRoot(file)}"
  }

  fun importUriToRoot(uri: Uri): String {
    val name = uniqueRootFileName(sanitizeRootFileName(displayName(uri) ?: uri.lastPathSegment.orEmpty()))
    val target = safeFile(name)
    val input = appContext.contentResolver.openInputStream(uri) ?: return "Could not open shared file: $uri"
    snapshotStore.createAutomatic("import:${relativeToRoot(target)}")
    writeStreamAtomically(target, input)
    return "Imported ${relativeToRoot(target)}"
  }

  fun editFile(path: String, oldText: String, newText: String): String {
    val file = safeFile(path)
    if (!file.exists() || !file.isFile) return "File does not exist: $path"
    val current = readUtf8Text(file)
    if (!current.contains(oldText)) return "Old text was not found in $path"
    val updated = current.replace(oldText, newText, ignoreCase = false)
    snapshotStore.createAutomatic("edit_file:${relativeToRoot(file)}")
    writeUtf8TextAtomically(file, updated)
    return "Edited ${relativeToRoot(file)}"
  }

  fun rename(path: String, newName: String): String {
    val file = safeFile(path)
    if (!file.exists()) return "Path does not exist: $path"
    val normalized = newName.trim()
    if (normalized.isBlank() || normalized.contains("/") || normalized.contains("\\") || normalized == "." || normalized == "..") {
      return "Invalid file name: $newName"
    }
    val target = File(file.parentFile, normalized).canonicalFile
    val canonicalRoot = root.canonicalFile
    if (target.path != canonicalRoot.path && !target.path.startsWith(canonicalRoot.path + File.separator)) {
      return "Path escapes workspace: $newName"
    }
    if (target.exists()) return "Target already exists: ${relativeToRoot(target)}"
    snapshotStore.createAutomatic("rename:${relativeToRoot(file)}")
    return if (file.renameTo(target)) {
      "Renamed ${relativeToRoot(file)} to ${relativeToRoot(target)}"
    } else {
      "Failed to rename ${relativeToRoot(file)}"
    }
  }

  private fun toNode(file: File): WorkspaceFileNode {
    val isDirectory = file.isDirectory
    val children = if (isDirectory) {
      file.listFiles()
        ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        ?.map { toNode(it) }
        ?: emptyList()
    } else {
      emptyList()
    }
    return WorkspaceFileNode(
      name = if (file == root) "workspace" else file.name,
      path = relativeToRoot(file).let { if (it == ".") "" else it },
      isDirectory = isDirectory,
      sizeBytes = if (isDirectory) 0L else file.length(),
      children = children,
    )
  }

  private fun safeFile(path: String): File {
    val requested = File(root, path).canonicalFile
    val canonicalRoot = root.canonicalFile
    require(requested.path == canonicalRoot.path || requested.path.startsWith(canonicalRoot.path + File.separator)) {
      "Path escapes workspace: $path"
    }
    return requested
  }

  private fun displayName(uri: Uri): String? {
    return runCatching {
      appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        } else {
          null
        }
      }
    }.getOrNull()
  }

  private fun sanitizeRootFileName(name: String): String {
    val leaf = name.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = leaf.map { char ->
      when {
        char.isISOControl() -> '_'
        char == '/' || char == '\\' || char == ':' || char == '*' || char == '?' || char == '"' || char == '<' || char == '>' || char == '|' -> '_'
        else -> char
      }
    }.joinToString("").trim().trim('.')
    return cleaned.ifBlank { "shared-file" }
  }

  private fun uniqueRootFileName(name: String): String {
    val base = name.substringBeforeLast('.', name)
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
    fun candidate(index: Int): String {
      val suffix = if (index == 0) "" else " ($index)"
      return if (extension.isBlank() || base == name) "$base$suffix" else "$base$suffix.$extension"
    }
    var index = 0
    while (safeFile(candidate(index)).exists()) index += 1
    return candidate(index)
  }

  private fun relativeToRoot(file: File): String {
    return file.canonicalFile.toRelativeString(root.canonicalFile).ifBlank { "." }
  }
}

@Serializable
data class FloveraWorkspaceManifest(
  val version: Int = 1,
  val workspaceId: String,
  val settingsViewPath: String,
  val capabilitiesPath: String,
  val proposalsPath: String,
)

@Serializable
data class FloveraSettingsView(
  val provider: String = "",
  val providerApiMode: String = "",
  val providerBaseUrl: String = "",
  val providerModelsUrl: String = "",
  val providerAuthType: String = "api_key",
  val providerSupportsHealthCheck: Boolean = true,
  val model: String = "",
  val activeWorkspaceId: String = "",
  val activeSessionId: String? = null,
  val selectedHtmlPath: String = "",
  val pinnedHtmlPaths: List<String> = emptyList(),
  val recentHtmlPaths: List<String> = emptyList(),
  val maxAgentIterations: Int = 0,
  val networkEnabled: Boolean = false,
  val webSearchEnabled: Boolean = false,
  val language: String = "",
  val themeMode: String = "",
  val themeColor: String = "",
  val authorityMode: String = "safe",
  val deepSeekThinkingEffort: String = "high",
  val customOpenAIBaseUrl: String = "",
  val customOpenAIChatCompletionsPath: String = "/v1/chat/completions",
  val customOpenAICompatibilityMode: String = "generic",
  val providerInjectsOllamaNumCtx: Boolean = false,
  val providerRequestOmittedFields: List<String> = emptyList(),
  val modelContextWindowTokens: Int? = null,
  val modelContextSource: String = "unknown",
  val tokenUsageSource: String = "estimate",
  val compressionThresholdPercent: Int? = null,
  val apiKeyRef: String = "",
  val braveSearchApiKeyRef: String = "",
)

@Serializable
data class FloveraProviderProfileView(
  val id: String,
  val label: String,
  val apiMode: String,
  val aliases: List<String> = emptyList(),
  val defaultModel: String,
  val suggestedModels: List<String> = emptyList(),
  val modelContexts: Map<String, FloveraModelContextView> = emptyMap(),
  val baseUrl: String = "",
  val modelsUrl: String = "",
  val authType: String = "api_key",
  val supportsHealthCheck: Boolean = true,
  val defaultMaxTokens: Int? = null,
  val defaultAuxModel: String = "",
  val requestCompatibilityModes: List<String> = listOf("generic"),
  val omittedRequestFields: List<String> = emptyList(),
  val customRequestBody: Boolean = false,
)

@Serializable
data class FloveraModelContextView(
  val contextWindowTokens: Int? = null,
  val source: String = "unknown",
  val usageSource: String = "estimate",
  val compressionThresholdPercent: Int? = null,
)

@Serializable
data class FloveraCapabilities(
  val workspaceFiles: Boolean = true,
  val webPreview: Boolean = true,
  val previewFormats: List<String> = listOf("html", "markdown", "json", "csv", "text", "image", "pdf"),
  val snapshots: Boolean = true,
  val notifications: Boolean = true,
  val networkTools: Boolean = false,
  val pythonRuntime: Boolean = false,
  val webSearch: Boolean = false,
  val settingsView: Boolean = true,
  val settingsProposals: Boolean = true,
  val controlledToolProposals: Boolean = true,
  val controlledMcpProposals: Boolean = true,
  val modelContextOverrides: Boolean = true,
  val deepSeekThinkingEffort: Boolean = true,
  val customOpenAICompatibleProvider: Boolean = true,
  val customUrlRouting: Boolean = true,
  val providerProfiles: Boolean = true,
  val providerApiModes: List<String> = listOf("chat_completions", "anthropic_messages"),
  val providerProfileCatalog: List<FloveraProviderProfileView> = emptyList(),
  val providerRequestHooks: Boolean = true,
  val customRequestBody: Boolean = false,
  val directSettingsWrite: Boolean = false,
  val directToolInstall: Boolean = false,
  val directMcpInstall: Boolean = false,
  val executableToolExpansion: Boolean = false,
  val proposalTypes: List<String> = listOf("settings", "tool", "mcp"),
  val authorityMode: String = "safe",
  val supportedAuthorityModes: List<String> = listOf("safe", "assisted"),
  val pendingAuthorityModes: List<String> = listOf("full"),
) {
  companion object {
    fun fromSettings(
      settingsView: FloveraSettingsView,
      providerProfileCatalog: List<FloveraProviderProfileView> = emptyList(),
    ): FloveraCapabilities {
      return FloveraCapabilities(
        networkTools = settingsView.networkEnabled,
        webSearch = settingsView.webSearchEnabled,
        providerProfileCatalog = providerProfileCatalog,
        authorityMode = settingsView.authorityMode,
      )
    }
  }
}

@Serializable
data class WorkspaceSettingsProposalFile(
  val type: String = "settings",
  val title: String = "",
  val reason: String = "",
  val changes: SettingsProposalChanges = SettingsProposalChanges(),
)

data class WorkspaceSettingsProposal(
  val path: String,
  val title: String,
  val reason: String,
  val changes: SettingsProposalChanges,
  val createdAtMillis: Long,
)

@Serializable
data class WorkspaceControlledToolProposalFile(
  val type: String = "tool",
  val title: String = "",
  val reason: String = "",
  val name: String = "",
  val description: String = "",
  val command: String = "",
  val endpoint: String = "",
  val requestedCapabilities: List<String> = emptyList(),
  val permissions: List<String> = emptyList(),
)

data class WorkspaceControlledToolProposal(
  val path: String,
  val type: String,
  val title: String,
  val reason: String,
  val name: String,
  val description: String,
  val command: String,
  val endpoint: String,
  val requestedCapabilities: List<String>,
  val permissions: List<String>,
  val createdAtMillis: Long,
)
