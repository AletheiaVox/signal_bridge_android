package com.signalbridge.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Signal Bridge palette — red/black for safety-critical UI, warm grays elsewhere
val SBRed = Color(0xFFDC2626)
val SBRedDark = Color(0xFFB91C1C)
val SBRedLight = Color(0xFFFEE2E2)
val SBBlack = Color(0xFF0F0F0F)
val SBGrayDark = Color(0xFF1C1C1E)
val SBGrayMid = Color(0xFF2C2C2E)
val SBGrayLight = Color(0xFF8E8E93)
val SBWhite = Color(0xFFF5F5F7)
val SBGreen = Color(0xFF22C55E)
val SBAmber = Color(0xFFF59E0B)

private val DarkColorScheme = darkColorScheme(
    primary = SBRed,
    onPrimary = Color.White,
    primaryContainer = SBRedDark,
    secondary = SBGrayLight,
    background = SBBlack,
    surface = SBGrayDark,
    surfaceVariant = SBGrayMid,
    onBackground = SBWhite,
    onSurface = SBWhite,
    error = SBRed,
)

private val LightColorScheme = lightColorScheme(
    primary = SBRedDark,
    onPrimary = Color.White,
    primaryContainer = SBRedLight,
    secondary = SBGrayLight,
    background = SBWhite,
    surface = Color.White,
    onBackground = SBBlack,
    onSurface = SBBlack,
    error = SBRed,
)

@Composable
fun SignalBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
