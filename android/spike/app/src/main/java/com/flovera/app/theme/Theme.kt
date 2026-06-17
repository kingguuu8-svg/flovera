package com.flovera.app.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DefaultAccent = FloveraAccent

private val FloveraDarkColorScheme =
  darkColorScheme(
    primary = DefaultAccent,
    onPrimary = FloveraBackground,
    primaryContainer = FloveraAccentContainer,
    onPrimaryContainer = FloveraOnAccentContainer,
    secondary = FloveraMutedText,
    onSecondary = FloveraBackground,
    secondaryContainer = FloveraSurfaceVariant,
    onSecondaryContainer = FloveraText,
    tertiary = DefaultAccent,
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

private val FloveraLightColorScheme =
  lightColorScheme(
    primary = DefaultAccent,
    onPrimary = Color.White,
    primaryContainer = blend(DefaultAccent, Color.White, 0.78f),
    onPrimaryContainer = FloveraLightText,
    secondary = FloveraLightMutedText,
    onSecondary = Color.White,
    secondaryContainer = FloveraLightSurfaceVariant,
    onSecondaryContainer = FloveraLightText,
    tertiary = DefaultAccent,
    background = FloveraLightBackground,
    onBackground = FloveraLightText,
    surface = FloveraLightSurface,
    onSurface = FloveraLightText,
    surfaceVariant = FloveraLightSurfaceVariant,
    onSurfaceVariant = FloveraLightMutedText,
    outline = FloveraLightOutline,
    outlineVariant = FloveraLightPanelLine,
    error = FloveraError,
    onError = Color.White,
    errorContainer = FloveraLightErrorContainer,
    onErrorContainer = FloveraLightText,
  )

@Composable
fun FloveraTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  themeMode: String = if (darkTheme) "dark" else "light",
  themeColor: String = "#76C4D8",
  content: @Composable () -> Unit,
) {
  val useDarkTheme = themeMode != "light"
  val accent = parseThemeColor(themeColor) ?: DefaultAccent
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      useDarkTheme -> FloveraDarkColorScheme.withAccent(accent, darkTheme = true)
      else -> FloveraLightColorScheme.withAccent(accent, darkTheme = false)
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

private fun ColorScheme.withAccent(accent: Color, darkTheme: Boolean): ColorScheme {
  val surfaceBase = if (darkTheme) FloveraSurfaceVariant else FloveraLightSurfaceVariant
  val outlineBase = if (darkTheme) FloveraOutline else FloveraLightOutline
  val outlineVariantBase = if (darkTheme) FloveraPanelLine else FloveraLightPanelLine
  val primaryContainer = if (darkTheme) {
    blend(accent, FloveraBackground, 0.82f)
  } else {
    blend(accent, Color.White, 0.78f)
  }
  val secondaryContainer = if (darkTheme) {
    blend(accent, FloveraSurface, 0.78f)
  } else {
    blend(accent, Color.White, 0.86f)
  }
  val themedSurfaceVariant = if (darkTheme) {
    blend(accent, surfaceBase, 0.86f)
  } else {
    blend(accent, surfaceBase, 0.9f)
  }
  val onPrimaryContainer = if (darkTheme) {
    blend(accent, Color.White, 0.82f)
  } else {
    FloveraLightText
  }
  return copy(
    primary = accent,
    secondary = accent,
    tertiary = accent,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = if (darkTheme) blend(accent, Color.White, 0.84f) else FloveraLightText,
    tertiaryContainer = secondaryContainer,
    onTertiaryContainer = if (darkTheme) blend(accent, Color.White, 0.84f) else FloveraLightText,
    surfaceVariant = themedSurfaceVariant,
    outline = blend(accent, outlineBase, 0.68f),
    outlineVariant = blend(accent, outlineVariantBase, 0.78f),
  )
}

private fun parseThemeColor(value: String): Color? {
  val normalized = value.trim().removePrefix("#")
  if (!Regex("^[0-9A-Fa-f]{6}$").matches(normalized)) return null
  return Color(("FF$normalized").toLong(16))
}

private fun blend(source: Color, target: Color, targetRatio: Float): Color {
  val sourceRatio = 1f - targetRatio
  return Color(
    red = source.red * sourceRatio + target.red * targetRatio,
    green = source.green * sourceRatio + target.green * targetRatio,
    blue = source.blue * sourceRatio + target.blue * targetRatio,
    alpha = source.alpha * sourceRatio + target.alpha * targetRatio,
  )
}
