package com.openobsidian.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary      = AccentLight,
    onPrimary    = BackgroundLight,
    surface      = SurfaceLight,
    onSurface    = OnSurfaceLight,
    background   = BackgroundLight,
    onBackground = OnSurfaceLight,
    outline      = OutlineLight,
)

private val DarkColors = darkColorScheme(
    primary      = AccentDark,
    onPrimary    = BackgroundDark,
    surface      = SurfaceDark,
    onSurface    = OnSurfaceDark,
    background   = BackgroundDark,
    onBackground = OnSurfaceDark,
    outline      = OutlineDark,
)

/**
 * App-wide theme.
 *
 * - Status / navigation bar backgrounds are transparent (enableEdgeToEdge in
 *   MainActivity owns that). We only set icon light/dark appearance here.
 * - Opts into Material You dynamic color on Android 12+ when [useDynamic] = true.
 */
@Composable
fun OpenObsidianTheme(
    darkTheme:  Boolean = isSystemInDarkTheme(),
    useDynamic: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        useDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else      -> LightColors
    }

    // Only control icon tint (light vs dark). Bar backgrounds stay transparent
    // because enableEdgeToEdge() already set them that way in MainActivity.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars     = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
