package com.flovera.app.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject

class DesktopAutomationProvider : ContentProvider() {
  override fun onCreate(): Boolean = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
    return runCatching {
      val service = FloveraAccessibilityService.requireConnected()
      val input = extras ?: Bundle.EMPTY
      val json = when (method) {
        METHOD_STATUS -> service.statusJson()
        METHOD_INSPECT -> service.inspect(
          maxNodes = input.getInt("maxNodes", 300),
          textFilter = input.getString("textFilter").orEmpty(),
          descriptionFilter = input.getString("descriptionFilter").orEmpty(),
          resourceIdFilter = input.getString("resourceIdFilter").orEmpty(),
          nodeId = input.getString("nodeId").orEmpty(),
          subtree = input.getBoolean("subtree", false),
        )
        METHOD_SCREENSHOT -> service.screenshot(java.io.File(input.getString("output").orEmpty()))
        METHOD_CLICK -> JSONObject().put(
          "completed",
          service.click(
            nodeId = input.getString("nodeId").orEmpty(),
            text = input.getString("text").orEmpty(),
            description = input.getString("description").orEmpty(),
            resourceId = input.getString("resourceId").orEmpty(),
          ),
        )
        METHOD_SET_TEXT -> JSONObject().put(
          "completed",
          service.setText(
            nodeId = input.getString("nodeId").orEmpty(),
            textMatch = input.getString("text").orEmpty(),
            description = input.getString("description").orEmpty(),
            resourceId = input.getString("resourceId").orEmpty(),
            value = input.getString("value").orEmpty(),
          ),
        )
        METHOD_TAP -> JSONObject().put(
          "completed",
          service.tap(input.getInt("x"), input.getInt("y"), input.getLong("timeoutMs")),
        )
        METHOD_SWIPE -> JSONObject().put(
          "completed",
          service.swipe(
            startX = input.getInt("startX"),
            startY = input.getInt("startY"),
            endX = input.getInt("endX"),
            endY = input.getInt("endY"),
            durationMs = input.getLong("durationMs"),
            timeoutMs = input.getLong("timeoutMs"),
          ),
        )
        METHOD_SWIPE_UNTIL_TEXT -> service.swipeUntilText(
          text = input.getString("text").orEmpty(),
          startX = input.getInt("startX"),
          startY = input.getInt("startY"),
          endX = input.getInt("endX"),
          endY = input.getInt("endY"),
          durationMs = input.getLong("durationMs"),
          timeoutMs = input.getLong("timeoutMs"),
          maxSwipes = input.getInt("maxSwipes", 5),
        )
        METHOD_GLOBAL -> JSONObject().put("completed", service.global(input.getString("action").orEmpty()))
        METHOD_LAUNCH -> JSONObject().put(
          "completed",
          service.launchApp(
            packageName = input.getString("package").orEmpty(),
            activityName = input.getString("activity").orEmpty(),
          ),
        )
        METHOD_WAIT -> service.waitFor(
          text = input.getString("text").orEmpty(),
          packageName = input.getString("package").orEmpty(),
          timeoutMs = input.getLong("timeoutMs"),
        )
        METHOD_WAIT_CHANGE -> service.waitForChange(
          previousDigest = input.getString("previousDigest").orEmpty(),
          timeoutMs = input.getLong("timeoutMs"),
        )
        else -> error("unsupported desktop automation bridge method: $method")
      }
      Bundle().apply { putString(KEY_JSON, json.toString()) }
    }.getOrElse { error ->
      Bundle().apply { putString(KEY_ERROR, "${error::class.java.simpleName}: ${error.message.orEmpty()}") }
    }
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? = null

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

  companion object {
    const val METHOD_STATUS = "status"
    const val METHOD_INSPECT = "inspect"
    const val METHOD_SCREENSHOT = "screenshot"
    const val METHOD_CLICK = "click"
    const val METHOD_SET_TEXT = "set_text"
    const val METHOD_TAP = "tap"
    const val METHOD_SWIPE = "swipe"
    const val METHOD_SWIPE_UNTIL_TEXT = "swipe_until_text"
    const val METHOD_GLOBAL = "global"
    const val METHOD_LAUNCH = "launch"
    const val METHOD_WAIT = "wait"
    const val METHOD_WAIT_CHANGE = "wait_change"
    const val KEY_JSON = "json"
    const val KEY_ERROR = "error"
  }
}

class DesktopAutomationClient(context: Context) {
  private val appContext = context.applicationContext
  private val uri = Uri.parse("content://${appContext.packageName}.desktopautomation")

  fun status(): JSONObject = call(DesktopAutomationProvider.METHOD_STATUS)

