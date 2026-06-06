package com.flovera.app.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ComponentName
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
    ocrTextFilter: String = "",
    nodeId: String = "",
    subtree: Boolean = false,
    withOcr: Boolean = false,
  ): JSONObject {
    val baseRoot = awaitActiveRoot()
    val root = if (subtree && nodeId.isNotBlank()) nodeAtPath(baseRoot, nodeId)
      ?: error("subtree node was not found: $nodeId")
    else baseRoot
    val ocrSnapshot = if (withOcr || ocrTextFilter.isNotBlank()) runCatching { ocrSnapshot() }.getOrNull() else null
    val nodes = JSONArray()
    val queue = ArrayDeque<NodePath>()
    queue.add(NodePath(root, if (subtree && nodeId.isNotBlank()) nodeId else "0"))
    while (queue.isNotEmpty() && nodes.length() < maxNodes) {
      val current = queue.removeFirst()
      val currentJson = nodeJson(current.node, current.path)
      if (ocrSnapshot != null) attachOcr(currentJson, ocrSnapshot.blocks)
      if (currentJson.matchesFilters(textFilter, descriptionFilter, resourceIdFilter, ocrTextFilter)) {
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
      .put("filterOcrText", ocrTextFilter)
      .put("subtreeRoot", if (subtree) nodeId else "")
      .put("withOcr", ocrSnapshot != null)
      .put("ocrEngine", ocrSnapshot?.engine.orEmpty())
      .put("ocrBlockCount", ocrSnapshot?.blocks?.size ?: 0)
      .put("ocrTextMatched", if (ocrTextFilter.isBlank()) false else ocrSnapshot?.containsText(ocrTextFilter) == true)
      .put("screenDigest", screenDigest(nodes))
      .put("nodes", nodes)
  }

  fun ocr(textFilter: String = "", maxBlocks: Int = 200): JSONObject {
    val snapshot = ocrSnapshot()
    val blocks = JSONArray()
    snapshot.blocks
      .asSequence()
      .filter { textFilter.isBlank() || it.text.contains(textFilter, ignoreCase = true) }
      .take(maxBlocks.coerceIn(1, 500))
      .forEach { blocks.put(it.toJson()) }
    return JSONObject()
      .put("engine", snapshot.engine)
      .put("width", snapshot.width)
      .put("height", snapshot.height)
      .put("filterText", textFilter)
      .put("blockCount", blocks.length())
      .put("blocks", blocks)
  }

  fun screenshot(output: File): JSONObject {
    val bitmap = captureScreenshotBitmap()
    output.parentFile?.mkdirs()
    try {
      FileOutputStream(output).use { stream ->
        require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "failed to encode screenshot" }
      }
    } finally {
      bitmap.recycle()
    }
    return JSONObject()
      .put("output", output.path)
      .put("bytes", output.length())
      .put("mimeType", "image/png")
  }

  private fun captureScreenshotBitmap(): Bitmap {
    var lastError: Throwable? = null
    repeat(SCREENSHOT_MAX_ATTEMPTS) { attempt ->
      try {
        return captureScreenshotBitmapOnce()
      } catch (error: Throwable) {
        lastError = error
        if (attempt < SCREENSHOT_MAX_ATTEMPTS - 1) {
          Thread.sleep(SCREENSHOT_RETRY_DELAY_MS * (attempt + 1))
        }
      }
    }
    throw lastError ?: IllegalStateException("accessibility screenshot failed")
  }

  private fun captureScreenshotBitmapOnce(): Bitmap {
    val latch = CountDownLatch(1)
    val result = AtomicReference<Result<Bitmap>>()
    val executor = Executors.newSingleThreadExecutor()
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
                hardware.recycle()
                bitmap
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
      return result.get()?.getOrThrow() ?: error("accessibility screenshot produced no result")
    } finally {
      executor.shutdownNow()
    }
  }

  fun click(nodeId: String, text: String, description: String, resourceId: String, ocrText: String): JSONObject {
    val node = findNode(nodeId, text, description, resourceId)
    if (node != null) {
      val completed = clickNodeOrBounds(node)
      return JSONObject()
        .put("completed", completed)
        .put("strategy", "accessibility")
    }
    if (ocrText.isNotBlank()) {
      return clickByOcrText(ocrText)
    }
    return JSONObject().put("completed", false).put("strategy", "none")
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

  fun launchApp(packageName: String, activityName: String): Boolean {
    val explicitIntent = if (activityName.isNotBlank()) {
      Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(packageName, activityName))
    } else {
      null
    }
    val launchIntent = explicitIntent ?: packageManager.getLaunchIntentForPackage(packageName) ?: return false
    startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
  }

  fun waitFor(text: String, packageName: String, timeoutMs: Long): JSONObject {
    return waitFor(text = text, ocrText = "", packageName = packageName, timeoutMs = timeoutMs)
  }

  fun waitFor(text: String, ocrText: String, packageName: String, timeoutMs: Long): JSONObject {
    val deadline = System.currentTimeMillis() + timeoutMs
    var observedPackage = ""
    var observedDigest = ""
    var ocrMatched = false
    do {
      val inspection = runCatching {
        inspect(
          maxNodes = MAX_WAIT_INSPECTION_NODES,
          ocrTextFilter = ocrText,
          withOcr = ocrText.isNotBlank(),
        )
      }.getOrNull()
      if (inspection == null) {
        Thread.sleep(WAIT_POLL_MS)
        continue
      }
      observedPackage = inspection.optString("package")
      observedDigest = inspection.optString("screenDigest")
      val packageMatched = packageName.isBlank() || inspection.optString("package") == packageName
      val textMatched = text.isBlank() || inspection.optJSONArray("nodes").containsText(text)
      ocrMatched = ocrText.isBlank() || inspection.optBoolean("ocrTextMatched")
      if (packageMatched && textMatched && ocrMatched) {
        return JSONObject()
          .put("matched", true)
          .put("text", text)
          .put("ocrText", ocrText)
          .put("ocrMatched", ocrMatched)
          .put("package", inspection.optString("package"))
          .put("screenDigest", inspection.optString("screenDigest"))
      }
      Thread.sleep(WAIT_POLL_MS)
    } while (System.currentTimeMillis() < deadline)
    return JSONObject()
      .put("matched", false)
      .put("text", text)
      .put("ocrText", ocrText)
      .put("ocrMatched", ocrMatched)
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

  private fun clickNodeOrBounds(node: AccessibilityNodeInfo): Boolean {
    var target: AccessibilityNodeInfo? = node
    while (target != null) {
      if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
      target = target.parent
    }
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    return tap(bounds.centerX(), bounds.centerY(), DEFAULT_GESTURE_TIMEOUT_MS)
  }

  private fun clickByOcrText(ocrText: String): JSONObject {
    val normalized = ocrText.trim()
    val snapshot = ocrSnapshot()
    val block = snapshot.blocks.firstOrNull { it.text.contains(normalized, ignoreCase = true) }
      ?: return JSONObject()
        .put("completed", false)
        .put("strategy", "ocr")
        .put("ocrText", ocrText)
        .put("reason", "ocr text was not observed")
    val target = clickableNodeForOcrBlock(awaitActiveRoot(), block)
    if (target != null) {
      return JSONObject()
        .put("completed", clickNodeOrBounds(target))
        .put("strategy", "ocr_clickable_node")
        .put("ocrText", block.text)
        .put("ocrBounds", block.boundsJson())
    }
    return JSONObject()
      .put("completed", tap(block.bounds.centerX(), block.bounds.centerY(), DEFAULT_GESTURE_TIMEOUT_MS))
      .put("strategy", "ocr_bounds_tap")
      .put("ocrText", block.text)
      .put("ocrBounds", block.boundsJson())
  }

  private fun clickableNodeForOcrBlock(root: AccessibilityNodeInfo, block: OcrBlock): AccessibilityNodeInfo? {
    var best: AccessibilityNodeInfo? = null
    var bestArea = Int.MAX_VALUE
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    while (queue.isNotEmpty()) {
      val node = queue.removeFirst()
      val bounds = Rect()
      node.getBoundsInScreen(bounds)
      if (node.isClickable && bounds.isUsable() && bounds.contains(block.bounds.centerX(), block.bounds.centerY())) {
        val area = bounds.width() * bounds.height()
        if (area < bestArea) {
          best = node
          bestArea = area
        }
      }
      for (index in 0 until node.childCount) {
        node.getChild(index)?.let(queue::add)
      }
    }
    return best
  }

  private fun ocrSnapshot(): OcrSnapshot {
    val bitmap = captureScreenshotBitmap()
    try {
      val json = MlKitOcrEngine.recognize(this, bitmap, OCR_TIMEOUT_MS)
      return OcrSnapshot(
        engine = json.optString("engine"),
        width = json.optInt("width"),
        height = json.optInt("height"),
        blocks = parseOcrBlocks(json.optJSONArray("blocks")),
      )
    } finally {
      bitmap.recycle()
    }
  }

  private fun parseOcrBlocks(blocksJson: JSONArray?): List<OcrBlock> {
    val blocks = mutableListOf<OcrBlock>()
    if (blocksJson == null) return blocks
    for (index in 0 until blocksJson.length()) {
      val block = blocksJson.optJSONObject(index) ?: continue
      val bounds = block.optJSONArray("bounds") ?: continue
      blocks.add(
        OcrBlock(
          id = block.optString("id"),
          kind = block.optString("kind"),
          text = block.optString("text"),
          bounds = Rect(
            bounds.optInt(0),
            bounds.optInt(1),
            bounds.optInt(2),
            bounds.optInt(3),
          ),
        ),
      )
    }
    return blocks.filter { it.text.isNotBlank() }
  }

  private fun attachOcr(nodeJson: JSONObject, blocks: List<OcrBlock>) {
    val bounds = nodeJson.boundsRect()
    if (!bounds.isUsable()) return
    val attached = blocks
      .asSequence()
      .filter { block ->
        bounds.contains(block.bounds.centerX(), block.bounds.centerY()) ||
          blockOverlapRatio(bounds, block.bounds) >= OCR_ATTACH_MIN_OVERLAP
      }
      .take(MAX_ATTACHED_OCR_BLOCKS)
      .toList()
    if (attached.isEmpty()) return
    nodeJson
      .put("ocrText", attached.joinToString(" ") { it.text })
      .put("ocrBlocks", JSONArray(attached.map { it.toJson() }))
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
        node.optString("description").contains(normalized, ignoreCase = true) ||
        node.optString("ocrText").contains(normalized, ignoreCase = true)
      ) {
        return true
      }
    }
    return false
  }

  private fun JSONObject.matchesFilters(text: String, description: String, resourceId: String, ocrText: String): Boolean {
    return (text.isBlank() || optString("text").contains(text, ignoreCase = true)) &&
      (description.isBlank() || optString("description").contains(description, ignoreCase = true)) &&
      (resourceId.isBlank() || optString("resourceId").contains(resourceId, ignoreCase = true)) &&
      (ocrText.isBlank() || optString("ocrText").contains(ocrText, ignoreCase = true))
  }

  private fun OcrSnapshot.containsText(expected: String): Boolean {
    val normalized = expected.trim()
    return normalized.isBlank() || blocks.any { it.text.contains(normalized, ignoreCase = true) }
  }

  private fun JSONObject.boundsRect(): Rect {
    val bounds = optJSONArray("bounds") ?: return Rect()
    return Rect(
      bounds.optInt(0),
      bounds.optInt(1),
      bounds.optInt(2),
      bounds.optInt(3),
    )
  }

  private fun Rect.isUsable(): Boolean = width() > 0 && height() > 0

  private fun blockOverlapRatio(nodeBounds: Rect, blockBounds: Rect): Double {
    val intersection = Rect(nodeBounds)
    if (!intersection.intersect(blockBounds)) return 0.0
    val blockArea = blockBounds.width().coerceAtLeast(1) * blockBounds.height().coerceAtLeast(1)
    val intersectionArea = intersection.width().coerceAtLeast(0) * intersection.height().coerceAtLeast(0)
    return intersectionArea.toDouble() / blockArea.toDouble()
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

  private data class OcrSnapshot(
    val engine: String,
    val width: Int,
    val height: Int,
    val blocks: List<OcrBlock>,
  )

  private data class OcrBlock(
    val id: String,
    val kind: String,
    val text: String,
    val bounds: Rect,
  ) {
    fun boundsJson(): JSONArray = JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))

    fun toJson(): JSONObject = JSONObject()
      .put("id", id)
      .put("kind", kind)
      .put("text", text)
      .put("bounds", boundsJson())
      .put("centerX", bounds.centerX())
      .put("centerY", bounds.centerY())
  }

  companion object {
    @Volatile private var instance: FloveraAccessibilityService? = null
    @Volatile private var latestPackage: String = ""
    @Volatile private var latestEventAtMillis: Long = 0L
    private const val SCREENSHOT_TIMEOUT_MS = 10_000L
    private const val SCREENSHOT_MAX_ATTEMPTS = 5
    private const val SCREENSHOT_RETRY_DELAY_MS = 250L
    private const val DEFAULT_GESTURE_TIMEOUT_MS = 3_000L
    private const val WAIT_POLL_MS = 250L
    private const val ACTIVE_WINDOW_TIMEOUT_MS = 3_000L
    private const val MAX_WAIT_INSPECTION_NODES = 500
    private const val OCR_TIMEOUT_MS = 10_000L
    private const val OCR_ATTACH_MIN_OVERLAP = 0.35
    private const val MAX_ATTACHED_OCR_BLOCKS = 8

    fun requireConnected(): FloveraAccessibilityService {
      return instance ?: error("Flovera Accessibility is not enabled or connected")
    }
  }
}
