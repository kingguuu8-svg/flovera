package com.flovera.app.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.location.LocationManager
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.flovera.app.R
import com.flovera.app.agent.AgentRunForegroundService
import com.flovera.app.agent.AgentRunNotifications
import com.flovera.app.workspace.WorkspaceManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

data class AndroidSystemCommandResult(
  val status: String,
  val output: String,
  val exitCode: Int = if (status == "ok") 0 else 1,
)

class AndroidSystemCommandApi(
  private val context: Context,
  private val workspace: WorkspaceManager,
  private val networkEnabled: Boolean,
) {
  private val appContext = context.applicationContext
  private val desktopAutomation by lazy { DesktopAutomationClient(appContext) }
  private var lastDesktopFeedback: JSONObject? = null

  fun execute(argv: List<String>): AndroidSystemCommandResult {
    val profile = argv.firstOrNull()?.lowercase().orEmpty()
    val args = AndroidCommandArgs(argv.drop(1))
    return runCatching {
      when (profile) {
        "help", "capabilities" -> ok(capabilities())
        "app" -> app(args)
        "permission" -> permission(args)
        "notification" -> notification(args)
        "camera" -> camera(args)
        "microphone" -> microphone(args)
        "location" -> location(args)
        "contacts" -> contacts(args)
        "calendar" -> calendar(args)
        "media" -> media(args)
        "bluetooth" -> bluetooth(args)
        "overlay" -> overlay(args)
        "storage" -> storage(args)
        "package" -> packageInstall(args)
        "alarm" -> alarm(args)
        "network" -> network(args)
        "foreground" -> foreground(args)
        "intent" -> intent(args)
        "ui" -> ui(args)
        else -> fail("unsupported Android profile: $profile. Run `android help` for supported profiles.")
      }
    }.getOrElse { throwable ->
      fail("${throwable::class.java.simpleName}: ${throwable.message.orEmpty()}")
    }
  }

  private fun app(args: AndroidCommandArgs): AndroidSystemCommandResult {
    return when (args.command("info")) {
      "info" -> ok(
        JSONObject()
          .put("package", appContext.packageName)
          .put("sdk", Build.VERSION.SDK_INT)
          .put("permissionsPanel", true)
          .put("profiles", JSONArray(PROFILES))
          .toString(2),
      )
      else -> fail("unsupported android app command")
    }
  }

  private fun permission(args: AndroidCommandArgs): AndroidSystemCommandResult {
    return when (args.command("status")) {
      "status" -> {
        val values = JSONArray()
        AndroidPermissionCapabilities.status(appContext).forEach { status ->
          values.put(
            JSONObject()
              .put("id", status.capability.id)
              .put("state", status.state)
              .put("type", status.capability.type.name.lowercase()),
          )
        }
        ok(JSONObject().put("permissions", values).toString(2))
      }
      "open" -> {
        val id = args.positional(1).ifBlank { "app_details" }
        ok(JSONObject().put("permission", id).put("opened", AndroidPermissionCapabilities.openPermission(appContext, id)).toString())
      }
      else -> fail("supported: android permission status | android permission open <id>")
    }
  }

  private fun notification(args: AndroidCommandArgs): AndroidSystemCommandResult {
    requirePermission("notifications")
    return when (args.command()) {
      "post" -> {
        val id = args.int("id", DEFAULT_NOTIFICATION_ID)
        val title = args.required("title")
        val body = args.required("body")
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(appContext, SYSTEM_CHANNEL_ID)
          .setSmallIcon(R.mipmap.ic_launcher)
          .setContentTitle(title)
          .setContentText(body)
          .setStyle(NotificationCompat.BigTextStyle().bigText(body))
          .setAutoCancel(true)
          .setPriority(NotificationCompat.PRIORITY_DEFAULT)
          .build()
        NotificationManagerCompat.from(appContext).notify(id, notification)
        ok(JSONObject().put("posted", true).put("id", id).toString())
      }
      "cancel" -> {
        val id = args.int("id", DEFAULT_NOTIFICATION_ID)
        NotificationManagerCompat.from(appContext).cancel(id)
        ok(JSONObject().put("cancelled", true).put("id", id).toString())
      }
      else -> fail("supported: android notification post --title <text> --body <text> [--id <int>] | cancel")
    }
  }

  private fun ui(args: AndroidCommandArgs): AndroidSystemCommandResult {
    return when (args.command("status")) {
      "status" -> {
        val service = runCatching { desktopAutomation.status() }.getOrElse { error ->
          JSONObject().put("connected", false).put("bridgeError", error.message.orEmpty())
        }
        if (!service.optBoolean("connected")) notifyDesktopIntervention("Flovera desktop operation is disconnected. Open Accessibility settings and re-enable Flovera.")
        ok(
          service
            .put("permissionState", permissionState("accessibility"))
            .put("diagnosis", desktopDiagnosis(service))
            .put("task", DesktopAutomationStore.load(appContext).toJson())
            .toString(2),
        )
      }
      "open-settings" -> ok(
        JSONObject()
          .put("opened", AndroidPermissionCapabilities.openPermission(appContext, "accessibility"))
          .toString(),
      )
      "inspect" -> {
        val maxNodes = args.int("max-nodes", 300).coerceIn(1, 1_000)
        desktopFeedback("正在操作手机", "检查当前界面", ongoing = true)
        ok(
          desktopAutomation.inspect(
            maxNodes = maxNodes,
            textFilter = args.string("filter-text"),
            descriptionFilter = args.string("filter-description"),
            resourceIdFilter = args.string("filter-resource-id"),
            ocrTextFilter = args.string("filter-ocr-text"),
            nodeId = args.string("node-id"),
            subtree = args.has("subtree"),
            withOcr = args.has("with-ocr") || args.string("filter-ocr-text").isNotBlank(),
          ).toString(2),
        )
      }
      "ocr" -> {
        desktopFeedback("正在操作手机", "识别当前屏幕文字", ongoing = true)
        ok(
          desktopAutomation.ocr(
            textFilter = args.string("filter-text"),
            maxBlocks = args.int("max-blocks", 200),
          ).toString(2),
        )
      }
      "screenshot" -> {
        val output = args.string("output", "captures/desktop-${System.currentTimeMillis()}.png")
        val file = workspaceOutputFile(output)
        workspace.createAutomaticSnapshot("android_ui_screenshot")
        val result = desktopAutomation.screenshot(file)
          .put("output", workspace.workspaceRelativePath(file))
        ok(result.toString(2))
      }
      "wait" -> {
        desktopFeedback("正在操作手机", "等待：${args.string("text").ifBlank { args.string("package").ifBlank { "界面变化" } }}", ongoing = true)
        val result = desktopAutomation.waitFor(
          text = args.string("text"),
          packageName = args.string("package"),
          ocrText = args.string("ocr-text"),
          timeoutMs = args.long("timeout-ms", 10_000L).coerceIn(250L, 60_000L),
        )
        ok(result.toString(2))
      }
      "task" -> desktopTask(args)
      "launch" -> desktopAction(args, "launch") {
        val packageName = args.required("package")
        val activityName = args.string("activity")
        desktopFeedback("正在操作手机", "打开应用：${activityName.ifBlank { packageName }}", ongoing = true)
        require(desktopAutomation.launch(packageName, activityName)) {
          "app has no launchable activity or launch was rejected: $packageName"
        }
        JSONObject()
          .put("launched", true)
          .put("package", packageName)
          .put("activity", activityName)
      }
      "click" -> desktopAction(args, "click") {
        desktopFeedback("正在操作手机", "点击：${desktopSelectorLabel(args)}", ongoing = true)
        val clicked = desktopAutomation.click(
          nodeId = args.string("node-id"),
          text = args.string("text"),
          description = args.string("description"),
          resourceId = args.string("resource-id"),
          ocrText = args.string("ocr-text"),
        )
        require(clicked.optBoolean("completed")) { "matching node was not found or could not be clicked" }
        clicked.put("clicked", true)
      }
      "set-text", "input" -> desktopAction(args, "set-text") {
        desktopFeedback("正在操作手机", "输入：${args.required("value").take(24)}", ongoing = true)
        val changed = desktopAutomation.setText(
          nodeId = args.string("node-id"),
          text = args.string("text"),
          description = args.string("description"),
          resourceId = args.string("resource-id"),
          value = args.required("value"),
        )
        require(changed) { "matching editable node was not found or rejected text input" }
        JSONObject().put("textSet", true)
      }
      "tap" -> desktopAction(args, "tap") {
        desktopFeedback("正在操作手机", "点击坐标：${args.requiredInt("x")},${args.requiredInt("y")}", ongoing = true)
        val completed = desktopAutomation.tap(
          x = args.requiredInt("x"),
          y = args.requiredInt("y"),
          timeoutMs = args.long("gesture-timeout-ms", 3_000L),
        )
        require(completed) { "tap gesture was rejected or cancelled" }
        JSONObject().put("tapped", true)
      }
      "swipe" -> desktopAction(args, "swipe") {
        val untilText = args.string("until-text")
        desktopFeedback("正在操作手机", if (untilText.isBlank()) "滑动" else "滑动：查找 $untilText", ongoing = true)
        if (untilText.isNotBlank()) {
          val result = desktopAutomation.swipeUntilText(
            text = untilText,
            startX = args.requiredIntAny("start-x", "from-x"),
            startY = args.requiredIntAny("start-y", "from-y"),
            endX = args.requiredIntAny("end-x", "to-x"),
            endY = args.requiredIntAny("end-y", "to-y"),
            durationMs = args.long("duration-ms", 500L),
            timeoutMs = args.long("gesture-timeout-ms", 5_000L),
            maxSwipes = args.int("max-swipes", 5),
          )
          require(result.optBoolean("matched")) { "target text was not observed after swiping: $untilText" }
          result
        } else {
          val completed = desktopAutomation.swipe(
            startX = args.requiredIntAny("start-x", "from-x"),
            startY = args.requiredIntAny("start-y", "from-y"),
            endX = args.requiredIntAny("end-x", "to-x"),
            endY = args.requiredIntAny("end-y", "to-y"),
            durationMs = args.long("duration-ms", 500L),
            timeoutMs = args.long("gesture-timeout-ms", 5_000L),
          )
          require(completed) { "swipe gesture was rejected or cancelled" }
          JSONObject().put("swiped", true)
        }
      }
      "global" -> desktopAction(args, "global") {
        val action = args.required("action")
        desktopFeedback("正在操作手机", "系统操作：$action", ongoing = true)
        require(desktopAutomation.global(action)) {
          "global action was rejected: $action"
        }
        JSONObject().put("globalAction", action)
      }
      else -> fail(
        "supported: android ui status|open-settings|inspect|ocr|screenshot|wait|task|launch|click|set-text|tap|swipe|global",
      )
    }
  }

  private fun desktopTask(args: AndroidCommandArgs): AndroidSystemCommandResult {
    val task = when (args.positional(1).lowercase().ifBlank { "status" }) {
      "status" -> DesktopAutomationStore.load(appContext)
      "start" -> DesktopAutomationStore.start(appContext, args.required("goal")).also {
        desktopFeedback("正在操作手机", "任务开始：${it.goal.take(36)}", ongoing = true)
      }
      "intervention", "pause" -> {
        val reason = args.required("reason")
        notifyDesktopIntervention(reason)
        DesktopAutomationStore.intervention(appContext, reason)
      }
      "resume" -> DesktopAutomationStore.resume(appContext).also {
        desktopFeedback("正在操作手机", "继续任务：先重新识别当前界面", ongoing = true)
      }
      "complete" -> DesktopAutomationStore.finish(appContext, "completed", args.string("summary")).also {
        desktopFeedback("手机操作已完成", args.string("summary").ifBlank { "任务完成" }, ongoing = false)
      }
      "cancel" -> DesktopAutomationStore.finish(appContext, "cancelled", args.string("summary")).also {
        desktopFeedback("手机操作已取消", args.string("summary").ifBlank { "任务取消" }, ongoing = false)
      }
      else -> return fail("supported: android ui task status|start|intervention|resume|complete|cancel")
    }
    return ok(
      JSONObject()
        .put("task", task.toJson())
        .put("feedback", lastDesktopFeedback)
        .toString(2),
    )
  }

  private fun desktopAction(
    args: AndroidCommandArgs,
    action: String,
    operation: () -> JSONObject,
  ): AndroidSystemCommandResult {
    val task = DesktopAutomationStore.load(appContext)
    require(task.status == "active") { "start or resume a desktop task before executing UI actions" }
    val actionId = args.required("action-id")
    if (DesktopAutomationStore.alreadyConfirmed(appContext, actionId)) {
      return ok(
        JSONObject()
          .put("alreadyConfirmed", true)
          .put("actionId", actionId)
          .put("task", DesktopAutomationStore.load(appContext).toJson())
          .toString(2),
      )
    }

    return try {
      val before = desktopAutomation.inspect(300)
      require(!before.optBoolean("keyguardLocked")) {
        "device is locked; unlock it before resuming the desktop task"
      }
      val operationResult = operation()
      val timeoutMs = args.long("verify-timeout-ms", 8_000L).coerceIn(250L, 60_000L)
      val expectedText = args.string("expect-text")
      val expectedOcrText = args.string("expect-ocr-text")
      val expectedPackage = args.string("expect-package")
      val verification = if (operationResult.optBoolean("matched")) {
        operationResult
      } else if (expectedText.isNotBlank() || expectedOcrText.isNotBlank() || expectedPackage.isNotBlank()) {
        desktopAutomation.waitFor(expectedText, expectedPackage, timeoutMs, expectedOcrText)
      } else {
        desktopAutomation.waitForChange(before.optString("screenDigest"), timeoutMs)
      }
      require(verification.optBoolean("matched")) {
        "UI action executed but its expected result was not observed"
      }
      desktopFeedback("正在操作手机", "$action 已验证", ongoing = true)
      val resultSummary = verification.toString()
      val updatedTask = DesktopAutomationStore.actionConfirmed(
        context = appContext,
        actionId = actionId,
        action = action,
        result = resultSummary,
      )
      ok(
        JSONObject()
          .put("actionId", actionId)
          .put("action", action)
          .put("operation", operationResult)
          .put("verification", verification)
          .put("feedback", lastDesktopFeedback)
          .put("task", updatedTask.toJson())
          .toString(2),
      )
    } catch (error: Throwable) {
      DesktopAutomationStore.intervention(
        appContext,
        "Desktop action $action ($actionId) needs review: ${error.message.orEmpty()}",
      )
      AndroidOverlayController.hide(appContext, DESKTOP_FEEDBACK_OVERLAY_ID)
      notifyDesktopIntervention(
        "Action $action needs review before Flovera can continue: ${error.message.orEmpty()}",
      )
      throw error
    }
  }

  private fun notifyDesktopIntervention(reason: String) {
    AgentRunNotifications.postNormal(
      context = appContext,
      title = "Flovera desktop task needs attention",
      body = reason.take(240),
      ongoing = false,
      priority = NotificationCompat.PRIORITY_DEFAULT,
    )
  }

  private fun desktopFeedback(title: String, detail: String, ongoing: Boolean): JSONObject {
    val body = detail.ifBlank { "Flovera is operating the phone." }.take(240)
    var overlayShown = false
    var notificationPosted = false
    if (Settings.canDrawOverlays(appContext)) {
      runCatching {
        AndroidOverlayController.show(
          appContext,
          DESKTOP_FEEDBACK_OVERLAY_ID,
          "$title\n$body",
          if (ongoing) DESKTOP_FEEDBACK_ONGOING_MS else DESKTOP_FEEDBACK_DONE_MS,
        )
      }.onSuccess { overlayShown = true }
    }
    if (permissionState("notifications") == "granted") {
      runCatching {
        ensureNotificationChannel()
        if (!ongoing) ensureDesktopCompletionChannel()
        val notification = NotificationCompat.Builder(appContext, if (ongoing) SYSTEM_CHANNEL_ID else DESKTOP_COMPLETION_CHANNEL_ID)
          .setSmallIcon(R.mipmap.ic_launcher)
          .setContentTitle(title)
          .setContentText(body)
          .setStyle(NotificationCompat.BigTextStyle().bigText(body))
          .setOngoing(ongoing)
          .setAutoCancel(!ongoing)
          .setPriority(if (ongoing) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
          .setDefaults(if (ongoing) 0 else NotificationCompat.DEFAULT_VIBRATE)
          .setVibrate(if (ongoing) null else DESKTOP_COMPLETION_VIBRATION)
          .build()
        NotificationManagerCompat.from(appContext).notify(DESKTOP_FEEDBACK_NOTIFICATION_ID, notification)
      }.onSuccess { notificationPosted = true }
    }
    if (!ongoing) vibrateDesktopCompletion()
    return JSONObject()
      .put("title", title)
      .put("detail", body)
      .put("ongoing", ongoing)
      .put("overlayShown", overlayShown)
      .put("notificationPosted", notificationPosted)
      .put("durationMs", if (ongoing) DESKTOP_FEEDBACK_ONGOING_MS else DESKTOP_FEEDBACK_DONE_MS)
      .also { lastDesktopFeedback = it }
  }

  private fun desktopSelectorLabel(args: AndroidCommandArgs): String {
    return args.string("text")
      .ifBlank { args.string("description") }
      .ifBlank { args.string("resource-id") }
      .ifBlank { args.string("ocr-text") }
      .ifBlank { args.string("node-id") }
      .ifBlank { "目标控件" }
      .take(40)
  }

  private fun desktopDiagnosis(service: JSONObject): JSONObject {
    val permission = permissionState("accessibility")
    return JSONObject()
      .put("accessibilityPermission", permission)
      .put("connected", service.optBoolean("connected"))
      .put("keyguardLocked", service.optBoolean("keyguardLocked"))
      .put("overlayPermission", permissionState("overlay"))
      .put("notificationPermission", permissionState("notifications"))
      .put(
        "recommendation",
        when {
          permission != "granted" -> "Open Flovera Permissions and enable Desktop operation accessibility. Android does not allow Flovera to re-enable it silently."
          !service.optBoolean("connected") -> "Flovera Accessibility is enabled but not connected yet. Reopen the Accessibility page or wait for Android to bind the service."
          service.optBoolean("keyguardLocked") -> "Unlock the device before continuing the desktop task."
          else -> "ready"
        },
      )
  }

  private fun camera(args: AndroidCommandArgs): AndroidSystemCommandResult {
    requirePermission("camera")
    if (args.command() != "capture") return fail("supported: android camera capture --output <workspace.jpg> [--lens back|front]")
    val output = args.string("output", "captures/camera-${System.currentTimeMillis()}.jpg")
    val file = workspaceOutputFile(output)
    workspace.createAutomaticSnapshot("android_camera_capture")
    capturePhoto(file, args.string("lens", "back"))
    return ok(fileResult(file, "image/jpeg").toString(2))
  }

  private fun microphone(args: AndroidCommandArgs): AndroidSystemCommandResult {
    requirePermission("microphone")
    if (args.command() != "record") return fail("supported: android microphone record --output <workspace.m4a> [--duration-ms 3000]")
    val durationMs = args.long("duration-ms", 3_000L).coerceIn(250L, 60_000L)
    val file = workspaceOutputFile(args.string("output", "recordings/audio-${System.currentTimeMillis()}.m4a"))
    workspace.createAutomaticSnapshot("android_microphone_record")
    recordAudio(file, durationMs)
    return ok(fileResult(file, "audio/mp4").put("durationMs", durationMs).toString(2))
  }

  private fun location(args: AndroidCommandArgs): AndroidSystemCommandResult {
    requireAnyPermission("fine_location", "coarse_location")
    if (args.command("current") != "current") return fail("supported: android location current [--timeout-ms 15000]")
    val timeoutMs = args.long("timeout-ms", 15_000L).coerceIn(1_000L, 60_000L)
    val value = currentLocation(timeoutMs)
    return ok(
      JSONObject()
        .put("latitude", value.location.latitude)
        .put("longitude", value.location.longitude)
        .put("accuracyMeters", value.location.accuracy.toDouble())
        .put("altitudeMeters", value.location.altitude)
        .put("provider", value.location.provider)
        .put("time", value.location.time)
        .put("ageMs", value.ageMs)
        .put("source", value.source)
        .put("enabledProviders", JSONArray(value.enabledProviders))
        .toString(2),
    )
  }

  private fun contacts(args: AndroidCommandArgs): AndroidSystemCommandResult {
    return when (args.command("list")) {
      "list", "search" -> {
        requirePermission("contacts_read")
        ok(listContacts(args.string("query"), args.int("limit", 50).coerceIn(1, 200)).toString(2))
      }
      "create" -> {
        requirePermission("contacts_write")
        ok(createContact(args.required("name"), args.string("phone"), args.string("email")).toString(2))
      }
      "delete" -> {
        requirePermission("contacts_write")
        val id = args.requiredLong("id")
        val count = appContext.contentResolver.delete(
          ContactsContract.RawContacts.CONTENT_URI,
          "${ContactsContract.RawContacts.CONTACT_ID}=?",
          arrayOf(id.toString()),
        )
        ok(JSONObject().put("deleted", count).put("contactId", id).toString())
      }
      else -> fail("supported: android contacts list|search|create|delete")
    }
  }

  private fun calendar(args: AndroidCommandArgs): AndroidSystemCommandResult {
    return when (args.command("calendars")) {
      "calendars" -> {
        requirePermission("calendar_read")
        ok(listCalendars(args.int("limit", 50).coerceIn(1, 200)).toString(2))
      }
      "events" -> {
        requirePermission("calendar_read")
        val from = args.long("from-ms", System.currentTimeMillis() - 7L * DAY_MS)
        val to = args.long("to-ms", System.currentTimeMillis() + 30L * DAY_MS)
        ok(listCalendarEvents(from, to, args.int("limit", 100).coerceIn(1, 500)).toString(2))
      }
      "create" -> {
        requirePermission("calendar_write")
        ok(createCalendarEvent(args).toString(2))
      }
      "delete" -> {
        requirePermission("calendar_write")
        val id = args.requiredLong("id")
        val count = appContext.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
        ok(JSONObject().put("deleted", count).put("eventId", id).toString())
      }
      else -> fail("supported: android calendar calendars|events|create|delete")
    }
  }

  private fun media(args: AndroidCommandArgs): AndroidSystemCommandResult {
    val type = args.string("type", "images").lowercase()
    if (type == "images" || type == "image" || type == "video" || type == "videos") {
      requireAnyPermission(mediaPermission(type), "media_visual_selected")
    } else {
      requirePermission(mediaPermission(type))
    }
    return when (args.command("list")) {
      "list" -> ok(listMedia(type, args.int("limit", 100).coerceIn(1, 500)).toString(2))
      "import" -> {
        val id = args.requiredLong("id")
        val output = args.required("output")
        val uri = ContentUris.withAppendedId(mediaCollection(type), id)
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
          input.readBytesBounded(MAX_IMPORTED_MEDIA_BYTES + 1)
        } ?: error("media item cannot be opened: $uri")
        require(bytes.size <= MAX_IMPORTED_MEDIA_BYTES) { "media item exceeds ${MAX_IMPORTED_MEDIA_BYTES / 1024 / 1024} MB import limit" }
        workspace.createAutomaticSnapshot("android_media_import")
        workspace.writeBytes(output, bytes, overwrite = true, createAutoSnapshot = false)
        ok(JSONObject().put("uri", uri.toString()).put("output", output).put("bytes", bytes.size).toString(2))
      }
      else -> fail("supported: android media list|import --type images|video|audio")
    }
  }

  private fun bluetooth(args: AndroidCommandArgs): AndroidSystemCommandResult {
    val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
      ?: return fail("Bluetooth adapter is unavailable")
    return when (args.command("paired")) {
      "paired" -> {
        requirePermission("bluetooth_connect")
        val devices = JSONArray()
        adapter.bondedDevices.sortedBy { it.address }.forEach { device ->
          devices.put(bluetoothDeviceJson(device))
        }
        ok(JSONObject().put("enabled", adapter.isEnabled).put("devices", devices).toString(2))
      }
      "scan" -> {
        requirePermission("bluetooth_scan")
        requirePermission("bluetooth_connect")
        val durationMs = args.long("duration-ms", 8_000L).coerceIn(1_000L, 30_000L)
        ok(scanBluetooth(adapter, durationMs).toString(2))
      }
      else -> fail("supported: android bluetooth paired | scan [--duration-ms 8000]")
    }
  }

  private fun overlay(args: AndroidCommandArgs): AndroidSystemCommandResult {
    requirePermission("overlay")
    return when (args.command()) {
      "show" -> {
        val id = args.string("id", "agent")
        val durationMs = args.long("duration-ms", 5_000L).coerceIn(500L, 300_000L)
        AndroidOverlayController.show(appContext, id, args.required("text"), durationMs)
        ok(JSONObject().put("shown", true).put("id", id).put("durationMs", durationMs).toString())
      }
      "hide" -> {
        val id = args.string("id", "agent")
        AndroidOverlayController.hide(appContext, id)
        ok(JSONObject().put("hidden", true).put("id", id).toString())
      }
      else -> fail("supported: android overlay show --text <text> [--duration-ms] | hide")
    }
  }

  private fun storage(args: AndroidCommandArgs): AndroidSystemCommandResult {
    requirePermission("all_files")
    return when (args.command("list")) {
      "list" -> ok(listExternalFiles(args.string("path", "."), args.int("limit", 100).coerceIn(1, 500)).toString(2))
      "import" -> {
        val source = externalFile(args.required("path"))
        require(source.isFile) { "external file does not exist: ${source.path}" }
        require(source.length() <= MAX_IMPORTED_MEDIA_BYTES) { "external file exceeds ${MAX_IMPORTED_MEDIA_BYTES / 1024 / 1024} MB import limit" }
        val output = args.required("output")
        workspace.createAutomaticSnapshot("android_storage_import")
        workspace.writeBytes(output, source.readBytes(), overwrite = true, createAutoSnapshot = false)
        ok(JSONObject().put("source", source.path).put("output", output).put("bytes", source.length()).toString(2))
      }
      else -> fail("supported: android storage list --path <external-relative> | import --path <external-relative> --output <workspace>")
    }
  }

  private fun packageInstall(args: AndroidCommandArgs): AndroidSystemCommandResult {
    if (args.command() != "install") return fail("supported: android package install --path <workspace.apk>")
    requirePermission("install_unknown_apps")
    val path = args.required("path")
    val file = workspace.exportableFile(path) ?: error("workspace APK does not exist: $path")
    require(file.extension.equals("apk", ignoreCase = true)) { "package install requires an .apk file" }
    val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.workspacefiles", file)
    val intent = Intent(Intent.ACTION_VIEW)
      .setDataAndType(uri, "application/vnd.android.package-archive")
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    appContext.startActivity(intent)
    return ok(JSONObject().put("openedInstaller", true).put("path", path).toString())
  }

  private fun alarm(args: AndroidCommandArgs): AndroidSystemCommandResult {
    requirePermission("exact_alarm")
    val manager = appContext.getSystemService(AlarmManager::class.java)
    return when (args.command()) {
      "schedule" -> {
        requirePermission("notifications")
        val id = args.int("id", DEFAULT_ALARM_ID)
        val atMs = args.requiredLong("at-ms")
        require(atMs > System.currentTimeMillis()) { "at-ms must be in the future" }
        val pending = AndroidSystemAlarmReceiver.pendingIntent(
          appContext,
          id,
          args.required("title"),
          args.required("body"),
        )
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
        ok(JSONObject().put("scheduled", true).put("id", id).put("atMs", atMs).put("at", Instant.ofEpochMilli(atMs).toString()).toString(2))
      }
      "cancel" -> {
        val id = args.int("id", DEFAULT_ALARM_ID)
        manager.cancel(AndroidSystemAlarmReceiver.pendingIntent(appContext, id, "", ""))
        ok(JSONObject().put("cancelled", true).put("id", id).toString())
      }
      else -> fail("supported: android alarm schedule --at-ms <epoch> --title <text> --body <text> [--id] | cancel")
    }
  }

  private fun network(args: AndroidCommandArgs): AndroidSystemCommandResult {
    requirePermission("internet")
    require(networkEnabled) { "Flovera Network setting is disabled" }
    if (args.command() != "get") return fail("supported: android network get --url <https://...> [--max-chars 20000]")
    val url = URL(args.required("url"))
    require(url.protocol == "https" || url.protocol == "http") { "only http/https URLs are supported" }
    val maxChars = args.int("max-chars", 20_000).coerceIn(1_000, 200_000)
    val connection = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 15_000
      readTimeout = 30_000
      instanceFollowRedirects = true
    }
    return connection.useConnection {
      val code = responseCode
      val stream = if (code >= 400) errorStream else inputStream
      val body = stream?.bufferedReader()?.use { it.readText().take(maxChars) }.orEmpty()
      ok(JSONObject().put("statusCode", code).put("contentType", contentType.orEmpty()).put("body", body).toString(2))
    }
  }

  private fun foreground(args: AndroidCommandArgs): AndroidSystemCommandResult {
    requirePermission("foreground_service")
    return when (args.command("status")) {
      "start" -> {
        ContextCompat.startForegroundService(appContext, AgentRunForegroundService.keepAliveIntent(appContext))
        ok(JSONObject().put("started", true).toString())
      }
      "stop" -> {
        appContext.startService(AgentRunForegroundService.stopKeepAliveIntent(appContext))
        ok(JSONObject().put("stopped", true).toString())
      }
      "status" -> ok(
        JSONObject()
          .put("running", AgentRunForegroundService.isServiceRunning())
          .put("mode", AgentRunForegroundService.currentMode())
          .toString(2),
      )
      else -> fail("supported: android foreground start|stop|status")
    }
  }

  private fun intent(args: AndroidCommandArgs): AndroidSystemCommandResult {
    return when (args.command()) {
      "open" -> {
        val id = args.positional(1).ifBlank { "app_details" }
        ok(JSONObject().put("opened", AndroidPermissionCapabilities.openPermission(appContext, id)).put("intent", id).toString())
      }
      "open-url" -> {
        val uri = Uri.parse(args.required("url"))
        require(uri.scheme in listOf("http", "https", "geo", "market")) { "unsupported URL scheme" }
        appContext.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ok(JSONObject().put("opened", true).put("uri", uri.toString()).toString())
      }
      "share" -> {
        val intent = Intent(Intent.ACTION_SEND)
          .setType("text/plain")
          .putExtra(Intent.EXTRA_TEXT, args.required("text"))
          .putExtra(Intent.EXTRA_TITLE, args.string("title", "Flovera"))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(Intent.createChooser(intent, args.string("title", "Share")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ok(JSONObject().put("opened", true).put("type", "share").toString())
      }
      "dial" -> {
        val number = args.required("number").filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        appContext.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ok(JSONObject().put("opened", true).put("type", "dial").toString())
      }
      else -> fail("supported: android intent open|open-url|share|dial")
    }
  }

  private fun capabilities(): String {
    return JSONObject()
      .put("profiles", JSONArray(PROFILES))
      .put(
        "commands",
        JSONArray(
          listOf(
            "permission status|open",
            "notification post|cancel",
            "camera capture",
            "microphone record",
            "location current",
            "contacts list|search|create|delete",
            "calendar calendars|events|create|delete",
            "media list|import",
            "bluetooth paired|scan",
            "overlay show|hide",
            "storage list|import",
            "package install",
            "alarm schedule|cancel",
            "network get",
            "foreground start|stop|status",
            "intent open|open-url|share|dial",
            "ui status|open-settings|inspect|ocr|screenshot|wait|task|launch|click|set-text|tap|swipe|global",
          ),
        ),
      )
      .toString(2)
  }

  private fun listContacts(query: String, limit: Int): JSONObject {
    val result = JSONArray()
    val selection = if (query.isBlank()) null else {
      "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
    }
    val selectionArgs = if (query.isBlank()) null else arrayOf("%$query%", "%$query%")
    appContext.contentResolver.query(
      ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
      arrayOf(
        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
        ContactsContract.CommonDataKinds.Phone.TYPE,
      ),
      selection,
      selectionArgs,
      "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC",
    )?.use { cursor ->
      while (cursor.moveToNext() && result.length() < limit) {
        result.put(
          JSONObject()
            .put("id", cursor.long(0))
            .put("name", cursor.string(1))
            .put("phone", cursor.string(2))
            .put("phoneType", cursor.int(3)),
        )
      }
    }
    return JSONObject().put("contacts", result).put("count", result.length())
  }

  private fun createContact(name: String, phone: String, email: String): JSONObject {
    val resolver = appContext.contentResolver
    val rawUri = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, ContentValues())
      ?: error("failed to create raw contact")
    val rawId = ContentUris.parseId(rawUri)
    resolver.insert(
      ContactsContract.Data.CONTENT_URI,
      ContentValues().apply {
        put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
        put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
        put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
      },
    )
    if (phone.isNotBlank()) {
      resolver.insert(
        ContactsContract.Data.CONTENT_URI,
        ContentValues().apply {
          put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
          put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
          put(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
          put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
        },
      )
    }
    if (email.isNotBlank()) {
      resolver.insert(
        ContactsContract.Data.CONTENT_URI,
        ContentValues().apply {
          put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
          put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
          put(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
          put(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
        },
      )
    }
    val contactId = resolver.query(
      ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
      arrayOf(ContactsContract.RawContacts.CONTACT_ID),
      null,
      null,
      null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.long(0) else 0L } ?: 0L
    return JSONObject().put("created", true).put("rawContactId", rawId).put("contactId", contactId)
  }

  private fun listCalendars(limit: Int): JSONObject {
    val result = JSONArray()
    appContext.contentResolver.query(
      CalendarContract.Calendars.CONTENT_URI,
      arrayOf(
        CalendarContract.Calendars._ID,
        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        CalendarContract.Calendars.ACCOUNT_NAME,
        CalendarContract.Calendars.VISIBLE,
        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
      ),
      null,
      null,
      "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
    )?.use { cursor ->
      while (cursor.moveToNext() && result.length() < limit) {
        result.put(
          JSONObject()
            .put("id", cursor.long(0))
            .put("name", cursor.string(1))
            .put("account", cursor.string(2))
            .put("visible", cursor.int(3) != 0)
            .put("accessLevel", cursor.int(4)),
        )
      }
    }
    return JSONObject().put("calendars", result).put("count", result.length())
  }

  private fun listCalendarEvents(from: Long, to: Long, limit: Int): JSONObject {
    val result = JSONArray()
    val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
    ContentUris.appendId(builder, from)
    ContentUris.appendId(builder, to)
    appContext.contentResolver.query(
      builder.build(),
      arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.CALENDAR_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.EVENT_LOCATION,
      ),
      null,
      null,
      "${CalendarContract.Instances.BEGIN} ASC",
    )?.use { cursor ->
      while (cursor.moveToNext() && result.length() < limit) {
        result.put(
          JSONObject()
            .put("id", cursor.long(0))
            .put("calendarId", cursor.long(1))
            .put("title", cursor.string(2))
            .put("startMs", cursor.long(3))
            .put("endMs", cursor.long(4))
            .put("location", cursor.string(5)),
        )
      }
    }
    return JSONObject().put("events", result).put("count", result.length()).put("fromMs", from).put("toMs", to)
  }

  private fun createCalendarEvent(args: AndroidCommandArgs): JSONObject {
    val calendarId = args.requiredLong("calendar-id")
    val start = args.requiredLong("start-ms")
    val end = args.long("end-ms", start + 60L * 60L * 1000L)
    require(end > start) { "end-ms must be greater than start-ms" }
    val uri = appContext.contentResolver.insert(
      CalendarContract.Events.CONTENT_URI,
      ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, args.required("title"))
        put(CalendarContract.Events.DESCRIPTION, args.string("description"))
        put(CalendarContract.Events.EVENT_LOCATION, args.string("location"))
        put(CalendarContract.Events.DTSTART, start)
        put(CalendarContract.Events.DTEND, end)
        put(CalendarContract.Events.EVENT_TIMEZONE, args.string("timezone", TimeZone.getDefault().id))
      },
    ) ?: error("failed to create calendar event")
    return JSONObject().put("created", true).put("eventId", ContentUris.parseId(uri)).put("uri", uri.toString())
  }

  private fun listMedia(type: String, limit: Int): JSONObject {
    val result = JSONArray()
    appContext.contentResolver.query(
      mediaCollection(type),
      arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
      ),
      null,
      null,
      "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
    )?.use { cursor ->
      while (cursor.moveToNext() && result.length() < limit) {
        val id = cursor.long(0)
        result.put(
          JSONObject()
            .put("id", id)
            .put("name", cursor.string(1))
            .put("mimeType", cursor.string(2))
            .put("size", cursor.long(3))
            .put("dateModified", cursor.long(4))
            .put("uri", ContentUris.withAppendedId(mediaCollection(type), id).toString()),
        )
      }
    }
    return JSONObject().put("type", type).put("items", result).put("count", result.length())
  }

  private fun mediaCollection(type: String): Uri {
    return when (type) {
      "images", "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
      "video", "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
      "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
      else -> error("unsupported media type: $type")
    }
  }

  private fun mediaPermission(type: String): String {
    return when (type) {
      "images", "image" -> "media_images"
      "video", "videos" -> "media_video"
      "audio" -> "media_audio"
      else -> error("unsupported media type: $type")
    }
  }

  private fun currentLocation(timeoutMs: Long): AndroidLocationSnapshot {
    val manager = appContext.getSystemService(LocationManager::class.java)
    require(manager.isLocationEnabled) { "Android location services are disabled" }
    val providers = listOf(
      LocationManager.FUSED_PROVIDER,
      LocationManager.NETWORK_PROVIDER,
      LocationManager.GPS_PROVIDER,
      LocationManager.PASSIVE_PROVIDER,
    ).distinct().filter { provider ->
      runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
    }
    require(providers.isNotEmpty()) { "no enabled location provider" }

    val initialLocations = providers.mapNotNull { provider ->
      runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }
    bestLocation(initialLocations, MAX_FRESH_LOCATION_AGE_MS)?.let { location ->
      return locationSnapshot(location, "last_known_fresh", providers)
    }

    val latch = CountDownLatch(1)
    val value = AtomicReference<Location?>()
    val remaining = AtomicInteger(providers.size)
    val executor = Executors.newSingleThreadExecutor()
    val cancellations = providers.map { CancellationSignal() }
    try {
      providers.forEachIndexed { index, provider ->
        runCatching {
          manager.getCurrentLocation(provider, cancellations[index], executor) { location ->
            if (location != null && value.compareAndSet(null, location)) {
              latch.countDown()
            } else if (remaining.decrementAndGet() == 0) {
              latch.countDown()
            }
          }
        }.onFailure {
          if (remaining.decrementAndGet() == 0) latch.countDown()
        }
      }
      if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        cancellations.forEach(CancellationSignal::cancel)
      }
      value.get()?.let { return locationSnapshot(it, "current", providers) }
      val fallbackLocations = providers.mapNotNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
      }
      val fallback = bestLocation(fallbackLocations, maxAgeMs = null)
        ?: error("location unavailable after ${timeoutMs}ms; enabled providers=${providers.joinToString()}")
      return locationSnapshot(fallback, "last_known_fallback", providers)
    } finally {
      cancellations.forEach(CancellationSignal::cancel)
      executor.shutdownNow()
    }
  }

  private fun bestLocation(locations: List<Location>, maxAgeMs: Long?): Location? {
    val now = System.currentTimeMillis()
    val candidates = if (maxAgeMs == null) {
      locations
    } else {
      locations.filter { location -> (now - location.time).coerceAtLeast(0L) <= maxAgeMs }
    }
    return candidates.minWithOrNull(
      compareBy<Location> { (now - it.time).coerceAtLeast(0L) / LOCATION_AGE_BUCKET_MS }
        .thenBy { it.accuracy }
        .thenByDescending { it.time },
    )
  }

  private fun locationSnapshot(
    location: Location,
    source: String,
    providers: List<String>,
  ): AndroidLocationSnapshot {
    return AndroidLocationSnapshot(
      location = location,
      source = source,
      ageMs = (System.currentTimeMillis() - location.time).coerceAtLeast(0L),
      enabledProviders = providers,
    )
  }

  private fun capturePhoto(output: File, lens: String) {
    val manager = appContext.getSystemService(CameraManager::class.java)
    val facing = if (lens.lowercase() == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
    val cameraId = manager.cameraIdList.firstOrNull { id ->
      manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
    } ?: manager.cameraIdList.firstOrNull() ?: error("no camera is available")
    val characteristics = manager.getCameraCharacteristics(cameraId)
    val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
      ?.getOutputSizes(ImageFormat.JPEG)
      ?.sortedByDescending { it.width.toLong() * it.height.toLong() }
      .orEmpty()
    val size = sizes.firstOrNull { it.width <= 1920 && it.height <= 1920 } ?: sizes.lastOrNull() ?: error("camera has no JPEG output size")
    output.parentFile?.mkdirs()
    val thread = HandlerThread("flovera-camera").also { it.start() }
    val handler = Handler(thread.looper)
    val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
    val cameraRef = AtomicReference<CameraDevice?>()
    val sessionRef = AtomicReference<CameraCaptureSession?>()
    val errorRef = AtomicReference<Throwable?>()
    val done = CountDownLatch(1)
    imageReader.setOnImageAvailableListener({ reader ->
      runCatching {
        reader.acquireLatestImage()?.use { image ->
          val buffer = image.planes[0].buffer
          val bytes = ByteArray(buffer.remaining())
          buffer.get(bytes)
          output.writeBytes(bytes)
        } ?: error("camera returned no image")
      }.onFailure(errorRef::set)
      done.countDown()
    }, handler)
    manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
      override fun onOpened(camera: CameraDevice) {
        cameraRef.set(camera)
        camera.createCaptureSession(
          listOf(imageReader.surface),
          object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
              sessionRef.set(session)
              runCatching {
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                  addTarget(imageReader.surface)
                  set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                }.build()
                session.capture(request, null, handler)
              }.onFailure {
                errorRef.set(it)
                done.countDown()
              }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
              errorRef.set(IllegalStateException("camera session configuration failed"))
              done.countDown()
            }
          },
          handler,
        )
      }

      override fun onDisconnected(camera: CameraDevice) {
        errorRef.set(IllegalStateException("camera disconnected"))
        done.countDown()
      }

      override fun onError(camera: CameraDevice, error: Int) {
        errorRef.set(IllegalStateException("camera error=$error"))
        done.countDown()
      }
    }, handler)
    try {
      if (!done.await(CAMERA_TIMEOUT_MS, TimeUnit.MILLISECONDS)) error("camera capture timed out")
      errorRef.get()?.let { throw it }
      require(output.isFile && output.length() > 0) { "camera produced an empty file" }
    } finally {
      sessionRef.get()?.close()
      cameraRef.get()?.close()
      imageReader.close()
      thread.quitSafely()
    }
  }

  private fun recordAudio(output: File, durationMs: Long) {
    output.parentFile?.mkdirs()
    val recorder = MediaRecorder(appContext)
    try {
      recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
      recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
      recorder.setAudioEncodingBitRate(128_000)
      recorder.setAudioSamplingRate(44_100)
      recorder.setOutputFile(output.absolutePath)
      recorder.prepare()
      recorder.start()
      Thread.sleep(durationMs)
      recorder.stop()
    } finally {
      recorder.release()
    }
    require(output.isFile && output.length() > 0) { "microphone produced an empty file" }
  }

  private fun scanBluetooth(adapter: BluetoothAdapter, durationMs: Long): JSONObject {
    require(adapter.isEnabled) { "Bluetooth is disabled" }
    val devices = linkedMapOf<String, JSONObject>()
    val latch = CountDownLatch(1)
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
          BluetoothDevice.ACTION_FOUND -> {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
              @Suppress("DEPRECATION")
              intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            if (device != null) devices[device.address] = bluetoothDeviceJson(device)
          }
          BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> latch.countDown()
        }
      }
    }
    val filter = IntentFilter().apply {
      addAction(BluetoothDevice.ACTION_FOUND)
      addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
    }
    ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    try {
      adapter.cancelDiscovery()
      require(adapter.startDiscovery()) { "Bluetooth discovery could not start" }
      latch.await(durationMs, TimeUnit.MILLISECONDS)
    } finally {
      adapter.cancelDiscovery()
      runCatching { appContext.unregisterReceiver(receiver) }
    }
    return JSONObject().put("durationMs", durationMs).put("devices", JSONArray(devices.values)).put("count", devices.size)
  }

  private fun bluetoothDeviceJson(device: BluetoothDevice): JSONObject {
    return JSONObject()
      .put("name", runCatching { device.name }.getOrNull().orEmpty())
      .put("address", device.address)
      .put("bondState", device.bondState)
      .put("type", device.type)
  }

  private fun listExternalFiles(path: String, limit: Int): JSONObject {
    val target = externalFile(path)
    require(target.exists()) { "external path does not exist: ${target.path}" }
    val items = JSONArray()
    if (target.isFile) {
      items.put(externalFileJson(target))
    } else {
      target.listFiles()?.sortedBy { it.name.lowercase() }?.take(limit)?.forEach { items.put(externalFileJson(it)) }
    }
    return JSONObject().put("path", target.path).put("items", items).put("count", items.length())
  }

  private fun externalFile(path: String): File {
    val root = Environment.getExternalStorageDirectory().canonicalFile
    val requested = if (File(path).isAbsolute) File(path).canonicalFile else File(root, path).canonicalFile
    require(requested.path == root.path || requested.path.startsWith(root.path + File.separator)) {
      "external path escapes shared storage root"
    }
    return requested
  }

  private fun externalFileJson(file: File): JSONObject {
    return JSONObject()
      .put("name", file.name)
      .put("path", file.path)
      .put("directory", file.isDirectory)
      .put("size", if (file.isFile) file.length() else 0L)
      .put("modified", file.lastModified())
  }

  private fun workspaceOutputFile(path: String): File {
    require(path.isNotBlank()) { "output path must be non-empty" }
    val file = File(workspace.root, path).canonicalFile
    val root = workspace.root.canonicalFile
    require(file.path.startsWith(root.path + File.separator)) { "output path escapes workspace" }
    file.parentFile?.mkdirs()
    return file
  }

  private fun fileResult(file: File, mimeType: String): JSONObject {
    return JSONObject()
      .put("output", workspace.workspaceRelativePath(file))
      .put("bytes", file.length())
      .put("mimeType", mimeType)
  }

  private fun requirePermission(id: String) {
    val capability = AndroidPermissionCapabilities.capabilities.firstOrNull { it.id == id }
      ?: error("unknown Android permission capability: $id")
    val state = AndroidPermissionCapabilities.stateFor(appContext, capability)
    require(state == "granted") { "permission $id is $state; grant it from Flovera Permissions" }
  }

  private fun permissionState(id: String): String {
    val capability = AndroidPermissionCapabilities.capabilities.firstOrNull { it.id == id }
      ?: return "unknown"
    return AndroidPermissionCapabilities.stateFor(appContext, capability)
  }

  private fun requireAnyPermission(vararg ids: String) {
    val granted = ids.any { id ->
      AndroidPermissionCapabilities.capabilities.firstOrNull { it.id == id }
        ?.let { AndroidPermissionCapabilities.stateFor(appContext, it) == "granted" }
        ?: false
    }
    require(granted) { "one of ${ids.joinToString()} must be granted from Flovera Permissions" }
  }

  private fun ensureNotificationChannel() {
    val manager = appContext.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(SYSTEM_CHANNEL_ID, "Flovera system actions", NotificationManager.IMPORTANCE_DEFAULT),
    )
  }

  private fun ensureDesktopCompletionChannel() {
    val manager = appContext.getSystemService(NotificationManager::class.java)
    val channel = NotificationChannel(
      DESKTOP_COMPLETION_CHANNEL_ID,
      "Flovera desktop completion",
      NotificationManager.IMPORTANCE_HIGH,
    ).apply {
      enableVibration(true)
      vibrationPattern = DESKTOP_COMPLETION_VIBRATION
    }
    manager.createNotificationChannel(channel)
  }

  private fun vibrateDesktopCompletion() {
    runCatching {
      appContext.getSystemService(Vibrator::class.java)
        .vibrate(VibrationEffect.createWaveform(DESKTOP_COMPLETION_VIBRATION, -1))
    }
  }

  private fun ok(output: String): AndroidSystemCommandResult = AndroidSystemCommandResult("ok", output)

  private fun fail(message: String): AndroidSystemCommandResult = AndroidSystemCommandResult("error", "error=$message\n")

  private fun Cursor.string(index: Int): String = if (isNull(index)) "" else getString(index).orEmpty()
  private fun Cursor.long(index: Int): Long = if (isNull(index)) 0L else getLong(index)
  private fun Cursor.int(index: Int): Int = if (isNull(index)) 0 else getInt(index)

  private inline fun <T> HttpURLConnection.useConnection(block: HttpURLConnection.() -> T): T {
    return try {
      block()
    } finally {
      disconnect()
    }
  }

  private fun java.io.InputStream.readBytesBounded(maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (total < maxBytes) {
      val count = read(buffer, 0, minOf(buffer.size, maxBytes - total))
      if (count < 0) break
      output.write(buffer, 0, count)
      total += count
    }
    return output.toByteArray()
  }

  companion object {
    val PROFILES = listOf(
      "app",
      "permission",
      "notification",
      "camera",
      "microphone",
      "location",
      "contacts",
      "calendar",
      "media",
      "bluetooth",
      "overlay",
      "storage",
      "package",
      "alarm",
      "network",
      "foreground",
      "intent",
      "ui",
    )
    private const val SYSTEM_CHANNEL_ID = "flovera_system_actions"
    private const val DESKTOP_COMPLETION_CHANNEL_ID = "flovera_desktop_completion"
    private const val DEFAULT_NOTIFICATION_ID = 7201
    private const val DESKTOP_FEEDBACK_NOTIFICATION_ID = 7310
    private const val DESKTOP_FEEDBACK_OVERLAY_ID = "desktop-operation"
    private const val DESKTOP_FEEDBACK_ONGOING_MS = 300_000L
    private const val DESKTOP_FEEDBACK_DONE_MS = 8_000L
    private const val DEFAULT_ALARM_ID = 7301
    private val DESKTOP_COMPLETION_VIBRATION = longArrayOf(0, 120, 80, 180)
    private const val DAY_MS = 86_400_000L
    private const val MAX_IMPORTED_MEDIA_BYTES = 50 * 1024 * 1024
    private const val CAMERA_TIMEOUT_MS = 20_000L
    private const val MAX_FRESH_LOCATION_AGE_MS = 2 * 60_000L
    private const val LOCATION_AGE_BUCKET_MS = 15_000L
  }
}

