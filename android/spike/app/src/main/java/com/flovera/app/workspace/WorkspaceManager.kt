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
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

data class WorkspaceFileNode(
  val name: String,
  val path: String,
  val isDirectory: Boolean,
  val sizeBytes: Long,
  val children: List<WorkspaceFileNode> = emptyList(),
)

data class WorkspaceSearchHit(
  val path: String,
  val lineNumber: Int,
  val score: Int,
  val snippet: String,
  val context: List<WorkspaceSearchContextLine> = emptyList(),
)

data class WorkspaceSearchContextLine(
  val lineNumber: Int,
  val text: String,
  val isMatch: Boolean,
)

data class WorkspaceSearchOptions(
  val query: String,
  val path: String = ".",
  val topK: Int = 10,
  val scope: String = "workspace_public",
  val contextLines: Int = 0,
  val caseSensitive: Boolean = false,
  val mode: String = "literal",
  val includeGlob: String = "",
  val excludeGlob: String = "",
  val output: String = "matches",
  val respectIgnoreFiles: Boolean = true,
  val maxFiles: Int = 2000,
  val maxSnippetChars: Int = 200,
  val debug: Boolean = false,
)

private data class WorkspaceIgnoreRule(
  val regex: Regex,
  val descendantRegex: Regex?,
  val negated: Boolean,
)

