package com.flovera.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flovera.app.platform.AndroidPermissionCapabilities
import com.flovera.app.performance.UiResponsivenessMonitor
import com.flovera.app.web.FloveraWebBridge
import com.flovera.app.theme.FloveraTheme
import java.util.ArrayDeque

class MainActivity : ComponentActivity() {
  private lateinit var controller: AgentController
  private val uiResponsivenessMonitor = UiResponsivenessMonitor()
  private val pendingSpecialPermissionIds = ArrayDeque<String>()
  private var permissionGrantFlowActive = false
  private val runtimePermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) {
    launchNextSpecialPermission()
  }
  private val specialPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) {
    launchNextSpecialPermission()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    uiResponsivenessMonitor.start()

    val styleShowcaseMode = resources.getBoolean(R.bool.style_showcase_mode)

    enableEdgeToEdge()
    if (styleShowcaseMode) {
      WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
      WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
    }

    if (styleShowcaseMode) {
      setContent {
        FloveraTheme(themeMode = "light", themeColor = "#127089") {
          StyleShowcaseApp()
        }
      }
      return
    }

    controller = AgentControllerProvider.get(applicationContext)
    consumeShareIntent(intent)
    setContent {
      val appController = remember { controller }
      val state by appController.state.collectAsStateWithLifecycle()
      DisposableEffect(appController) { onDispose { } }
      DisposableEffect(state.settings.themeMode) {
        val lightSystemBars = state.settings.themeMode == "light"
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = lightSystemBars
        controller.isAppearanceLightNavigationBars = lightSystemBars
        onDispose { }
      }

      FloveraTheme(
        themeMode = state.settings.themeMode,
        themeColor = state.settings.themeColor,
      ) {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background,
        ) {
          AgentScreen(appController)
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    consumeShareIntent(intent)
  }

  override fun onDestroy() {
    uiResponsivenessMonitor.stop()
    super.onDestroy()
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == FloveraWebBridge.NOTIFICATION_PERMISSION_REQUEST_CODE &&
      grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
    ) {
      FloveraWebBridge.flushPendingNotification(this)
    }
  }

  fun requestAllAndroidPermissions() {
    if (permissionGrantFlowActive) return
    permissionGrantFlowActive = true
    pendingSpecialPermissionIds.clear()
    pendingSpecialPermissionIds.addAll(AndroidPermissionCapabilities.deniedSpecialPermissionIds(this))

    val runtimePermissions = AndroidPermissionCapabilities.missingRuntimePermissions(this)
    if (runtimePermissions.isNotEmpty()) {
      runtimePermissionLauncher.launch(runtimePermissions.toTypedArray())
    } else {
      launchNextSpecialPermission()
    }
  }

  private fun launchNextSpecialPermission() {
    while (pendingSpecialPermissionIds.isNotEmpty()) {
      val id = pendingSpecialPermissionIds.removeFirst()
      val capability = AndroidPermissionCapabilities.capabilities.firstOrNull { it.id == id } ?: continue
      if (AndroidPermissionCapabilities.stateFor(this, capability) == "granted") continue
      val intent = AndroidPermissionCapabilities.permissionIntent(this, id) ?: continue
      specialPermissionLauncher.launch(intent)
      return
    }
    permissionGrantFlowActive = false
  }

  private fun consumeShareIntent(intent: Intent?) {
    if (::controller.isInitialized && controller.importSharedIntent(intent)) {
      setIntent(Intent())
    }
  }
}
