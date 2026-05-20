package com.flovera.app.workspace

import android.content.Context
import com.flovera.app.storage.readUtf8Text
import com.flovera.app.storage.writeUtf8TextAtomically
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WorkspaceSnapshotRecord(
  val id: String,
  val name: String,
  val kind: String,
  val reason: String = "",
  val selectedHtmlPath: String = "",
  val createdAtMillis: Long,
  val fileCount: Int,
  val totalBytes: Long,
)

class WorkspaceSnapshotStore(
  context: Context,
  private val workspaceId: String,
  private val workspaceRoot: File,
) {
  private val snapshotsRoot = File(context.filesDir, "workspace-snapshots/$workspaceId").apply { mkdirs() }
  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun list(): List<WorkspaceSnapshotRecord> {
    return snapshotsRoot.listFiles()
      ?.filter { it.isDirectory }
      ?.mapNotNull { readManifest(it) }
      ?.sortedWith(compareByDescending<WorkspaceSnapshotRecord> { it.kind == KIND_MANUAL }.thenByDescending { it.createdAtMillis })
      ?: emptyList()
  }

  fun createManual(name: String, selectedHtmlPath: String = ""): WorkspaceSnapshotRecord {
    val normalized = name.trim().ifBlank { "Snapshot ${timestampLabel()}" }
    return create(kind = KIND_MANUAL, name = normalized, reason = "user", selectedHtmlPath = selectedHtmlPath)
  }

  fun createAutomatic(reason: String) {
    create(kind = KIND_AUTO, name = "Auto ${timestampLabel()}", reason = reason, selectedHtmlPath = "")
    pruneAutomatic()
  }

  fun restore(id: String): WorkspaceSnapshotRecord? {
    val snapshotDir = snapshotDir(id)
    val record = readManifest(snapshotDir) ?: return null
    val dataDir = File(snapshotDir, DATA_DIR)
    if (!dataDir.isDirectory) return null

    workspaceRoot.mkdirs()
    workspaceRoot.listFiles()?.forEach { it.deleteRecursively() }
    copyDirectoryContents(dataDir, workspaceRoot)
    return record
  }

  fun delete(id: String): Boolean {
    val record = readManifest(snapshotDir(id)) ?: return false
    if (record.kind == KIND_AUTO) return false
    return snapshotDir(id).deleteRecursively()
  }

  private fun create(kind: String, name: String, reason: String, selectedHtmlPath: String): WorkspaceSnapshotRecord {
    val createdAtMillis = System.currentTimeMillis()
    val id = "${kind}-${createdAtMillis}-${workspaceId.hashCode().toUInt().toString(16)}"
    val snapshotDir = snapshotDir(id)
    val dataDir = File(snapshotDir, DATA_DIR)
    snapshotDir.deleteRecursively()
    dataDir.mkdirs()
    copyDirectoryContents(workspaceRoot, dataDir)
    val stats = dataDir.walkTopDown().filter { it.isFile }.fold(SnapshotStats()) { current, file ->
      current.copy(fileCount = current.fileCount + 1, totalBytes = current.totalBytes + file.length())
    }
    val record = WorkspaceSnapshotRecord(
      id = id,
      name = name,
      kind = kind,
      reason = reason,
      selectedHtmlPath = selectedHtmlPath,
      createdAtMillis = createdAtMillis,
      fileCount = stats.fileCount,
      totalBytes = stats.totalBytes,
    )
    writeUtf8TextAtomically(File(snapshotDir, MANIFEST_FILE), json.encodeToString(record))
    return record
  }

  private fun pruneAutomatic() {
    list()
      .filter { it.kind == KIND_AUTO }
      .sortedByDescending { it.createdAtMillis }
      .drop(AUTO_SNAPSHOT_LIMIT)
      .forEach { snapshotDir(it.id).deleteRecursively() }
  }

  private fun readManifest(snapshotDir: File): WorkspaceSnapshotRecord? {
    val manifest = File(snapshotDir, MANIFEST_FILE)
    if (!manifest.isFile) return null
    return runCatching { json.decodeFromString<WorkspaceSnapshotRecord>(readUtf8Text(manifest)) }.getOrNull()
  }

  private fun snapshotDir(id: String): File = File(snapshotsRoot, id)

  private fun copyDirectoryContents(source: File, target: File) {
    if (!source.exists()) return
    source.walkTopDown().forEach { file ->
      if (file == source) return@forEach
      val relative = file.relativeTo(source)
      val destination = File(target, relative.path)
      if (file.isDirectory) {
        destination.mkdirs()
      } else {
        destination.parentFile?.mkdirs()
        file.copyTo(destination, overwrite = true)
      }
    }
  }

  private fun timestampLabel(): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
  }

  private data class SnapshotStats(
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
  )

  private companion object {
    const val KIND_AUTO = "auto"
    const val KIND_MANUAL = "manual"
    const val MANIFEST_FILE = "manifest.json"
    const val DATA_DIR = "data"
    const val AUTO_SNAPSHOT_LIMIT = 3
  }
}
