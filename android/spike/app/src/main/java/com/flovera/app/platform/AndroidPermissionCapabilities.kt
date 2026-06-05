package com.flovera.app.platform

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat

const val FLOVERA_PERMISSION_REQUEST_CODE = 1202

data class AndroidPermissionCapability(
  val id: String,
  val labelEn: String,
  val labelZh: String,
  val type: AndroidPermissionType,
  val permission: String? = null,
  val minSdk: Int = 1,
  val maxSdk: Int = Int.MAX_VALUE,
)

enum class AndroidPermissionType {
  Runtime,
  Special,
  Manifest,
}

data class AndroidPermissionStatus(
  val capability: AndroidPermissionCapability,
  val state: String,
) {
  val granted: Boolean get() = state == "granted"
}

object AndroidPermissionCapabilities {
  val capabilities: List<AndroidPermissionCapability> = listOf(
    AndroidPermissionCapability(
      id = "notifications",
      labelEn = "Notifications",
      labelZh = "通知",
      type = AndroidPermissionType.Runtime,
      permission = Manifest.permission.POST_NOTIFICATIONS,
      minSdk = Build.VERSION_CODES.TIRAMISU,
    ),
    AndroidPermissionCapability(
      id = "camera",
      labelEn = "Camera",
      labelZh = "相机",
      type = AndroidPermissionType.Runtime,
      permission = Manifest.permission.CAMERA,
    ),
    AndroidPermissionCapability(
      id = "microphone",
      labelEn = "Microphone",
      labelZh = "麦克风",
      type = AndroidPermissionType.Runtime,
      permission = Manifest.permission.RECORD_AUDIO,
    ),
    AndroidPermissionCapability(
      id = "fine_location",
      labelEn = "Precise location",
      labelZh = "精确位置",
      type = AndroidPermissionType.Runtime,
      permission = Manifest.permission.ACCESS_FINE_LOCATION,
    ),
    AndroidPermissionCapability(
      id = "coarse_location",
      labelEn = "Approximate location",
      labelZh = "大致位置",
      type = AndroidPermissionType.Runtime,
      permission = Manifest.permission.ACCESS_COARSE_LOCATION,
    ),
    AndroidPermissionCapability(
      id = "contacts_read",
      labelEn = "Read contacts",
      labelZh = "读取联系人",
      type = AndroidPermissionType.Runtime,
      permission = Manifest.permission.READ_CONTACTS,
    ),
    AndroidPermissionCapability(
      id = "contacts_write",
      labelEn = "Write contacts",
      labelZh = "写入联系人",
      type = AndroidPermissionType.Runtime,
      permission = Manifest.permission.WRITE_CONTACTS,
    ),
    AndroidPermissionCapability(
      id = "calendar_read",
      labelEn = "Read calendar",
      labelZh = "读取日历",
      type = AndroidPermissionType.Runtime,
      permission = Manifest.permission.READ_CALENDAR,
    ),
    AndroidPermissionCapability(
      id = "calendar_write",
      labelEn = "Write calendar",
      labelZh = "写入日历",
      type = AndroidPermissionType.Runtime,
      permission = Manifest.permission.WRITE_CALENDAR,
    ),
    AndroidPermissionCapability(
      id = "media_images",
      labelEn = "Images",
      labelZh = "图片",
      type = AndroidPermissionType.Runtime,
      permission = Manifest.permission.READ_MEDIA_IMAGES,
      minSdk = Build.VERSION_CODES.TIRAMISU,
    ),
    AndroidPermissionCapability(
      id = "media_visual_selected",
      labelEn = "Selected images and video",
      labelZh = "选中的图片和视频",
      type = AndroidPermissionType.Runtime,
      permission = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
      minSdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
    ),
    AndroidPermissionCapability(
      id = "media_video",
      labelEn = "Video",
      labelZh = "视频",
      type = AndroidPermissionType.Runtime,
      permission = Manifest.permission.READ_MEDIA_VIDEO,
      minSdk = Build.VERSION_CODES.TIRAMISU,
    ),
    AndroidPermissionCapability(
      id = "media_audio",
      labelEn = "Audio",
      labelZh = "音频",
      type = AndroidPermissionType.Runtime,
      permission = Manifest.permission.READ_MEDIA_AUDIO,
      minSdk = Build.VERSION_CODES.TIRAMISU,
    ),
    AndroidPermissionCapability(
      id = "external_storage_read",
      labelEn = "Legacy storage read",
      labelZh = "旧版存储读取",
      type = AndroidPermissionType.Runtime,
      permission = Manifest.permission.READ_EXTERNAL_STORAGE,
      maxSdk = Build.VERSION_CODES.S_V2,
    ),
    AndroidPermissionCapability(
      id = "bluetooth_scan",
      labelEn = "Bluetooth scan",
      labelZh = "蓝牙扫描",
      type = AndroidPermissionType.Runtime,
      permission = Manifest.permission.BLUETOOTH_SCAN,
      minSdk = Build.VERSION_CODES.S,
    ),
    AndroidPermissionCapability(
      id = "bluetooth_connect",
      labelEn = "Bluetooth connect",
      labelZh = "蓝牙连接",
      type = AndroidPermissionType.Runtime,
      permission = Manifest.permission.BLUETOOTH_CONNECT,
      minSdk = Build.VERSION_CODES.S,
    ),
    AndroidPermissionCapability(
      id = "battery_optimization",
      labelEn = "Ignore battery optimization",
      labelZh = "忽略电池优化",
      type = AndroidPermissionType.Special,
      minSdk = Build.VERSION_CODES.M,
    ),
    AndroidPermissionCapability(
      id = "overlay",
      labelEn = "Display over other apps",
      labelZh = "悬浮窗",
      type = AndroidPermissionType.Special,
      minSdk = Build.VERSION_CODES.M,
    ),
    AndroidPermissionCapability(
      id = "all_files",
      labelEn = "All files access",
      labelZh = "所有文件访问",
      type = AndroidPermissionType.Special,
      minSdk = Build.VERSION_CODES.R,
    ),
    AndroidPermissionCapability(
      id = "install_unknown_apps",
      labelEn = "Install unknown apps",
      labelZh = "安装未知应用",
      type = AndroidPermissionType.Special,
      minSdk = Build.VERSION_CODES.O,
    ),
    AndroidPermissionCapability(
      id = "exact_alarm",
      labelEn = "Exact alarms",
      labelZh = "精确闹钟",
      type = AndroidPermissionType.Special,
      minSdk = Build.VERSION_CODES.S,
    ),
    AndroidPermissionCapability(
      id = "accessibility",
      labelEn = "Desktop operation accessibility",
      labelZh = "桌面操作无障碍服务",
      type = AndroidPermissionType.Special,
    ),
    AndroidPermissionCapability(
      id = "internet",
      labelEn = "Internet",
      labelZh = "网络",
      type = AndroidPermissionType.Manifest,
      permission = Manifest.permission.INTERNET,
    ),
    AndroidPermissionCapability(
      id = "foreground_service",
      labelEn = "Foreground service",
      labelZh = "前台服务",
      type = AndroidPermissionType.Manifest,
      permission = Manifest.permission.FOREGROUND_SERVICE,
    ),
  )

