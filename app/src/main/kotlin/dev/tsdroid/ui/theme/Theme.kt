package dev.tsdroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object GlassColors {
    val Surface        = Color.White.copy(alpha = 0.15f)
    val SurfaceHigh    = Color.White.copy(alpha = 0.25f)
    val SurfaceVariant = Color.White.copy(alpha = 0.10f)
    val OnSurface      = Color.White.copy(alpha = 0.90f)
    val OnSurfaceVar   = Color.White.copy(alpha = 0.70f)
    val PrimaryCtr     = Color(0xFF5B9BD5).copy(alpha = 0.30f)
    val SecondaryCtr   = Color(0xFF4A86C8).copy(alpha = 0.25f)
    val Outline        = Color.White.copy(alpha = 0.30f)
}

object GlassShapes {
    val Medium = RoundedCornerShape(16.dp)
    val Large  = RoundedCornerShape(20.dp)
}

private val DarkColorScheme = darkColorScheme(
    primary            = Color(0xFF5B9BD5),
    secondary          = Color(0xFF4A86C8),
    tertiary           = Color(0xFF7AB8E0),
    background         = Color.Transparent,
    surface            = GlassColors.Surface,
    surfaceVariant     = GlassColors.SurfaceVariant,
    onPrimary          = Color.White,
    onSecondary        = Color.White,
    onBackground       = GlassColors.OnSurface,
    onSurface          = GlassColors.OnSurface,
    onSurfaceVariant   = GlassColors.OnSurfaceVar,
    primaryContainer   = GlassColors.PrimaryCtr,
    secondaryContainer = GlassColors.SecondaryCtr,
    outline            = GlassColors.Outline,
)

private val LightColorScheme = lightColorScheme(
    primary            = Color(0xFF2962FF),
    secondary          = Color(0xFF1565C0),
    tertiary           = Color(0xFF0D47A1),
    background         = Color.Transparent,
    surface            = GlassColors.Surface,
    surfaceVariant     = GlassColors.SurfaceVariant,
    onPrimary          = Color.White,
    onBackground       = GlassColors.OnSurface,
    onSurface          = GlassColors.OnSurface,
    onSurfaceVariant   = GlassColors.OnSurfaceVar,
    primaryContainer   = GlassColors.PrimaryCtr,
    secondaryContainer = GlassColors.SecondaryCtr,
    outline            = GlassColors.Outline,
)

@Composable
fun TsDroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
