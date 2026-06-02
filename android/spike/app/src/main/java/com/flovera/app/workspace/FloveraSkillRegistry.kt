package com.flovera.app.workspace

import com.flovera.app.storage.readUtf8Text
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class FloveraSkillManifest(
  val version: Int = 1,
  val skills: List<FloveraSkillRegistration> = FloveraSkillRegistry.defaultRegistrations,
)

@Serializable
data class FloveraSkillRegistration(
  val id: String,
  val path: String = ".flovera/skills/$id/SKILL.md",
  val enabled: Boolean = true,
)

data class FloveraSkillPromptDescriptor(
  val id: String,
  val path: String,
  val name: String,
  val description: String,
)

object FloveraSkillRegistry {
  val defaultRegistrations: List<FloveraSkillRegistration> = listOf(
    FloveraSkillRegistration(id = "flovera-android-webview-app"),
    FloveraSkillRegistration(id = "flovera-context-handoff"),
    FloveraSkillRegistration(id = "flovera-jvm-groovy"),
    FloveraSkillRegistration(id = "flovera-mcp-adapter"),
  )

  fun defaultPromptDescriptors(): String {
    return defaultRegistrations.joinToString("\n") { registration ->
      val descriptor = descriptorFromSkillBody(
        id = registration.id,
        path = registration.path,
        body = defaultSkillBody(registration.id),
      )
      descriptor.toPromptLine()
    }
  }

  fun promptDescriptors(workspaceRoot: File, json: Json): String {
    val manifest = loadManifest(workspaceRoot, json)
    return manifest.skills
      .asSequence()
      .filter { it.enabled }
      .mapNotNull { registration ->
        val path = normalizedSkillPath(registration)
        val file = workspaceFile(workspaceRoot, path) ?: return@mapNotNull null
        if (!file.isFile) return@mapNotNull null
        descriptorFromSkillBody(
          id = registration.id.ifBlank { file.parentFile?.name.orEmpty() },
          path = path,
          body = runCatching { readUtf8Text(file) }.getOrDefault(""),
        )
      }
      .take(MAX_PROMPT_SKILLS)
      .joinToString("\n") { it.toPromptLine() }
  }

  fun defaultSkillBody(id: String): String {
    return when (id) {
      "flovera-android-webview-app" -> """
        ---
        name: flovera-android-webview-app
        description: Use when creating or fixing a Flovera workspace app, HTML preview, mobile WebView surface, or flovera.app.json registration.
        ---

        # Flovera Android WebView App

        Required workflow:
        - Build portable files first: `README.md`, `src/`, optional `src/server.py`, `src/web/`, `data/`, `outputs/`, and `flovera.app.json`.
        - Design for Android/mobile WebView before desktop. Use responsive layout, readable touch targets, safe bottom spacing, and stable first-screen content.
        - Prefer `local_http` with a Python stdlib `python_http` server for interactive apps. Use standard HTTP/fetch/SSE.
        - Keep `flovera.app.json` as a small adapter. Do not invent a project-specific JSON handoff protocol as the main integration.
        - After writing or changing `flovera.app.json`, call `artifact_diagnose`. Do not claim registration or usability until diagnostics confirm the manifest and preview path.
        - If unsure about the manifest shape, call `artifact_diagnose` with `includeReference=true` and compare with the hidden reference app.
      """.trimIndent()

      "flovera-context-handoff" -> """
        ---
        name: flovera-context-handoff
        description: Use when continuing after compression, interruption, retry, provider overflow, or a long tool-heavy session.
        ---

        # Flovera Context Handoff

        Required workflow:
        - Treat the latest handoff summary, current user request, failed tool results, and recent artifact paths as higher priority than old successful stdout.
        - Do not replay completed tool work unless verification or recovery requires it.
        - Preserve actionable error tails, generated paths, active TODOs, and user guidance.
        - If a previous run was interrupted, continue from the saved partial transcript/tool history instead of restarting from scratch.
        - Report what was reused from history and what was reverified.
      """.trimIndent()

      "flovera-jvm-groovy" -> """
        ---
        name: flovera-jvm-groovy
        description: Use when a task needs JVM libraries, Groovy scripts, jars, Maven coordinates, or document-processing libraries that CPython cannot cover well.
        ---

        # Flovera JVM Groovy Runtime

        Required workflow:
        - Use `workspace_command_run` with argv such as `["groovy", "tools/script.groovy"]` only when JVM access is materially useful.
        - Put pure JVM jars under `libs/`, or declare Maven coordinates in `libs/maven.json` or `.flovera/jvm/maven.json`.
        - For isolated tests, prefer a temporary Maven config and pass `FLOVERA_JVM_MAVEN_CONFIG=<workspace-relative-json>` in environment.
        - Expect Android-incompatible APIs or native JVM artifacts to fail during D8/dex loading. Use `failureCategory` and `.flovera/logs/jvm-build.jsonl` to locate the failing stage.
        - Heavy first runs may spend time resolving Maven and preparing dex caches. Do not treat slow progress as failure while jvm build progress is moving.
      """.trimIndent()

      "flovera-mcp-adapter" -> """
        ---
        name: flovera-mcp-adapter
        description: Use when planning or prototyping a lightweight Flovera-side MCP adapter or server rewrite workflow.
        ---

        # Flovera MCP Adapter Planning

        Required workflow:
        - Treat MCP support as proposal/scaffolding unless the requested adapter has been implemented and verified inside Flovera.
        - Prefer translating a narrow MCP server capability into workspace files and registered Flovera tools/artifacts over adding broad native tool surfaces.
        - Identify server inputs, outputs, auth needs, filesystem boundaries, network needs, and long-running process assumptions.
        - If the original MCP server depends on npm, daemons, OS shell behavior, native binaries, or hidden secrets, call out the platform gap and design a bounded replacement.
      """.trimIndent()

      else -> ""
    }
  }

