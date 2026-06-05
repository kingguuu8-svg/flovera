package com.flovera.app.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import com.flovera.app.agent.AgentRunNotifications
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

class FloveraAccessibilityService : AccessibilityService() {
  override fun onServiceConnected() {
    instance = this
    val current = DesktopAutomationStore.load(this)
    val task = if (current.status == "active" && current.lastActionId.isNotBlank()) {
      DesktopAutomationStore.intervention(
        this,
        "Flovera or its Accessibility service restarted. Review the current screen before resuming.",
      )
    } else {
      current
    }
    if (task.status == "intervention") {
      AgentRunNotifications.postNormal(
        context = this,
        title = "Flovera desktop task needs attention",
        body = task.interventionReason.ifBlank { "Open Flovera to review and continue the task." },
        ongoing = false,
        priority = NotificationCompat.PRIORITY_DEFAULT,
      )
    }
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    latestPackage = event?.packageName?.toString().orEmpty().ifBlank { latestPackage }
    latestEventAtMillis = System.currentTimeMillis()
  }

  override fun onInterrupt() {
    DesktopAutomationStore.intervention(this, "Android interrupted the accessibility service.")
    AgentRunNotifications.postNormal(
      context = this,
      title = "Flovera desktop task interrupted",
      body = "Open Flovera after restoring Accessibility access to continue from the last confirmed action.",
      ongoing = false,
      priority = NotificationCompat.PRIORITY_DEFAULT,
    )
  }

  override fun onDestroy() {
    if (instance === this) instance = null
    super.onDestroy()
  }

  fun statusJson(): JSONObject {
    val root = activeApplicationRoot()
    return JSONObject()
      .put("connected", true)
      .put("package", root?.packageName?.toString().orEmpty().ifBlank { latestPackage })
      .put("windowTitle", root?.window?.title?.toString().orEmpty())
      .put("keyguardLocked", keyguardLocked())
      .put("latestEventAtMillis", latestEventAtMillis)
  }

  fun inspect(
    maxNodes: Int,
    textFilter: String = "",
    descriptionFilter: String = "",
    resourceIdFilter: String = "",
    nodeId: String = "",
    subtree: Boolean = false,
  ): JSONObject {
    val baseRoot = awaitActiveRoot()
    val root = if (subtree && nodeId.isNotBlank()) nodeAtPath(baseRoot, nodeId)
      ?: error("subtree node was not found: $nodeId")
    else baseRoot
    val nodes = JSONArray()
    val queue = ArrayDeque<NodePath>()
    queue.add(NodePath(root, if (subtree && nodeId.isNotBlank()) nodeId else "0"))
    while (queue.isNotEmpty() && nodes.length() < maxNodes) {
      val current = queue.removeFirst()
      val currentJson = nodeJson(current.node, current.path)
      if (currentJson.matchesFilters(textFilter, descriptionFilter, resourceIdFilter)) {
        nodes.put(currentJson)
      }
      for (index in 0 until current.node.childCount) {
        current.node.getChild(index)?.let { child ->
          queue.add(NodePath(child, "${current.path}.$index"))
        }
      }
    }
    return JSONObject()
      .put("package", root.packageName?.toString().orEmpty())
      .put("windowTitle", root.window?.title?.toString().orEmpty())
      .put("keyguardLocked", keyguardLocked())
      .put("nodeCount", nodes.length())
      .put("truncated", queue.isNotEmpty())
      .put("filterText", textFilter)
      .put("filterDescription", descriptionFilter)
      .put("filterResourceId", resourceIdFilter)
      .put("subtreeRoot", if (subtree) nodeId else "")
      .put("screenDigest", screenDigest(nodes))
      .put("nodes", nodes)
  }

