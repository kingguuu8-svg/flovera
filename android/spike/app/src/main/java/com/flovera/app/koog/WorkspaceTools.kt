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
): ToolRegistry = ToolRegistry {
  tool(ListFilesTool(workspace, recorder))
  tool(ReadFileTool(workspace, recorder))
  tool(WriteFileTool(workspace, recorder))
  tool(EditFileTool(workspace, recorder))
  if (networkEnabled) {
    tool(FetchUrlTool(recorder))
    tool(DownloadFileTool(workspace, recorder))
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
