package ai.edgeml.pairing.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * EdgeML brand colors used as Material3 seed.
 */
private val EdgeMLBlue = Color(0xFF2563EB)
private val EdgeMLBlueDark = Color(0xFF60A5FA)

private val LightColorScheme = lightColorScheme(
    primary = EdgeMLBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE4FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF575E71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBE2F9),
    onSecondaryContainer = Color(0xFF141B2C),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFEFBFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFEFBFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
)

private val DarkColorScheme = darkColorScheme(
    primary = EdgeMLBlueDark,
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF0E4393),
    onPrimaryContainer = Color(0xFFDBE4FF),
    secondary = Color(0xFFBFC6DC),
    onSecondary = Color(0xFF293041),
    secondaryContainer = Color(0xFF3F4759),
    onSecondaryContainer = Color(0xFFDBE2F9),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1B1B1F),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF1B1B1F),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
)

/**
 * Material3 theme for the EdgeML pairing screen.
 *
 * Uses dynamic color on Android 12+ when available, falling back to
 * EdgeML brand colors on older devices.
 *
 * @param darkTheme Whether to use the dark color scheme.
 * @param dynamicColor Whether to use dynamic colors (Android 12+).
 */
@Composable
fun EdgeMLPairingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
