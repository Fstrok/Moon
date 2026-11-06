package app.moon.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

val Lavender = Color(0xFFC9B8FF)

@Composable
fun MoonTheme(amoled: Boolean, content: @Composable () -> Unit) {
    val bg = if (amoled) Color(0xFF000000) else Color(0xFF0E0E14)
    val container = if (amoled) Color(0xFF0B0B10) else Color(0xFF17161F)
    val scheme = darkColorScheme(
        primary = Lavender,
        onPrimary = Color(0xFF261A57),
        primaryContainer = Color(0xFF3B3175),
        onPrimaryContainer = Color(0xFFE6DEFF),
        secondary = Color(0xFFB9C3FF),
        onSecondary = Color(0xFF1E2A5E),
        secondaryContainer = Color(0xFF2E2D45),
        onSecondaryContainer = Color(0xFFE2E0F9),
        tertiary = Color(0xFFFFB0CD),
        background = bg,
        onBackground = Color(0xFFE8E6F0),
        surface = bg,
        onSurface = Color(0xFFE8E6F0),
        surfaceVariant = Color(0xFF26252F),
        onSurfaceVariant = Color(0xFFAEABBD),
        surfaceContainerLowest = bg,
        surfaceContainerLow = container,
        surfaceContainer = container,
        surfaceContainerHigh = if (amoled) Color(0xFF131219) else Color(0xFF201F2A),
        surfaceContainerHighest = if (amoled) Color(0xFF1A1922) else Color(0xFF2A2934),
        outline = Color(0xFF4A4858),
        outlineVariant = Color(0xFF34333F),
        error = Color(0xFFFFB4AB),
    )
    MaterialTheme(colorScheme = scheme, typography = Typography()) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}
