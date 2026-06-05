package com.flovera.app.platform

import android.content.Context
import com.flovera.app.storage.readUtf8Text
import com.flovera.app.storage.writeUtf8TextAtomically
import java.io.File
import org.json.JSONObject

data class DesktopAutomationTask(
  val goal: String = "",
  val status: String = "idle",
  val lastActionId: String = "",
  val lastAction: String = "",
  val lastResult: String = "",
  val confirmedActionIds: List<String> = emptyList(),
  val interventionReason: String = "",
  val updatedAtMillis: Long = 0L,
) {
  fun toJson(): JSONObject {
    return JSONObject()
      .put("goal", goal)
      .put("status", status)
      .put("lastActionId", lastActionId)
      .put("lastAction", lastAction)
      .put("lastResult", lastResult)
      .put("confirmedActionIds", org.json.JSONArray(confirmedActionIds))
      .put("interventionReason", interventionReason)
      .put("updatedAtMillis", updatedAtMillis)
  }
}

object DesktopAutomationStore {
  private const val PREFS = "desktop_automation"

  @Synchronized
  fun load(context: Context): DesktopAutomationTask {
    val file = taskFile(context)
    val raw = if (file.isFile) runCatching { readUtf8Text(file) }.getOrDefault("") else ""
    if (raw.isBlank()) return DesktopAutomationTask()
    return runCatching {
      val json = JSONObject(raw)
      DesktopAutomationTask(
        goal = json.optString("goal"),
        status = json.optString("status", "idle"),
        lastActionId = json.optString("lastActionId"),
        lastAction = json.optString("lastAction"),
        lastResult = json.optString("lastResult"),
        confirmedActionIds = json.optJSONArray("confirmedActionIds").toStringList(),
        interventionReason = json.optString("interventionReason"),
        updatedAtMillis = json.optLong("updatedAtMillis"),
      )
    }.getOrDefault(DesktopAutomationTask())
  }

  @Synchronized
  fun start(context: Context, goal: String): DesktopAutomationTask {
    require(goal.isNotBlank()) { "desktop task goal must be non-empty" }
    return save(context, DesktopAutomationTask(goal = goal, status = "active", updatedAtMillis = now()))
  }

  @Synchronized
  fun actionConfirmed(
    context: Context,
    actionId: String,
    action: String,
    result: String,
  ): DesktopAutomationTask {
    val current = load(context)
    return save(
      context,
      current.copy(
        status = "active",
        lastActionId = actionId.ifBlank { current.lastActionId },
        lastAction = action,
        lastResult = result,
        confirmedActionIds = (current.confirmedActionIds + actionId)
          .filter(String::isNotBlank)
          .distinct()
          .takeLast(MAX_CONFIRMED_ACTION_IDS),
        interventionReason = "",
        updatedAtMillis = now(),
      ),
    )
  }

  @Synchronized
  fun intervention(context: Context, reason: String): DesktopAutomationTask {
    val current = load(context)
    return save(
      context,
      current.copy(
        status = "intervention",
        interventionReason = reason,
        updatedAtMillis = now(),
      ),
    )
  }

  @Synchronized
  fun resume(context: Context): DesktopAutomationTask {
    val current = load(context)
    require(current.goal.isNotBlank()) { "no desktop task is available to resume" }
    return save(context, current.copy(status = "active", interventionReason = "", updatedAtMillis = now()))
  }

  @Synchronized
  fun finish(context: Context, status: String, summary: String): DesktopAutomationTask {
    require(status in setOf("completed", "cancelled")) { "unsupported terminal desktop task status: $status" }
    val current = load(context)
    return save(
      context,
      current.copy(
        status = status,
        lastResult = summary.ifBlank { current.lastResult },
        interventionReason = "",
        updatedAtMillis = now(),
      ),
    )
  }

  @Synchronized
  fun alreadyConfirmed(context: Context, actionId: String): Boolean {
    if (actionId.isBlank()) return false
    val current = load(context)
    return actionId in current.confirmedActionIds && current.status in setOf("active", "completed")
  }

  private fun save(context: Context, task: DesktopAutomationTask): DesktopAutomationTask {
    writeUtf8TextAtomically(taskFile(context), task.toJson().toString())
    return task
  }

  private fun taskFile(context: Context): File {
    return File(context.filesDir, "$PREFS/task.json")
  }

  private fun now(): Long = System.currentTimeMillis()

  private fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
      for (index in 0 until length()) {
        optString(index).takeIf(String::isNotBlank)?.let(::add)
      }
    }
  }

  private const val MAX_CONFIRMED_ACTION_IDS = 200
}
