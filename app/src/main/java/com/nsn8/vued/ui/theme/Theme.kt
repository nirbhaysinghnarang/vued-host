package com.nsn8.vued.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val DevLightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

private val VuedLightColorScheme = lightColorScheme(
    primary = VuedAccent,
    secondary = VuedTextSecondary,
    tertiary = VuedSuccess,
    background = VuedBackground,
    surface = VuedSurface,
    surfaceVariant = VuedSurfaceAlt,
    outline = VuedHairline,
    error = VuedDanger,
    onPrimary = VuedBackground,
    onSecondary = VuedBackground,
    onTertiary = VuedBackground,
    onBackground = VuedTextPrimary,
    onSurface = VuedTextPrimary,
    onSurfaceVariant = VuedTextSecondary,

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun VuedTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    desktopTheme: Boolean = false,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        desktopTheme -> VuedLightColorScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> DevLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = if (desktopTheme) VuedTypography else DevTypography,
        content = content
    )
}