  fun screenshot(output: File): JSONObject {
    val latch = CountDownLatch(1)
    val result = AtomicReference<Result<Unit>>()
    val executor = Executors.newSingleThreadExecutor()
    output.parentFile?.mkdirs()
    try {
      takeScreenshot(
        android.view.Display.DEFAULT_DISPLAY,
        executor,
        object : TakeScreenshotCallback {
          override fun onSuccess(screenshot: ScreenshotResult) {
            result.set(
              runCatching {
                val hardware = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                  ?: error("Android returned an unreadable screenshot buffer")
                val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                screenshot.hardwareBuffer.close()
                FileOutputStream(output).use { stream ->
                  require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "failed to encode screenshot" }
                }
                bitmap.recycle()
                hardware.recycle()
              },
            )
            latch.countDown()
          }

          override fun onFailure(errorCode: Int) {
            result.set(Result.failure(IllegalStateException("accessibility screenshot failed with code $errorCode")))
            latch.countDown()
          }
        },
      )
      require(latch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "accessibility screenshot timed out" }
      result.get()?.getOrThrow() ?: error("accessibility screenshot produced no result")
      return JSONObject()
        .put("output", output.path)
        .put("bytes", output.length())
        .put("mimeType", "image/png")
    } finally {
      executor.shutdownNow()
    }
  }

  fun click(nodeId: String, text: String, description: String, resourceId: String): Boolean {
    val node = findNode(nodeId, text, description, resourceId) ?: return false
    var target: AccessibilityNodeInfo? = node
    while (target != null) {
      if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
      target = target.parent
    }
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    return tap(bounds.centerX(), bounds.centerY(), DEFAULT_GESTURE_TIMEOUT_MS)
  }

  fun setText(nodeId: String, textMatch: String, description: String, resourceId: String, value: String): Boolean {
    val node = findNode(nodeId, textMatch, description, resourceId)
      ?: activeApplicationRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
      ?: return false
    if (node.isPassword) return false
    val arguments = Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
    }
    return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
  }

  fun tap(x: Int, y: Int, timeoutMs: Long): Boolean {
    val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
    return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build(), timeoutMs)
  }

  fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long, timeoutMs: Long): Boolean {
    val path = Path().apply {
      moveTo(startX.toFloat(), startY.toFloat())
      lineTo(endX.toFloat(), endY.toFloat())
    }
    return dispatch(
      GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(100L, 5_000L)))
        .build(),
      timeoutMs,
    )
  }

  fun swipeUntilText(
    text: String,
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMs: Long,
    timeoutMs: Long,
    maxSwipes: Int,
  ): JSONObject {
    require(text.isNotBlank()) { "swipe-until-text requires non-empty text" }
    var lastDigest = ""
    repeat(maxSwipes.coerceIn(1, 20)) { index ->
      val before = runCatching { inspect(MAX_WAIT_INSPECTION_NODES) }.getOrNull()
      if (before?.optJSONArray("nodes").containsText(text)) {
        return JSONObject()
          .put("matched", true)
          .put("text", text)
          .put("swipes", index)
          .put("package", before?.optString("package").orEmpty())
          .put("screenDigest", before?.optString("screenDigest").orEmpty())
      }
      require(swipe(startX, startY, endX, endY, durationMs, timeoutMs)) { "swipe gesture was rejected or cancelled" }
      Thread.sleep(WAIT_POLL_MS)
      val after = runCatching { inspect(MAX_WAIT_INSPECTION_NODES) }.getOrNull()
      lastDigest = after?.optString("screenDigest").orEmpty()
      if (after?.optJSONArray("nodes").containsText(text)) {
        return JSONObject()
          .put("matched", true)
          .put("text", text)
          .put("swipes", index + 1)
          .put("package", after?.optString("package").orEmpty())
          .put("screenDigest", lastDigest)
      }
    }
    return JSONObject()
      .put("matched", false)
      .put("text", text)
      .put("swipes", maxSwipes.coerceIn(1, 20))
      .put("screenDigest", lastDigest)
  }

  fun global(action: String): Boolean {
    val globalAction = when (action) {
      "back" -> GLOBAL_ACTION_BACK
      "home" -> GLOBAL_ACTION_HOME
      "recents" -> GLOBAL_ACTION_RECENTS
      "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
      "quick-settings" -> GLOBAL_ACTION_QUICK_SETTINGS
      else -> error("unsupported global action: $action")
    }
    return performGlobalAction(globalAction)
  }

  fun launchApp(packageName: String): Boolean {
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
    startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
  }

  fun waitFor(text: String, packageName: String, timeoutMs: Long): JSONObject {
    val deadline = System.currentTimeMillis() + timeoutMs
    var observedPackage = ""
    var observedDigest = ""
    do {
      val inspection = runCatching { inspect(MAX_WAIT_INSPECTION_NODES) }.getOrNull()
      if (inspection == null) {
        Thread.sleep(WAIT_POLL_MS)
        continue
      }
      observedPackage = inspection.optString("package")
      observedDigest = inspection.optString("screenDigest")
      val packageMatched = packageName.isBlank() || inspection.optString("package") == packageName
      val textMatched = text.isBlank() || inspection.optJSONArray("nodes").containsText(text)
      if (packageMatched && textMatched) {
        return JSONObject()
          .put("matched", true)
          .put("package", inspection.optString("package"))
          .put("screenDigest", inspection.optString("screenDigest"))
      }
      Thread.sleep(WAIT_POLL_MS)
    } while (System.currentTimeMillis() < deadline)
    return JSONObject()
      .put("matched", false)
      .put("text", text)
      .put("package", packageName)
      .put("observedPackage", observedPackage)
      .put("screenDigest", observedDigest)
  }

  fun waitForChange(previousDigest: String, timeoutMs: Long): JSONObject {
    val deadline = System.currentTimeMillis() + timeoutMs
    do {
      val inspection = runCatching { inspect(MAX_WAIT_INSPECTION_NODES) }.getOrNull()
      if (inspection == null) {
        Thread.sleep(WAIT_POLL_MS)
        continue
      }
      val digest = inspection.optString("screenDigest")
      if (digest.isNotBlank() && digest != previousDigest) {
        return JSONObject()
          .put("matched", true)
          .put("package", inspection.optString("package"))
          .put("screenDigest", digest)
      }
      Thread.sleep(WAIT_POLL_MS)
    } while (System.currentTimeMillis() < deadline)
    return JSONObject().put("matched", false).put("screenDigest", previousDigest)
  }

  private fun dispatch(gesture: GestureDescription, timeoutMs: Long): Boolean {
    val latch = CountDownLatch(1)
    val completed = AtomicReference(false)
    val accepted = dispatchGesture(
      gesture,
      object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
          completed.set(true)
          latch.countDown()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
          latch.countDown()
        }
      },
      null,
    )
    if (!accepted) return false
    latch.await(timeoutMs.coerceIn(500L, 10_000L), TimeUnit.MILLISECONDS)
    return completed.get()
  }

  private fun findNode(nodeId: String, text: String, description: String, resourceId: String): AccessibilityNodeInfo? {
    val root = runCatching { awaitActiveRoot() }.getOrNull() ?: return null
    if (nodeId.isNotBlank()) return nodeAtPath(root, nodeId)
    if (resourceId.isNotBlank()) {
      root.findAccessibilityNodeInfosByViewId(resourceId).firstOrNull()?.let { return it }
    }
    if (text.isNotBlank()) {
      val normalized = text.trim()
      matchingNode(root) { node -> node.safeText().contains(normalized, ignoreCase = true) }?.let { return it }
      matchingNode(root) { node ->
        node.contentDescription?.toString().orEmpty().contains(normalized, ignoreCase = true)
      }?.let { return it }
    }
    if (description.isNotBlank()) {
      val normalized = description.trim()
      matchingNode(root) { node ->
        node.contentDescription?.toString().orEmpty().contains(normalized, ignoreCase = true)
      }?.let { return it }
    }
    return null
  }

  private fun matchingNode(
    root: AccessibilityNodeInfo,
    predicate: (AccessibilityNodeInfo) -> Boolean,
  ): AccessibilityNodeInfo? {
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    while (queue.isNotEmpty()) {
      val node = queue.removeFirst()
      if (predicate(node)) return node
      for (index in 0 until node.childCount) {
        node.getChild(index)?.let(queue::add)
      }
    }
    return null
  }

  private fun nodeAtPath(root: AccessibilityNodeInfo, path: String): AccessibilityNodeInfo? {
    val indexes = path.split('.').mapNotNull(String::toIntOrNull)
    if (indexes.firstOrNull() != 0) return null
    var current: AccessibilityNodeInfo = root
    indexes.drop(1).forEach { index ->
      current = current.getChild(index) ?: return null
    }
    return current
  }

  private fun nodeJson(node: AccessibilityNodeInfo, path: String): JSONObject {
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    return JSONObject()
      .put("nodeId", path)
      .put("text", node.safeText())
      .put("description", if (node.isPassword) "" else node.contentDescription?.toString().orEmpty())
      .put("resourceId", node.viewIdResourceName.orEmpty())
      .put("class", node.className?.toString().orEmpty())
      .put("package", node.packageName?.toString().orEmpty())
      .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
      .put("clickable", node.isClickable)
      .put("editable", node.isEditable)
      .put("scrollable", node.isScrollable)
      .put("focused", node.isFocused)
      .put("enabled", node.isEnabled)
      .put("visible", node.isVisibleToUser)
  }

  private fun AccessibilityNodeInfo.safeText(): String {
    return if (isPassword) "" else text?.toString().orEmpty()
  }

  private fun JSONArray?.containsText(expected: String): Boolean {
    val normalized = expected.trim()
    if (this == null) return false
    for (index in 0 until length()) {
      val node = optJSONObject(index) ?: continue
      if (
        node.optString("text").contains(normalized, ignoreCase = true) ||
        node.optString("description").contains(normalized, ignoreCase = true)
      ) {
        return true
      }
    }
    return false
  }

  private fun JSONObject.matchesFilters(text: String, description: String, resourceId: String): Boolean {
    return (text.isBlank() || optString("text").contains(text, ignoreCase = true)) &&
      (description.isBlank() || optString("description").contains(description, ignoreCase = true)) &&
      (resourceId.isBlank() || optString("resourceId").contains(resourceId, ignoreCase = true))
  }

  private fun screenDigest(nodes: JSONArray): String {
    return Integer.toHexString(nodes.toString().hashCode())
  }

  private fun awaitActiveRoot(): AccessibilityNodeInfo {
    val deadline = System.currentTimeMillis() + ACTIVE_WINDOW_TIMEOUT_MS
    do {
      activeApplicationRoot()?.let { return it }
      Thread.sleep(WAIT_POLL_MS)
    } while (System.currentTimeMillis() < deadline)
    error("active window is unavailable after ${ACTIVE_WINDOW_TIMEOUT_MS}ms")
  }

  private fun activeApplicationRoot(): AccessibilityNodeInfo? {
    val applicationWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
    return applicationWindows.firstNotNullOfOrNull { window ->
      if (window.isFocused) window.root else null
    } ?: applicationWindows.firstNotNullOfOrNull { window ->
      if (window.isActive) window.root else null
    } ?: rootInActiveWindow
      ?.takeIf { root -> root.window?.type == AccessibilityWindowInfo.TYPE_APPLICATION }
      ?: applicationWindows.firstNotNullOfOrNull(AccessibilityWindowInfo::getRoot)
  }

  private fun keyguardLocked(): Boolean {
    return getSystemService(KeyguardManager::class.java).isKeyguardLocked
  }

  private data class NodePath(val node: AccessibilityNodeInfo, val path: String)

  companion object {
    @Volatile private var instance: FloveraAccessibilityService? = null
    @Volatile private var latestPackage: String = ""
    @Volatile private var latestEventAtMillis: Long = 0L
    private const val SCREENSHOT_TIMEOUT_MS = 10_000L
    private const val DEFAULT_GESTURE_TIMEOUT_MS = 3_000L
    private const val WAIT_POLL_MS = 250L
    private const val ACTIVE_WINDOW_TIMEOUT_MS = 3_000L
    private const val MAX_WAIT_INSPECTION_NODES = 500

    fun requireConnected(): FloveraAccessibilityService {
      return instance ?: error("Flovera Accessibility is not enabled or connected")
    }
  }
}