class WorkspaceManager(context: Context, workspaceId: String = "default") {
  private val appContext = context.applicationContext
  private val workspacesRoot = File(context.filesDir, "workspaces")
  val root: File = File(workspacesRoot, workspaceId).apply { mkdirs() }
  val applicationContext: Context
    get() = appContext
  private val snapshotStore = WorkspaceSnapshotStore(appContext, workspaceId, root)
  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
  }
  private val compactJson = Json {
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
    providerApiModes: List<String> = listOf("chat_completions", "anthropic_messages"),
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
      content = json.encodeToString(FloveraCapabilities.fromSettings(settingsView, providerProfileCatalog, providerApiModes)),
      overwrite = true,
      createAutoSnapshot = false,
    )
    safeFile(".flovera/proposals").mkdirs()
    safeFile(".flovera/tools").mkdirs()
    safeFile(".flovera/python/site-packages").mkdirs()
    safeFile(".flovera/python/wheels").mkdirs()
    writeFile(
      path = ".flovera/tools/manifest.json",
      content = json.encodeToString(FloveraPythonToolsManifest()),
      overwrite = false,
      createAutoSnapshot = false,
    )
    writeFile(
      path = ".flovera/python/wheel-catalog.json",
      content = json.encodeToString(FloveraPythonWheelCatalog.default()),
      overwrite = true,
      createAutoSnapshot = false,
    )
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

  fun appendFullAuthorityAudit(
    action: String,
    targetPath: String,
    title: String,
    reason: String,
    changes: SettingsProposalChanges,
  ): String {
    val file = safeFile(".flovera/logs/full-authority.jsonl")
    val existing = if (file.exists()) readUtf8Text(file).trimEnd() else ""
    val record = WorkspaceFullAuthorityAuditRecord(
      id = UUID.randomUUID().toString(),
      timestampMillis = System.currentTimeMillis(),
      action = action,
      targetPath = targetPath,
      title = title,
      reason = reason,
      changes = changes,
    )
    val updated = buildString {
      if (existing.isNotBlank()) {
        appendLine(existing)
      }
      appendLine(compactJson.encodeToString(record))
    }
    writeUtf8TextAtomically(file, updated)
    return relativeToRoot(file)
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

  fun createAutomaticSnapshot(reason: String) {
    snapshotStore.createAutomatic(reason)
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

  fun workspaceRuntimeDirectory(path: String = "."): File {
    val file = safeFile(path.ifBlank { "." })
    if (!file.exists()) return file
    return if (file.isFile) file.parentFile ?: root else file
  }

  fun workspaceRelativePath(file: File): String = relativeToRoot(file)

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

  fun searchFiles(
    query: String,
    topK: Int = 10,
    scope: String = WORKSPACE_SEARCH_SCOPE_PUBLIC,
    path: String = ".",
    contextLines: Int = 0,
    caseSensitive: Boolean = false,
    mode: String = WORKSPACE_SEARCH_MODE_LITERAL,
    includeGlob: String = "",
    excludeGlob: String = "",
    output: String = WORKSPACE_SEARCH_OUTPUT_MATCHES,
    respectIgnoreFiles: Boolean = true,
    maxFiles: Int = DEFAULT_WORKSPACE_SEARCH_MAX_FILES,
    maxSnippetChars: Int = DEFAULT_WORKSPACE_SEARCH_SNIPPET_CHARS,
    debug: Boolean = false,
  ): String {
    return searchFiles(
      WorkspaceSearchOptions(
        query = query,
        path = path,
        topK = topK,
        scope = scope,
        contextLines = contextLines,
        caseSensitive = caseSensitive,
        mode = mode,
        includeGlob = includeGlob,
        excludeGlob = excludeGlob,
        output = output,
        respectIgnoreFiles = respectIgnoreFiles,
        maxFiles = maxFiles,
        maxSnippetChars = maxSnippetChars,
        debug = debug,
      ),
    )
  }

  fun searchFiles(options: WorkspaceSearchOptions): String {
    val normalizedQuery = options.query.trim()
    if (normalizedQuery.isBlank()) return "Search query is blank."
    val requested = runCatching { safeFile(options.path.ifBlank { "." }) }.getOrElse {
      return it.message ?: it.toString()
    }
    if (!requested.exists()) return "Path does not exist: ${options.path}"
    val limit = options.topK.coerceIn(1, MAX_WORKSPACE_SEARCH_RESULTS)
    val maxFiles = options.maxFiles.coerceIn(1, MAX_WORKSPACE_SEARCH_FILES)
    val normalizedScope = normalizeWorkspaceSearchScope(options.scope)
    val searchMode = normalizeWorkspaceSearchMode(options.mode)
    val output = normalizeWorkspaceSearchOutput(options.output)
    val regex = if (searchMode == WORKSPACE_SEARCH_MODE_REGEX) {
      runCatching {
        Regex(normalizedQuery, if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
      }.getOrElse { return "Invalid regex: ${it.message}" }
    } else {
      null
    }
    val tokens = workspaceSearchTokens(normalizedQuery, options.caseSensitive)
    val includeRegex = workspaceGlobRegex(options.includeGlob)
    val excludeRegex = workspaceGlobRegex(options.excludeGlob)
    val context = options.contextLines.coerceIn(0, MAX_WORKSPACE_SEARCH_CONTEXT_LINES)
    val maxSnippetChars = options.maxSnippetChars.coerceIn(MIN_WORKSPACE_SEARCH_SNIPPET_CHARS, MAX_WORKSPACE_SEARCH_SNIPPET_CHARS)
    val ignoreRules = if (options.respectIgnoreFiles) loadWorkspaceIgnoreRules() else emptyList()
    val hits = mutableListOf<WorkspaceSearchHit>()
    if (!root.exists()) return "No matches for \"$normalizedQuery\"."

    var scannedFiles = 0
    var skippedFiles = 0
    var stoppedEarly = false
    val candidates = workspaceSearchCandidates(requested, normalizedScope, ignoreRules)
    for (file in candidates) {
      if (Thread.currentThread().isInterrupted) {
        stoppedEarly = true
        break
      }
      if (!isWorkspaceSearchCandidate(file, normalizedScope, includeRegex, excludeRegex, ignoreRules)) {
        skippedFiles += 1
        continue
      }
      if (scannedFiles >= maxFiles) {
        stoppedEarly = true
        break
      }
      scannedFiles += 1
      hits += runCatching {
        searchFile(
          file = file,
          query = normalizedQuery,
          tokens = tokens,
          caseSensitive = options.caseSensitive,
          mode = searchMode,
          regex = regex,
          contextLines = context,
          maxSnippetChars = maxSnippetChars,
        )
      }.getOrDefault(emptyList())
    }

    val topHits = hits
      .sortedWith(compareByDescending<WorkspaceSearchHit> { it.score }.thenBy { it.path }.thenBy { it.lineNumber })
      .take(limit)

    if (topHits.isEmpty()) {
      return "No matches for \"$normalizedQuery\"${workspaceSearchHeaderSuffix(options.debug, scannedFiles, skippedFiles, stoppedEarly, maxFiles)}."
    }
    if (output == WORKSPACE_SEARCH_OUTPUT_FILES) {
      return workspaceSearchFilesOutput(
        query = normalizedQuery,
        requested = requested,
        scope = normalizedScope,
        mode = searchMode,
        hits = hits,
        limit = limit,
        scannedFiles = scannedFiles,
        skippedFiles = skippedFiles,
        stoppedEarly = stoppedEarly,
        maxFiles = maxFiles,
        debug = options.debug,
      )
    }
    if (output == WORKSPACE_SEARCH_OUTPUT_COUNT) {
      return workspaceSearchCountOutput(
        query = normalizedQuery,
        requested = requested,
        scope = normalizedScope,
        mode = searchMode,
        hits = hits,
        limit = limit,
        scannedFiles = scannedFiles,
        skippedFiles = skippedFiles,
        stoppedEarly = stoppedEarly,
        maxFiles = maxFiles,
        debug = options.debug,
      )
    }
    return workspaceSearchMatchesOutput(
      query = normalizedQuery,
      requestedPath = relativeToRoot(requested),
      scope = normalizedScope,
      mode = searchMode,
      hits = topHits,
      totalMatches = hits.size,
      scannedFiles = scannedFiles,
      skippedFiles = skippedFiles,
      stoppedEarly = stoppedEarly,
      maxFiles = maxFiles,
      debug = options.debug,
    )
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

  fun deletePath(path: String): String {
    val file = safeFile(path)
    if (!file.exists()) return "Path does not exist: $path"
    if (file.canonicalFile == root.canonicalFile) return "Cannot delete workspace root."
    val relative = relativeToRoot(file)
    snapshotStore.createAutomatic("delete:$relative")
    val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
    return if (deleted) {
      "Deleted $relative"
    } else {
      "Failed to delete $relative"
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

  private fun searchFile(
    file: File,
    query: String,
    tokens: List<String>,
    caseSensitive: Boolean,
    mode: String,
    regex: Regex?,
    contextLines: Int,
    maxSnippetChars: Int,
  ): List<WorkspaceSearchHit> {
    val path = relativeToRoot(file)
    val pathScore = workspaceSearchPathScore(path, query, tokens, caseSensitive, mode, regex)
    val hits = mutableListOf<WorkspaceSearchHit>()
    var firstNonBlank: Pair<Int, String>? = null
    val lines = file.readLines(Charsets.UTF_8)
    lines.forEachIndexed { index, line ->
      if (firstNonBlank == null && line.isNotBlank()) {
        firstNonBlank = index + 1 to line
      }
      val score = pathScore + workspaceSearchLineScore(line, query, tokens, caseSensitive, mode, regex)
      if (score > 0) {
        hits += WorkspaceSearchHit(
          path = path,
          lineNumber = index + 1,
          score = score,
          snippet = workspaceSearchSnippet(lines, index, contextLines, maxSnippetChars),
          context = workspaceSearchContext(lines, index, contextLines, maxSnippetChars),
        )
      }
    }
    if (hits.isEmpty() && pathScore > 0) {
      val preview = firstNonBlank ?: (1 to "")
      hits += WorkspaceSearchHit(
        path = path,
        lineNumber = preview.first,
        score = pathScore,
        snippet = workspaceSearchSnippet(preview.second, maxSnippetChars),
        context = listOf(WorkspaceSearchContextLine(preview.first, workspaceSearchSnippet(preview.second, maxSnippetChars), isMatch = true)),
      )
    }
    return hits
  }

  private fun workspaceSearchCandidates(
    requested: File,
    scope: String,
    ignoreRules: List<WorkspaceIgnoreRule>,
  ): Sequence<File> = sequence {
    if (requested.isFile) {
      yield(requested)
      return@sequence
    }

    suspend fun SequenceScope<File>.visit(dir: File) {
      dir.listFiles()
        ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        ?.forEach { child ->
          if (Thread.currentThread().isInterrupted) return
          if (child.isDirectory) {
            if (isWorkspaceSearchDirectoryCandidate(child, scope, ignoreRules)) {
              visit(child)
            }
          } else {
            yield(child)
          }
        }
    }

    visit(requested)
  }

  private fun isWorkspaceSearchDirectoryCandidate(
    dir: File,
    scope: String,
    ignoreRules: List<WorkspaceIgnoreRule>,
  ): Boolean {
    val path = relativeToRoot(dir).replace('\\', '/')
    if (path == ".") return true
    if (path == ".flovera") return scope != WORKSPACE_SEARCH_SCOPE_PUBLIC
    if (path.startsWith(".flovera/retrieval") || path.startsWith(".flovera/cache")) return false
    if (path.startsWith(".flovera/")) {
      return scope == WORKSPACE_SEARCH_SCOPE_INTERNAL ||
        (scope == WORKSPACE_SEARCH_SCOPE_APP_METADATA && path.startsWith(".flovera/proposals"))
    }
    if (path.startsWith(".") || path.contains("/.")) return false
    if (isWorkspaceIgnored(path, isDirectory = true, ignoreRules = ignoreRules)) return false
    return true
  }

  private fun isWorkspaceSearchCandidate(
    file: File,
    scope: String,
    includeRegex: Regex?,
    excludeRegex: Regex?,
    ignoreRules: List<WorkspaceIgnoreRule>,
  ): Boolean {
    val path = relativeToRoot(file)
    if (!isWorkspaceSearchPathAllowed(path, scope)) return false
    val normalizedPath = path.replace('\\', '/')
    if (isWorkspaceIgnored(normalizedPath, isDirectory = false, ignoreRules = ignoreRules)) return false
    if (includeRegex != null && !includeRegex.matches(normalizedPath)) return false
    if (excludeRegex != null && excludeRegex.matches(normalizedPath)) return false
    if (file.length() > MAX_WORKSPACE_SEARCH_FILE_BYTES) return false
    if (!isLikelyTextFile(file)) return false
    return true
  }

  private fun isWorkspaceSearchPathAllowed(path: String, scope: String): Boolean {
    val normalized = path.replace('\\', '/')
    if (normalized == ".") return false
    if (normalized.startsWith(".") && !normalized.startsWith(".flovera/")) return false
    if (normalized.contains("/.") && !normalized.startsWith(".flovera/")) return false
    if (!normalized.startsWith(".flovera/")) return true
    if (normalized.startsWith(".flovera/retrieval/") || normalized.startsWith(".flovera/cache/")) return false
    return when (scope) {
      WORKSPACE_SEARCH_SCOPE_PUBLIC -> false
      WORKSPACE_SEARCH_SCOPE_APP_METADATA -> {
        normalized == ".flovera/manifest.json" ||
          normalized == ".flovera/settings-view.json" ||
          normalized == ".flovera/capabilities.json" ||
          normalized.startsWith(".flovera/proposals/")
      }
      WORKSPACE_SEARCH_SCOPE_INTERNAL -> true
      else -> false
    }
  }

  private fun isLikelyTextFile(file: File): Boolean {
    val allowedExtensions = setOf(
      "txt", "md", "markdown", "html", "htm", "css", "js", "mjs", "cjs", "ts", "tsx", "jsx",
      "json", "jsonl", "xml", "csv", "kt", "kts", "java", "gradle", "properties", "yml", "yaml",
      "toml", "ini", "sql", "sh", "ps1", "py", "rb", "go", "rs", "c", "cpp", "h", "hpp",
    )
    if (file.extension.lowercase() in allowedExtensions) return true
    val sample = ByteArray(1024)
    val read = runCatching {
      file.inputStream().use { it.read(sample) }
    }.getOrDefault(0)
    if (read <= 0) return true
    return sample.take(read).none { byte ->
      val value = byte.toInt() and 0xff
      value == 0 || (value < 0x09) || (value in 0x0e..0x1f)
    }
  }

  private fun loadWorkspaceIgnoreRules(): List<WorkspaceIgnoreRule> {
    if (!root.exists()) return emptyList()
    val rules = mutableListOf<WorkspaceIgnoreRule>()
    workspaceSearchIgnoreFiles().forEach { ignoreFile ->
      val basePath = relativeToRoot(ignoreFile.parentFile ?: root).replace('\\', '/').let { if (it == ".") "" else "$it/" }
      readUtf8Text(ignoreFile).lineSequence().forEach { rawLine ->
        workspaceIgnoreRule(basePath, rawLine)?.let { rules += it }
      }
    }
    return rules
  }

  private fun workspaceSearchIgnoreFiles(): Sequence<File> = sequence {
    suspend fun SequenceScope<File>.visit(dir: File) {
      dir.listFiles()
        ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        ?.forEach { child ->
          val path = relativeToRoot(child).replace('\\', '/')
          if (child.isDirectory) {
            if (path != ".flovera" && !path.startsWith(".flovera/") && !path.startsWith(".") && !path.contains("/.")) {
              visit(child)
            }
          } else if (child.name == ".gitignore" || child.name == ".ignore") {
            yield(child)
          }
        }
    }

    visit(root)
  }

  private fun isWorkspaceIgnored(
    path: String,
    isDirectory: Boolean,
    ignoreRules: List<WorkspaceIgnoreRule>,
  ): Boolean {
    var ignored = false
    ignoreRules.forEach { rule ->
      val matches = rule.regex.matches(path) || rule.descendantRegex?.matches(path) == true
      if (matches || (isDirectory && rule.descendantRegex?.matches("$path/") == true)) {
        ignored = !rule.negated
      }
    }
    return ignored
  }

  private fun workspaceSearchFilesOutput(
    query: String,
    requested: File,
    scope: String,
    mode: String,
    hits: List<WorkspaceSearchHit>,
    limit: Int,
    scannedFiles: Int,
    skippedFiles: Int,
    stoppedEarly: Boolean,
    maxFiles: Int,
    debug: Boolean,
  ): String {
    val allFiles = hits
      .sortedWith(compareByDescending<WorkspaceSearchHit> { it.score }.thenBy { it.path })
      .map { it.path }
      .distinct()
    val files = allFiles.take(limit)
    return buildString {
      appendLine(
        "Found ${allFiles.size} files for \"$query\" " +
          "(path=${relativeToRoot(requested)}, scope=$scope, mode=$mode)" +
          workspaceSearchHeaderSuffix(debug, scannedFiles, skippedFiles, stoppedEarly, maxFiles) +
          ":",
      )
      files.forEach { path -> appendLine(path) }
    }.trimEnd()
  }

  private fun workspaceSearchCountOutput(
    query: String,
    requested: File,
    scope: String,
    mode: String,
    hits: List<WorkspaceSearchHit>,
    limit: Int,
    scannedFiles: Int,
    skippedFiles: Int,
    stoppedEarly: Boolean,
    maxFiles: Int,
    debug: Boolean,
  ): String {
    val allCounts = hits
      .groupingBy { it.path }
      .eachCount()
      .entries
      .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    val counts = allCounts.take(limit)
    return buildString {
      appendLine(
        "Found ${hits.size} matches in ${allCounts.size} files for \"$query\" " +
          "(path=${relativeToRoot(requested)}, scope=$scope, mode=$mode)" +
          workspaceSearchHeaderSuffix(debug, scannedFiles, skippedFiles, stoppedEarly, maxFiles) +
          ":",
      )
      counts.forEach { entry -> appendLine("${entry.key} count=${entry.value}") }
    }.trimEnd()
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

  private companion object {
    const val WORKSPACE_SEARCH_SCOPE_PUBLIC = "workspace_public"
    const val WORKSPACE_SEARCH_SCOPE_APP_METADATA = "workspace_app_metadata"
    const val WORKSPACE_SEARCH_SCOPE_INTERNAL = "workspace_internal"
    const val WORKSPACE_SEARCH_MODE_LITERAL = "literal"
    const val WORKSPACE_SEARCH_MODE_REGEX = "regex"
    const val WORKSPACE_SEARCH_OUTPUT_MATCHES = "matches"
    const val WORKSPACE_SEARCH_OUTPUT_FILES = "files"
    const val WORKSPACE_SEARCH_OUTPUT_COUNT = "count"
    const val MAX_WORKSPACE_SEARCH_RESULTS = 25
    const val MAX_WORKSPACE_SEARCH_CONTEXT_LINES = 5
    const val MAX_WORKSPACE_SEARCH_FILE_BYTES = 512 * 1024L
    const val DEFAULT_WORKSPACE_SEARCH_MAX_FILES = 2000
    const val MAX_WORKSPACE_SEARCH_FILES = 10000
    const val DEFAULT_WORKSPACE_SEARCH_SNIPPET_CHARS = 200
    const val MIN_WORKSPACE_SEARCH_SNIPPET_CHARS = 80
    const val MAX_WORKSPACE_SEARCH_SNIPPET_CHARS = 500
  }
}

private fun normalizeWorkspaceSearchScope(scope: String): String {
  return when (scope.trim().lowercase()) {
    "", "workspace", "public", "workspace_public" -> "workspace_public"
    "metadata", "app_metadata", "workspace_app_metadata", "flovera_metadata" -> "workspace_app_metadata"
    "internal", "workspace_internal", "all" -> "workspace_internal"
    else -> "workspace_public"
  }
}

private fun normalizeWorkspaceSearchMode(mode: String): String {
  return when (mode.trim().lowercase()) {
    "regex", "regexp" -> "regex"
    else -> "literal"
  }
}

private fun normalizeWorkspaceSearchOutput(output: String): String {
  return when (output.trim().lowercase()) {
    "file", "files", "files_with_matches", "paths" -> "files"
    "count", "counts", "count_only" -> "count"
    else -> "matches"
  }
}

private fun workspaceSearchTokens(query: String, caseSensitive: Boolean): List<String> {
  val source = if (caseSensitive) query else query.lowercase()
  return Regex("[\\p{L}\\p{N}_./:-]+")
    .findAll(source)
    .map { it.value.trim('.', '/', ':', '-') }
    .filter { it.length >= 2 }
    .distinct()
    .toList()
}

private fun workspaceSearchPathScore(
  path: String,
  query: String,
  tokens: List<String>,
  caseSensitive: Boolean,
  mode: String,
  regex: Regex?,
): Int {
  val haystack = if (caseSensitive) path else path.lowercase()
  val needle = if (caseSensitive) query else query.lowercase()
  var score = 0
  if (mode == "regex" && regex?.containsMatchIn(path) == true) score += 24
  if (mode == "literal" && needle.length >= 2 && haystack.contains(needle)) score += 24
  tokens.forEach { token ->
    if (haystack.contains(token)) score += if (path.substringAfterLast('/').lowercase().contains(token)) 8 else 4
  }
  return score
}

private fun workspaceSearchLineScore(
  line: String,
  query: String,
  tokens: List<String>,
  caseSensitive: Boolean,
  mode: String,
  regex: Regex?,
): Int {
  val haystack = if (caseSensitive) line else line.lowercase()
  val needle = if (caseSensitive) query else query.lowercase()
  var score = 0
  if (mode == "regex" && regex?.containsMatchIn(line) == true) score += 40
  if (mode == "literal" && needle.length >= 2 && haystack.contains(needle)) score += 40
  tokens.forEach { token ->
    if (haystack.contains(token)) score += 12
  }
  return score
}

private fun workspaceGlobRegex(glob: String): Regex? {
  val raw = glob.trim()
  if (raw.isBlank()) return null
  val normalized = raw.replace('\\', '/').let { value ->
    if ("/" in value) value else "**/$value"
  }
  val pattern = buildString {
    append("^")
    val chars = normalized
    var index = 0
    while (index < chars.length) {
      val char = chars[index]
      when {
        char == '*' && index + 1 < chars.length && chars[index + 1] == '*' && index + 2 < chars.length && chars[index + 2] == '/' -> {
          append("(?:.*/)?")
          index += 2
        }
        char == '*' && index + 1 < chars.length && chars[index + 1] == '*' -> {
          append(".*")
          index += 1
        }
        char == '*' -> append("[^/]*")
        char == '?' -> append("[^/]")
        char == '.' -> append("\\.")
        char == '/' -> append("/")
        else -> append(Regex.escape(char.toString()))
      }
      index += 1
    }
    append("$")
  }
  return Regex(pattern, RegexOption.IGNORE_CASE)
}

private fun workspaceIgnoreRule(basePath: String, rawLine: String): WorkspaceIgnoreRule? {
  var line = rawLine.trim()
  if (line.isBlank() || line.startsWith("#")) return null
  val negated = line.startsWith("!")
  if (negated) line = line.drop(1).trim()
  if (line.isBlank()) return null
  val directoryOnly = line.endsWith("/")
  line = line.trim('/')
  if (line.isBlank()) return null
  val anchored = rawLine.trim().removePrefix("!").startsWith("/")
  val hasSlash = "/" in line
  val pattern = when {
    anchored || hasSlash -> basePath + line
    else -> basePath + "**/$line"
  }
  val regex = workspaceGlobRegex(pattern) ?: return null
  val descendantRegex = if (directoryOnly) workspaceGlobRegex("$pattern/**") else null
  return WorkspaceIgnoreRule(regex = regex, descendantRegex = descendantRegex, negated = negated)
}

private fun workspaceSearchSummary(scannedFiles: Int, skippedFiles: Int, stoppedEarly: Boolean, maxFiles: Int): String {
  val stopped = if (stoppedEarly) ", stoppedAfterMaxFiles=$maxFiles" else ""
  return "scannedFiles=$scannedFiles, skippedFiles=$skippedFiles$stopped"
}

private fun workspaceSearchHeaderSuffix(
  debug: Boolean,
  scannedFiles: Int,
  skippedFiles: Int,
  stoppedEarly: Boolean,
  maxFiles: Int,
): String {
  if (debug) return " (${workspaceSearchSummary(scannedFiles, skippedFiles, stoppedEarly, maxFiles)})"
  return if (stoppedEarly) " (stoppedAfterMaxFiles=$maxFiles)" else ""
}

private fun workspaceSearchSnippet(lines: List<String>, index: Int, contextLines: Int, maxChars: Int): String {
  if (contextLines <= 0) return workspaceSearchSnippet(lines[index], maxChars)
  val start = (index - contextLines).coerceAtLeast(0)
  val end = (index + contextLines).coerceAtMost(lines.lastIndex)
  return (start..end).joinToString(" | ") { lineIndex ->
    val marker = if (lineIndex == index) ">" else " "
    "$marker${lineIndex + 1}: ${workspaceSearchSnippet(lines[lineIndex], maxChars)}"
  }
}

private fun workspaceSearchContext(
  lines: List<String>,
  index: Int,
  contextLines: Int,
  maxChars: Int,
): List<WorkspaceSearchContextLine> {
  val start = (index - contextLines).coerceAtLeast(0)
  val end = (index + contextLines).coerceAtMost(lines.lastIndex)
  return (start..end).map { lineIndex ->
    WorkspaceSearchContextLine(
      lineNumber = lineIndex + 1,
      text = workspaceSearchSnippet(lines[lineIndex], maxChars),
      isMatch = lineIndex == index,
    )
  }
}

private fun workspaceSearchSnippet(line: String, maxChars: Int): String {
  val normalized = line.trim().replace(Regex("\\s+"), " ")
  if (normalized.length <= maxChars) return normalized
  return normalized.take((maxChars - 3).coerceAtLeast(1)) + "..."
}

private fun workspaceSearchMatchesOutput(
  query: String,
  requestedPath: String,
  scope: String,
  mode: String,
  hits: List<WorkspaceSearchHit>,
  totalMatches: Int,
  scannedFiles: Int,
  skippedFiles: Int,
  stoppedEarly: Boolean,
  maxFiles: Int,
  debug: Boolean,
): String {
  return buildString {
    appendLine(
      "Found $totalMatches matches for \"$query\" " +
        "(path=$requestedPath, scope=$scope, mode=$mode)" +
        workspaceSearchHeaderSuffix(debug, scannedFiles, skippedFiles, stoppedEarly, maxFiles) +
        ":",
    )
    hits.groupBy { it.path }.forEach { (path, fileHits) ->
      val lineNumbers = fileHits.map { it.lineNumber }.distinct().sorted().joinToString(",")
      val debugSuffix = if (debug) " maxScore=${fileHits.maxOf { it.score }}" else ""
      appendLine("$path:$lineNumbers$debugSuffix")
      workspaceSearchMergedContext(fileHits).forEach { line ->
        val marker = if (line.isMatch) ">" else " "
        appendLine("$marker${line.lineNumber}: ${line.text}")
      }
    }
  }.trimEnd()
}

private fun workspaceSearchMergedContext(hits: List<WorkspaceSearchHit>): List<WorkspaceSearchContextLine> {
  return hits
    .flatMap { hit ->
      hit.context.ifEmpty {
        listOf(WorkspaceSearchContextLine(hit.lineNumber, hit.snippet, isMatch = true))
      }
    }
    .groupBy { it.lineNumber }
    .map { (lineNumber, lines) ->
      WorkspaceSearchContextLine(
        lineNumber = lineNumber,
        text = lines.first().text,
        isMatch = lines.any { it.isMatch },
      )
    }
    .sortedBy { it.lineNumber }
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
  val providerTransport: String = "",
  val providerBaseUrl: String = "",
  val providerModelsUrl: String = "",
  val providerResponsesPath: String = "",
  val providerMessagesPath: String = "",
  val providerModelsPath: String = "",
  val providerAuthType: String = "api_key",
  val providerDefaultHeaderNames: List<String> = emptyList(),
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
  val reasoningEffort: String = "",
  val customOpenAIBaseUrl: String = "",
  val customOpenAIChatCompletionsPath: String = "/v1/chat/completions",
  val customOpenAICompatibilityMode: String = "generic",
  val openRouterProviderPreferences: JsonObject = JsonObject(emptyMap()),
  val openRouterMinCodingScore: Double? = null,
  val providerInjectsOllamaNumCtx: Boolean = false,
  val providerInjectsOpenRouterRouting: Boolean = false,
  val providerRequestHookIds: List<String> = emptyList(),
  val providerRequestOmittedFields: List<String> = emptyList(),
  val providerRequestAddedFields: List<String> = emptyList(),
  val modelContextWindowTokens: Int? = null,
  val modelContextSource: String = "unknown",
  val modelSupportsReasoning: Boolean = false,
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
  val transport: String,
  val aliases: List<String> = emptyList(),
  val defaultModel: String,
  val suggestedModels: List<String> = emptyList(),
  val modelContexts: Map<String, FloveraModelContextView> = emptyMap(),
  val baseUrl: String = "",
  val modelsUrl: String = "",
  val responsesPath: String = "",
  val messagesPath: String = "",
  val modelsPath: String = "",
  val authType: String = "api_key",
  val defaultHeaderNames: List<String> = emptyList(),
  val supportsHealthCheck: Boolean = true,
  val defaultMaxTokens: Int? = null,
  val defaultAuxModel: String = "",
  val requestCompatibilityModes: List<String> = listOf("generic"),
  val requestHooks: List<String> = emptyList(),
  val omittedRequestFields: List<String> = emptyList(),
  val addedRequestFields: List<String> = emptyList(),
  val customRequestBody: Boolean = false,
)

@Serializable
data class FloveraModelContextView(
  val contextWindowTokens: Int? = null,
  val source: String = "unknown",
  val usageSource: String = "estimate",
  val compressionThresholdPercent: Int? = null,
  val supportsReasoning: Boolean = false,
)

@Serializable
data class FloveraCapabilities(
  val workspaceFiles: Boolean = true,
  val workspaceSearch: Boolean = true,
  val workspaceSearchScopes: List<String> = listOf("workspace_public", "workspace_app_metadata", "workspace_internal"),
  val artifactInspect: Boolean = true,
  val artifactInspectFormats: List<String> = listOf("json", "html", "docx", "xlsx", "pdf", "png", "jpg", "jpeg", "webp", "text"),
  val webPreview: Boolean = true,
  val previewFormats: List<String> = listOf("html", "markdown", "json", "csv", "text", "image", "pdf"),
  val snapshots: Boolean = true,
  val notifications: Boolean = true,
  val networkTools: Boolean = false,
  val pythonRuntime: Boolean = true,
  val pythonPackageInstall: Boolean = true,
  val pythonPackageCatalogPath: String = ".flovera/python/wheel-catalog.json",
  val pythonWorkspaceSitePackagesPath: String = ".flovera/python/site-packages",
  val pythonToolManifestPath: String = ".flovera/tools/manifest.json",
  val pythonBuiltInPackages: List<String> = listOf("lxml", "python-docx", "openpyxl", "XlsxWriter", "pypdf", "Markdown", "Jinja2"),
  val webSearch: Boolean = false,
  val settingsView: Boolean = true,
  val settingsProposals: Boolean = true,
  val controlledToolProposals: Boolean = true,
  val controlledMcpProposals: Boolean = true,
  val modelContextOverrides: Boolean = true,
  val deepSeekThinkingEffort: Boolean = true,
  val reasoningEffort: Boolean = true,
  val customOpenAICompatibleProvider: Boolean = true,
  val openRouterRouting: Boolean = true,
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
  val supportedAuthorityModes: List<String> = listOf("safe", "assisted", "full"),
  val pendingAuthorityModes: List<String> = emptyList(),
) {
  companion object {
    fun fromSettings(
      settingsView: FloveraSettingsView,
      providerProfileCatalog: List<FloveraProviderProfileView> = emptyList(),
      providerApiModes: List<String> = listOf("chat_completions", "anthropic_messages"),
    ): FloveraCapabilities {
      val fullAuthority = settingsView.authorityMode == "full"
      return FloveraCapabilities(
        networkTools = settingsView.networkEnabled,
        webSearch = settingsView.webSearchEnabled,
        providerApiModes = providerApiModes,
        providerProfileCatalog = providerProfileCatalog,
        directSettingsWrite = fullAuthority,
        authorityMode = settingsView.authorityMode,
      )
    }
  }
}

@Serializable
data class FloveraPythonToolsManifest(
  val version: Int = 1,
  val tools: List<FloveraPythonToolManifestEntry> = emptyList(),
)

@Serializable
data class FloveraPythonToolManifestEntry(
  val name: String = "",
  val path: String = "",
  val description: String = "",
  val entrypoint: String = "",
  val permissions: List<String> = listOf("workspace_public"),
)

@Serializable
data class FloveraPythonWheelCatalog(
  val version: Int = 1,
  val packages: List<FloveraPythonWheelPackage>,
) {
  companion object {
    fun default(): FloveraPythonWheelCatalog = FloveraPythonWheelCatalog(
      packages = listOf(
        FloveraPythonWheelPackage(
          name = "openpyxl",
          version = "3.1.5",
          wheelUrl = "https://files.pythonhosted.org/packages/c0/da/977ded879c29cbd04de313843e76868e6e13408a94ed6b987245dc7c8506/openpyxl-3.1.5-py2.py3-none-any.whl",
          sha256 = "5282c12b107bffeef825f4617dc029afaf41d0ea60823bbb665ef3079dc79de2",
          topLevelImports = listOf("openpyxl"),
          dependencies = listOf("et_xmlfile"),
          bundled = true,
        ),
        FloveraPythonWheelPackage(
          name = "et_xmlfile",
          version = "2.0.0",
          wheelUrl = "https://files.pythonhosted.org/packages/c1/8b/5fe2cc11fee489817272089c4203e679c63b570a5aaeb18d852ae3cbba6a/et_xmlfile-2.0.0-py3-none-any.whl",
          sha256 = "7a91720bc756843502c3b7504c77b8fe44217c85c537d85037f0f536151b2caa",
          topLevelImports = listOf("et_xmlfile"),
          bundled = true,
        ),
        FloveraPythonWheelPackage(
          name = "XlsxWriter",
          version = "3.2.9",
          wheelUrl = "https://files.pythonhosted.org/packages/3a/0c/3662f4a66880196a590b202f0db82d919dd2f89e99a27fadef91c4a33d41/xlsxwriter-3.2.9-py3-none-any.whl",
          sha256 = "9a5db42bc5dff014806c58a20b9eae7322a134abb6fce3c92c181bfb275ec5b3",
          topLevelImports = listOf("xlsxwriter"),
          bundled = true,
        ),
        FloveraPythonWheelPackage(
          name = "pypdf",
          version = "6.11.0",
          wheelUrl = "https://files.pythonhosted.org/packages/07/b1/68feb7eb3b99f0c020b414234825f4a5d70e0126c18d933770e8c93a35fc/pypdf-6.11.0-py3-none-any.whl",
          sha256 = "769394d5756d5b304c9b6bef88b54b1816b328e7e6fc9254e625529a15ed4ab8",
          topLevelImports = listOf("pypdf"),
          bundled = true,
        ),
        FloveraPythonWheelPackage(
          name = "Markdown",
          version = "3.10.2",
          wheelUrl = "https://files.pythonhosted.org/packages/de/1f/77fa3081e4f66ca3576c896ae5d31c3002ac6607f9747d2e3aa49227e464/markdown-3.10.2-py3-none-any.whl",
          sha256 = "e91464b71ae3ee7afd3017d9f358ef0baf158fd9a298db92f1d4761133824c36",
          topLevelImports = listOf("markdown"),
          bundled = true,
        ),
        FloveraPythonWheelPackage(
          name = "Jinja2",
          version = "3.1.6",
          wheelUrl = "https://files.pythonhosted.org/packages/62/a1/3d680cbfd5f4b8f15abc1d571870c5fc3e594bb582bc3b64ea099db13e56/jinja2-3.1.6-py3-none-any.whl",
          sha256 = "85ece4451f492d0c13c5dd7c13a64681a86afae63a5f347908daf103ce6d2f67",
          topLevelImports = listOf("jinja2"),
          dependencies = listOf("MarkupSafe"),
          bundled = true,
          purePython = false,
        ),
      ),
    )
  }
}

@Serializable
data class FloveraPythonWheelPackage(
  val name: String,
  val version: String,
  val wheelUrl: String,
  val sha256: String,
  val topLevelImports: List<String>,
  val dependencies: List<String> = emptyList(),
  val purePython: Boolean = true,
  val bundled: Boolean = false,
)

@Serializable
data class WorkspaceSettingsProposalFile(
  val type: String = "settings",
  val title: String = "",
  val reason: String = "",
  val changes: SettingsProposalChanges = SettingsProposalChanges(),
)

@Serializable
data class WorkspaceFullAuthorityAuditRecord(
  val id: String,
  val timestampMillis: Long,
  val action: String,
  val targetPath: String,
  val title: String,
  val reason: String,
  val changes: SettingsProposalChanges,
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
