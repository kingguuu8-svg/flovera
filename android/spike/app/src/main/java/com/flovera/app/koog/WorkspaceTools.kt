package com.flovera.app.koog

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.flovera.app.workspace.WorkspaceManager
import kotlinx.serialization.Serializable

private const val MAX_READ_FILE_CHARS = 64 * 1024

fun workspaceToolRegistry(
  workspace: WorkspaceManager,
  recorder: ToolEventRecorder,
  networkEnabled: Boolean = false,
  webSearchEnabled: Boolean = false,
  braveSearchApiKey: String = "",
): ToolRegistry = ToolRegistry {
  tool(ListFilesTool(workspace, recorder))
  tool(WorkspaceSearchTool(workspace, recorder))
  tool(PythonRunTool(workspace, recorder, networkEnabled))
  tool(PythonPackageInstallTool(workspace, recorder, networkEnabled))
  tool(ArtifactInspectTool(workspace, recorder))
  tool(ArtifactDiagnoseTool(workspace, recorder))
  tool(ReadFileTool(workspace, recorder))
  tool(WriteFileTool(workspace, recorder))
  tool(EditFileTool(workspace, recorder))
  if (networkEnabled) {
    tool(FetchUrlTool(recorder))
    tool(DownloadFileTool(workspace, recorder))
    if (webSearchEnabled && braveSearchApiKey.isNotBlank()) {
      tool(WebSearchTool(braveSearchApiKey, recorder))
    }
  }
}

class ArtifactDiagnoseTool(
  private val workspace: WorkspaceManager,
  private val recorder: ToolEventRecorder,
) : SimpleTool<ArtifactDiagnoseTool.Args>(
  argsType = typeToken<Args>(),
  name = "artifact_diagnose",
  description = "Diagnose Flovera app registration for workspace artifacts. Use after writing flovera.app.json to confirm discovery, schema validity, preview path, python_http backend command, actions, outputs, and validation diagnostics before claiming the app is registered.",
) {
  @Serializable
  data class Args(
    @property:LLMDescription("Optional workspace-relative flovera.app.json path to diagnose, for example agent-demo/flovera.app.json. Leave blank to list all discovered artifacts.")
    val manifestPath: String = "",
    @property:LLMDescription("Optional workspace-relative HTML preview path to match, for example agent-demo/src/web/index.html.")
    val previewPath: String = "",
    @property:LLMDescription("Set true to include Flovera's hidden reference app shape for comparison without exposing a user-visible demo.")
    val includeReference: Boolean = false,
  )

  override suspend fun execute(args: Args): String {
    val result = runCatching {
      workspace.diagnoseWorkspaceArtifact(
        manifestPath = args.manifestPath,
        previewPath = args.previewPath,
        includeReference = args.includeReference,
      )
    }.getOrElse { it.message ?: it.toString() }
    recorder.record(name, "manifestPath=${args.manifestPath}, previewPath=${args.previewPath}, includeReference=${args.includeReference}", result)
    return result
  }
}

class WorkspaceSearchTool(
  private val workspace: WorkspaceManager,
  private val recorder: ToolEventRecorder,
) : SimpleTool<WorkspaceSearchTool.Args>(
  argsType = typeToken<Args>(),
  name = "workspace_search",
  description = "Search current Android workspace text files using local grep-like matching. Use path/includeGlob/excludeGlob to narrow the search, contextLines for local understanding, output=files or count for quick routing, and scope=workspace_app_metadata only when .flovera app metadata is relevant.",
) {
  @Serializable
  data class Args(
    @property:LLMDescription("Search query. Use concrete words, identifiers, API paths, error text, or file names.")
    val query: String,
    @property:LLMDescription("Relative file or directory path to search. Use '.' for the workspace root.")
    val path: String = ".",
    @property:LLMDescription("Maximum number of matches to return. Values are clamped to 1..25.")
    val topK: Int = 10,
    @property:LLMDescription("Search permission scope: workspace_public, workspace_app_metadata, or workspace_internal.")
    val scope: String = "workspace_public",
    @property:LLMDescription("Number of lines before and after each hit to include. Values are clamped to 0..5.")
    val contextLines: Int = 0,
    @property:LLMDescription("Whether matching should be case-sensitive.")
    val caseSensitive: Boolean = false,
    @property:LLMDescription("Search mode: literal or regex.")
    val mode: String = "literal",
    @property:LLMDescription("Optional glob for paths to include, for example src/** or *.kt.")
    val includeGlob: String = "",
    @property:LLMDescription("Optional glob for paths to exclude, for example build/** or *.min.js.")
    val excludeGlob: String = "",
    @property:LLMDescription("Output shape: matches, files, or count.")
    val output: String = "matches",
    @property:LLMDescription("Whether to respect workspace .gitignore and .ignore files.")
    val respectIgnoreFiles: Boolean = true,
    @property:LLMDescription("Maximum searchable text files to scan before stopping. Values are clamped to 1..10000.")
    val maxFiles: Int = 2000,
    @property:LLMDescription("Maximum characters per returned snippet line. Values are clamped to 80..500.")
    val maxSnippetChars: Int = 200,
    @property:LLMDescription("Whether to include diagnostic scores and scan counters.")
    val debug: Boolean = false,
  )

  override suspend fun execute(args: Args): String {
    val result = runCatching {
      workspace.searchFiles(
        query = args.query,
        topK = args.topK,
        scope = args.scope,
        path = args.path,
        contextLines = args.contextLines,
        caseSensitive = args.caseSensitive,
        mode = args.mode,
        includeGlob = args.includeGlob,
        excludeGlob = args.excludeGlob,
        output = args.output,
        respectIgnoreFiles = args.respectIgnoreFiles,
        maxFiles = args.maxFiles,
        maxSnippetChars = args.maxSnippetChars,
        debug = args.debug,
      )
    }.getOrElse { it.message ?: it.toString() }
    recorder.record(
      name,
      "query=${args.query}, path=${args.path}, topK=${args.topK}, scope=${args.scope}, mode=${args.mode}, output=${args.output}",
      result,
    )
    return result
  }
}