  private fun loadManifest(workspaceRoot: File, json: Json): FloveraSkillManifest {
    val manifest = File(workspaceRoot, SKILL_MANIFEST_PATH)
    if (!manifest.isFile) return FloveraSkillManifest()
    return runCatching { json.decodeFromString<FloveraSkillManifest>(readUtf8Text(manifest)) }
      .getOrDefault(FloveraSkillManifest())
  }

  private fun descriptorFromSkillBody(id: String, path: String, body: String): FloveraSkillPromptDescriptor {
    val metadata = frontmatter(body)
    return FloveraSkillPromptDescriptor(
      id = id,
      path = path,
      name = metadata["name"].orEmpty().ifBlank { id },
      description = metadata["description"].orEmpty().ifBlank { firstHeading(body).ifBlank { "No description provided." } },
    )
  }

  private fun frontmatter(body: String): Map<String, String> {
    val lines = body.lineSequence().toList()
    if (lines.firstOrNull()?.trim() != "---") return emptyMap()
    val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
    if (end < 0) return emptyMap()
    return lines.drop(1).take(end).mapNotNull { line ->
      val index = line.indexOf(':')
      if (index <= 0) return@mapNotNull null
      line.take(index).trim() to line.drop(index + 1).trim()
    }.toMap()
  }

  private fun firstHeading(body: String): String {
    return body.lineSequence()
      .firstOrNull { it.trimStart().startsWith("# ") }
      ?.trimStart()
      ?.removePrefix("# ")
      ?.trim()
      .orEmpty()
  }

  private fun normalizedSkillPath(registration: FloveraSkillRegistration): String {
    val path = registration.path.ifBlank { ".flovera/skills/${registration.id}/SKILL.md" }
    return path.replace('\\', '/').trimStart('/')
  }

  private fun workspaceFile(workspaceRoot: File, path: String): File? {
    val file = File(workspaceRoot, path).canonicalFile
    val root = workspaceRoot.canonicalFile
    if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) return null
    return file
  }

  private fun FloveraSkillPromptDescriptor.toPromptLine(): String {
    return "- $id (`$name`): $description Path: $path."
  }

  const val SKILL_MANIFEST_PATH = ".flovera/skills/manifest.json"
  private const val MAX_PROMPT_SKILLS = 12
}
