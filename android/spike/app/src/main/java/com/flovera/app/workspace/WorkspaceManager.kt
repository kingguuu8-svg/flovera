package com.flovera.app.workspace

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.flovera.app.storage.readUtf8Text
import com.flovera.app.storage.writeBytesAtomically
import com.flovera.app.storage.writeStreamAtomically
import com.flovera.app.storage.writeUtf8TextAtomically
import java.io.File

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

  fun ensureSeedFiles() {
    writeFile(
      path = "README.md",
      content = """
        # Android Agent Workspace

        This workspace is owned by the Android app. The agent can read, write, and edit files here through approved tools.
      """.trimIndent(),
      overwrite = false,
    )
    writeFile(
      path = "AGENT.md",
      content = """
        # Agent Rules

        - Keep all file paths relative to this workspace.
        - Prefer plain HTML, CSS, JavaScript, Markdown, and JSON files.
        - Do not assume Python, npm, git, bash, or Linux tools exist on Android.
        - Do not use emoji unless the user explicitly asks for them.
      """.trimIndent(),
      overwrite = false,
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
    )
  }

  fun readAgentRules(): String = readFile("AGENT.md")

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

  fun writeFile(path: String, content: String, overwrite: Boolean = true): String {
    val file = safeFile(path)
    if (file.exists() && !overwrite) return "File already exists: ${relativeToRoot(file)}"
    writeUtf8TextAtomically(file, content)
    return "Wrote ${content.length} chars to ${relativeToRoot(file)}"
  }

  fun writeBytes(path: String, content: ByteArray, overwrite: Boolean = true): String {
    val file = safeFile(path)
    if (file.exists() && !overwrite) return "File already exists: ${relativeToRoot(file)}"
    writeBytesAtomically(file, content)
    return "Wrote ${content.size} bytes to ${relativeToRoot(file)}"
  }

  fun importUriToRoot(uri: Uri): String {
    val name = uniqueRootFileName(sanitizeRootFileName(displayName(uri) ?: uri.lastPathSegment.orEmpty()))
    val target = safeFile(name)
    val input = appContext.contentResolver.openInputStream(uri) ?: return "Could not open shared file: $uri"
    writeStreamAtomically(target, input)
    return "Imported ${relativeToRoot(target)}"
  }

  fun editFile(path: String, oldText: String, newText: String): String {
    val file = safeFile(path)
    if (!file.exists() || !file.isFile) return "File does not exist: $path"
    val current = readUtf8Text(file)
    if (!current.contains(oldText)) return "Old text was not found in $path"
    val updated = current.replace(oldText, newText, ignoreCase = false)
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