private data class AndroidLocationSnapshot(
  val location: Location,
  val source: String,
  val ageMs: Long,
  val enabledProviders: List<String>,
)

class AndroidSystemAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.getIntExtra(EXTRA_ID, 7301)
    val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Flovera reminder" }
    val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Flovera reminders", NotificationManager.IMPORTANCE_DEFAULT),
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .build()
    NotificationManagerCompat.from(context).notify(id, notification)
  }

  companion object {
    private const val CHANNEL_ID = "flovera_system_alarms"
    private const val EXTRA_ID = "id"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_BODY = "body"

    fun pendingIntent(context: Context, id: Int, title: String, body: String): PendingIntent {
      val intent = Intent(context, AndroidSystemAlarmReceiver::class.java)
        .putExtra(EXTRA_ID, id)
        .putExtra(EXTRA_TITLE, title)
        .putExtra(EXTRA_BODY, body)
      return PendingIntent.getBroadcast(
        context,
        id,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }
  }
}

private object AndroidOverlayController {
  private val handler = Handler(android.os.Looper.getMainLooper())
  private val views = mutableMapOf<String, TextView>()

  fun show(context: Context, id: String, text: String, durationMs: Long) {
    handler.post {
      hideOnMain(context, id)
      val manager = context.getSystemService(WindowManager::class.java)
      val view = TextView(context).apply {
        this.text = text
        setTextColor(android.graphics.Color.WHITE)
        setBackgroundColor(0xDD1D232A.toInt())
        textSize = 15f
        setPadding(32, 20, 32, 20)
      }
      val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        android.graphics.PixelFormat.TRANSLUCENT,
      ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = 96
      }
      manager.addView(view, params)
      views[id] = view
      handler.postDelayed({ hide(context, id) }, durationMs)
    }
  }

  fun hide(context: Context, id: String) {
    handler.post { hideOnMain(context, id) }
  }

  private fun hideOnMain(context: Context, id: String) {
    val view = views.remove(id) ?: return
    runCatching { context.getSystemService(WindowManager::class.java).removeView(view) }
  }
}

