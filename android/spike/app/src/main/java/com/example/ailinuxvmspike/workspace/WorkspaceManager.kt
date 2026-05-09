package com.example.ailinuxvmspike.workspace

import android.content.Context
import java.io.File

class WorkspaceManager(context: Context, workspaceId: String = "default") {
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

  fun displayUrl(path: String): String? {
    val file = safeFile(path)
    if (!file.exists() || !file.isFile || !file.extension.equals("html", ignoreCase = true)) return null
    return file.toURI().toASCIIString()
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
    return file.readText()
  }

  fun writeFile(path: String, content: String, overwrite: Boolean = true): String {
    val file = safeFile(path)
    if (file.exists() && !overwrite) return "File already exists: ${relativeToRoot(file)}"
    file.parentFile?.mkdirs()
    file.writeText(content)
    return "Wrote ${content.length} chars to ${relativeToRoot(file)}"
  }

  fun editFile(path: String, oldText: String, newText: String): String {
    val file = safeFile(path)
    if (!file.exists() || !file.isFile) return "File does not exist: $path"
    val current = file.readText()
    if (!current.contains(oldText)) return "Old text was not found in $path"
    val updated = current.replace(oldText, newText, ignoreCase = false)
    file.writeText(updated)
    return "Edited ${relativeToRoot(file)}"
  }

  private fun safeFile(path: String): File {
    val requested = File(root, path).canonicalFile
    val canonicalRoot = root.canonicalFile
    require(requested.path == canonicalRoot.path || requested.path.startsWith(canonicalRoot.path + File.separator)) {
      "Path escapes workspace: $path"
    }
    return requested
  }

  private fun relativeToRoot(file: File): String {
    return file.canonicalFile.toRelativeString(root.canonicalFile).ifBlank { "." }
  }
}