class ListFilesTool(
  private val workspace: WorkspaceManager,
  private val recorder: ToolEventRecorder,
) : SimpleTool<ListFilesTool.Args>(
  argsType = typeToken<Args>(),
  name = "list_files",
  description = "List files under the current Android workspace. The path is relative to the workspace root.",
) {
  @Serializable
  data class Args(
    @property:LLMDescription("Relative directory path. Use '.' for workspace root.")
    val path: String = ".",
  )

  override suspend fun execute(args: Args): String {
    val result = runCatching { workspace.listFiles(args.path) }.getOrElse { it.message ?: it.toString() }
    recorder.record(name, "path=${args.path}", result)
    return result
  }
}

class ReadFileTool(
  private val workspace: WorkspaceManager,
  private val recorder: ToolEventRecorder,
) : SimpleTool<ReadFileTool.Args>(
  argsType = typeToken<Args>(),
  name = "read_file",
  description = "Read a UTF-8 text file from the Android workspace. The path is relative to the workspace root.",
) {
  @Serializable
  data class Args(
    @property:LLMDescription("Relative file path to read.")
    val path: String,
  )

  override suspend fun execute(args: Args): String {
    val result = runCatching {
      workspace.readFilePreview(args.path, maxChars = MAX_READ_FILE_CHARS)
    }.getOrElse { it.message ?: it.toString() }
    recorder.record(name, "path=${args.path}", result)
    return result
  }
}

class WriteFileTool(
  private val workspace: WorkspaceManager,
  private val recorder: ToolEventRecorder,
) : SimpleTool<WriteFileTool.Args>(
  argsType = typeToken<Args>(),
  name = "write_file",
  description = "Write a UTF-8 text file inside the Android workspace. Creates parent directories when needed.",
) {
  @Serializable
  data class Args(
    @property:LLMDescription("Relative file path to write.")
    val path: String,
    @property:LLMDescription("Full file content.")
    val content: String,
  )

  override suspend fun execute(args: Args): String {
    val result = runCatching { workspace.writeFile(args.path, args.content) }.getOrElse { it.message ?: it.toString() }
    recorder.record(name, "path=${args.path}, chars=${args.content.length}", result)
    return result
  }
}

class EditFileTool(
  private val workspace: WorkspaceManager,
  private val recorder: ToolEventRecorder,
) : SimpleTool<EditFileTool.Args>(
  argsType = typeToken<Args>(),
  name = "edit_file",
  description = "Edit a UTF-8 text file by replacing an exact old_text block with new_text.",
) {
  @Serializable
  data class Args(
    @property:LLMDescription("Relative file path to edit.")
    val path: String,
    @property:LLMDescription("Exact text block to replace.")
    val oldText: String,
    @property:LLMDescription("Replacement text block.")
    val newText: String,
  )

  override suspend fun execute(args: Args): String {
    val result = runCatching {
      workspace.editFile(args.path, args.oldText, args.newText)
    }.getOrElse { it.message ?: it.toString() }
    recorder.record(name, "path=${args.path}, oldChars=${args.oldText.length}, newChars=${args.newText.length}", result)
    return result
  }
}