private class AndroidCommandArgs(private val values: List<String>) {
  fun command(default: String = ""): String = values.firstOrNull()?.lowercase().orEmpty().ifBlank { default }

  fun positional(index: Int): String = values.getOrNull(index).orEmpty()

  fun string(name: String, default: String = ""): String {
    val index = optionIndex(name)
    return if (index >= 0) values.getOrNull(index + 1).orEmpty() else default
  }

  fun required(name: String): String = string(name).ifBlank { error("--$name is required") }

  fun int(name: String, default: Int): Int = string(name).toIntOrNull() ?: default

  fun long(name: String, default: Long): Long = string(name).toLongOrNull() ?: default

  fun requiredLong(name: String): Long = required(name).toLongOrNull() ?: error("--$name must be an integer")

  fun requiredInt(name: String): Int = required(name).toIntOrNull() ?: error("--$name must be an integer")

  fun requiredIntAny(vararg names: String): Int {
    names.forEach { name ->
      val value = string(name)
      if (value.isNotBlank()) return value.toIntOrNull() ?: error("--$name must be an integer")
    }
    error("--${names.first()} is required")
  }

  fun has(name: String): Boolean = optionIndex(name) >= 0

  private fun optionIndex(name: String): Int {
    return values.indexOfFirst { it == "--$name" }
  }
}
