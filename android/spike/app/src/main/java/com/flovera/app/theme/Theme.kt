package com.flovera.app.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val FloveraDarkColorScheme =
  darkColorScheme(
    primary = FloveraAccent,
    onPrimary = FloveraBackground,
    primaryContainer = FloveraAccentContainer,
    onPrimaryContainer = FloveraOnAccentContainer,
    secondary = FloveraMutedText,
    onSecondary = FloveraBackground,
    secondaryContainer = FloveraSurfaceVariant,
    onSecondaryContainer = FloveraText,
    tertiary = FloveraAccent,
    background = FloveraBackground,
    onBackground = FloveraText,
    surface = FloveraSurface,
    onSurface = FloveraText,
    surfaceVariant = FloveraSurfaceVariant,
    onSurfaceVariant = FloveraMutedText,
    outline = FloveraOutline,
    outlineVariant = FloveraPanelLine,
    error = FloveraError,
    onError = FloveraBackground,
    errorContainer = FloveraErrorContainer,
    onErrorContainer = FloveraText,
  )

@Composable
fun FloveraTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> FloveraDarkColorScheme
      else -> FloveraDarkColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