  fun status(context: Context): List<AndroidPermissionStatus> {
    return capabilities.map { capability ->
      AndroidPermissionStatus(capability, stateFor(context, capability))
    }
  }

  fun requestRuntimePermissions(activity: Activity): List<String> {
    val permissions = missingRuntimePermissions(activity)
    if (permissions.isNotEmpty()) {
      ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), FLOVERA_PERMISSION_REQUEST_CODE)
    }
    return permissions
  }

  fun missingRuntimePermissions(context: Context): List<String> {
    return capabilities
      .filter { it.type == AndroidPermissionType.Runtime && isSdkSupported(it) }
      .mapNotNull { it.permission }
      .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
      .distinct()
  }

  fun deniedSpecialPermissionIds(context: Context): List<String> {
    return capabilities
      .filter { it.type == AndroidPermissionType.Special && isSdkSupported(it) }
      .filter { stateFor(context, it) != "granted" }
      .map { it.id }
  }

  fun permissionIntent(context: Context, id: String): Intent? {
    return specialPermissionIntent(context, id)
  }

  fun openPermission(context: Context, id: String): Boolean {
    val activity = context.findActivity()
    val launchContext = activity ?: context.applicationContext
    val intent = specialPermissionIntent(launchContext, id)
      ?: appDetailsIntent(launchContext)
    return runCatching {
      if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      launchContext.startActivity(intent)
    }.isSuccess
  }

  fun openAppDetails(context: Context): Boolean {
    val activity = context.findActivity()
    val launchContext = activity ?: context.applicationContext
    val intent = appDetailsIntent(launchContext)
    return runCatching {
      if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      launchContext.startActivity(intent)
    }.isSuccess
  }

  fun stateFor(context: Context, capability: AndroidPermissionCapability): String {
    if (!isSdkSupported(capability)) return "not_applicable"
    return when (capability.type) {
      AndroidPermissionType.Runtime,
      AndroidPermissionType.Manifest -> {
        val permission = capability.permission ?: return "unknown"
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) "granted" else "denied"
      }
      AndroidPermissionType.Special -> specialState(context, capability.id)
    }
  }

  private fun specialState(context: Context, id: String): String {
    return when (id) {
      "battery_optimization" -> {
        val power = context.getSystemService(PowerManager::class.java)
        if (power?.isIgnoringBatteryOptimizations(context.packageName) == true) "granted" else "denied"
      }
      "overlay" -> if (Settings.canDrawOverlays(context)) "granted" else "denied"
      "all_files" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
        "granted"
      } else {
        "denied"
      }
      "install_unknown_apps" -> if (context.packageManager.canRequestPackageInstalls()) "granted" else "denied"
      "exact_alarm" -> {
        val alarm = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm?.canScheduleExactAlarms() == true) {
          "granted"
        } else {
          "denied"
        }
      }
      "accessibility" -> if (isAccessibilityServiceEnabled(context)) "granted" else "denied"
      else -> "unknown"
    }
  }

  private fun specialPermissionIntent(context: Context, id: String): Intent? {
    val packageUri = Uri.parse("package:${context.packageName}")
    return when (id) {
      "notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
          .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
      } else {
        appDetailsIntent(context)
      }
      "battery_optimization" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
      } else {
        appDetailsIntent(context)
      }
      "overlay" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
      } else {
        appDetailsIntent(context)
      }
      "all_files" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
      } else {
        appDetailsIntent(context)
      }
      "install_unknown_apps" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
      } else {
        appDetailsIntent(context)
      }
      "exact_alarm" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
      } else {
        appDetailsIntent(context)
      }
      "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
      "app_details" -> appDetailsIntent(context)
      else -> null
    }
  }

  private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, FloveraAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(
      context.contentResolver,
      Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabled.split(':')
      .mapNotNull(ComponentName::unflattenFromString)
      .any { it == expected }
  }

  private fun appDetailsIntent(context: Context): Intent {
    return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
  }

  private fun isSdkSupported(capability: AndroidPermissionCapability): Boolean {
    return Build.VERSION.SDK_INT in capability.minSdk..capability.maxSdk
  }
}

fun Context.findActivity(): Activity? {
  var current: Context? = this
  while (current is ContextWrapper) {
    if (current is Activity) return current
    current = current.baseContext
  }
  return null
}
