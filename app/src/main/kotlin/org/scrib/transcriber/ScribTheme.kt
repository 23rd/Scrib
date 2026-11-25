package org.scrib.transcriber

import android.app.Activity
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.WindowCompat

internal val LocalAppThemeMode = staticCompositionLocalOf { AppThemeMode.SYSTEM }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF006A6A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC7EAE8),
    onPrimaryContainer = Color(0xFF00201F),
    background = Color(0xFFF6F9F8),
    onBackground = Color(0xFF171D1C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171D1C),
    surfaceContainer = Color(0xFFEEF3F1),
    onSurfaceVariant = Color(0xFF3F4A48),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFD3DEDC),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4FD8DB),
    onPrimary = Color(0xFF00373A),
    primaryContainer = Color(0xFF00504F),
    onPrimaryContainer = Color(0xFF7AF4F4),
    background = Color(0xFF0E1413),
    onBackground = Color(0xFFDEE4E2),
    surface = Color(0xFF0E1413),
    onSurface = Color(0xFFDEE4E2),
    surfaceContainer = Color(0xFF1A2221),
    onSurfaceVariant = Color(0xFFBEC9C6),
    outline = Color(0xFF889492),
    outlineVariant = Color(0xFF3F4A48),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScribTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val prefs = remember(context) { AppThemePreferences.get(context) }
    fun readMode() = AppThemeMode.fromStorage(prefs.getString(AppThemePreferences.KEY, null))
    var mode by remember(prefs) { mutableStateOf(readMode()) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == AppThemePreferences.KEY) mode = readMode()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        mode = readMode()
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val dark = mode.usesDarkColors(isSystemInDarkTheme())
    val palette = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    val scheme = if (mode == AppThemeMode.BLACK) {
        palette.copy(background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black)
    } else palette
    if (!view.isInEditMode && context is Activity) {
        SideEffect {
            val window = context.window
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                window.statusBarColor = scheme.background.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = scheme.background.toArgb()
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalAppThemeMode provides mode) {
        MaterialTheme(colorScheme = scheme) {
            Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                content()
            }
        }
    }
}