  fun inspect(
    maxNodes: Int,
    textFilter: String = "",
    descriptionFilter: String = "",
    resourceIdFilter: String = "",
    nodeId: String = "",
    subtree: Boolean = false,
  ): JSONObject = call(
    DesktopAutomationProvider.METHOD_INSPECT,
    Bundle().apply {
      putInt("maxNodes", maxNodes)
      putString("textFilter", textFilter)
      putString("descriptionFilter", descriptionFilter)
      putString("resourceIdFilter", resourceIdFilter)
      putString("nodeId", nodeId)
      putBoolean("subtree", subtree)
    },
  )

  fun screenshot(output: java.io.File): JSONObject = call(
    DesktopAutomationProvider.METHOD_SCREENSHOT,
    Bundle().apply { putString("output", output.absolutePath) },
  )

  fun click(nodeId: String, text: String, description: String, resourceId: String): Boolean = call(
    DesktopAutomationProvider.METHOD_CLICK,
    Bundle().apply {
      putString("nodeId", nodeId)
      putString("text", text)
      putString("description", description)
      putString("resourceId", resourceId)
    },
  ).optBoolean("completed")

  fun setText(nodeId: String, text: String, description: String, resourceId: String, value: String): Boolean = call(
    DesktopAutomationProvider.METHOD_SET_TEXT,
    Bundle().apply {
      putString("nodeId", nodeId)
      putString("text", text)
      putString("description", description)
      putString("resourceId", resourceId)
      putString("value", value)
    },
  ).optBoolean("completed")

  fun tap(x: Int, y: Int, timeoutMs: Long): Boolean = call(
    DesktopAutomationProvider.METHOD_TAP,
    Bundle().apply {
      putInt("x", x)
      putInt("y", y)
      putLong("timeoutMs", timeoutMs)
    },
  ).optBoolean("completed")

  fun swipe(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMs: Long,
    timeoutMs: Long,
  ): Boolean = call(
    DesktopAutomationProvider.METHOD_SWIPE,
    Bundle().apply {
      putInt("startX", startX)
      putInt("startY", startY)
      putInt("endX", endX)
      putInt("endY", endY)
      putLong("durationMs", durationMs)
      putLong("timeoutMs", timeoutMs)
    },
  ).optBoolean("completed")

  fun swipeUntilText(
    text: String,
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMs: Long,
    timeoutMs: Long,
    maxSwipes: Int,
  ): JSONObject = call(
    DesktopAutomationProvider.METHOD_SWIPE_UNTIL_TEXT,
    Bundle().apply {
      putString("text", text)
      putInt("startX", startX)
      putInt("startY", startY)
      putInt("endX", endX)
      putInt("endY", endY)
      putLong("durationMs", durationMs)
      putLong("timeoutMs", timeoutMs)
      putInt("maxSwipes", maxSwipes)
    },
  )

  fun global(action: String): Boolean = call(
    DesktopAutomationProvider.METHOD_GLOBAL,
    Bundle().apply { putString("action", action) },
  ).optBoolean("completed")

  fun launch(packageName: String, activityName: String = ""): Boolean = call(
    DesktopAutomationProvider.METHOD_LAUNCH,
    Bundle().apply {
      putString("package", packageName)
      putString("activity", activityName)
    },
  ).optBoolean("completed")

  fun waitFor(text: String, packageName: String, timeoutMs: Long): JSONObject = call(
    DesktopAutomationProvider.METHOD_WAIT,
    Bundle().apply {
      putString("text", text)
      putString("package", packageName)
      putLong("timeoutMs", timeoutMs)
    },
  )

  fun waitForChange(previousDigest: String, timeoutMs: Long): JSONObject = call(
    DesktopAutomationProvider.METHOD_WAIT_CHANGE,
    Bundle().apply {
      putString("previousDigest", previousDigest)
      putLong("timeoutMs", timeoutMs)
    },
  )

  private fun call(method: String, extras: Bundle = Bundle.EMPTY): JSONObject {
    val deadline = System.currentTimeMillis() + BRIDGE_CONNECT_TIMEOUT_MS
    var lastError = ""
    do {
      val result = appContext.contentResolver.call(uri, method, null, extras)
      if (result != null) {
        val error = result.getString(DesktopAutomationProvider.KEY_ERROR).orEmpty()
        if (error.isBlank()) {
          return JSONObject(result.getString(DesktopAutomationProvider.KEY_JSON).orEmpty())
        }
        lastError = error
        if (!error.contains("not enabled or connected", ignoreCase = true)) throw IllegalStateException(error)
      } else {
        lastError = "desktop automation bridge returned no result"
      }
      Thread.sleep(BRIDGE_CONNECT_POLL_MS)
    } while (System.currentTimeMillis() < deadline)
    error(lastError.ifBlank { "Flovera Accessibility did not reconnect in time" })
  }

  private companion object {
    const val BRIDGE_CONNECT_TIMEOUT_MS = 5_000L
    const val BRIDGE_CONNECT_POLL_MS = 200L
  }
}
