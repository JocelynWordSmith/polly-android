package com.robotics.polly.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =============================================================
// COLOR PALETTE - BLUEPRINT / TECHNICAL SCHEMATIC
// =============================================================

// Warm cream paper backgrounds
val BackgroundPrimary = Color(0xFFF5F3EE)        // Main background
val BackgroundSecondary = Color(0xFFEBE8E0)      // Cards, panels
val BackgroundTertiary = Color(0xFFE0DDD5)       // Elevated surfaces

// Blueprint blue text
val TextPrimary = Color(0xFF0A3D6B)              // Main text/lines
val TextSecondary = Color(0xFF4A7BA8)            // Dimmed labels
val TextTertiary = Color(0xFF8EAEC4)             // Hints, disabled

// Blueprint blue for UI elements
val BlueprintBlue = Color(0xFF0055AA)            // Primary line/border color
val BlueprintBlueDim = Color(0xFF003D7A)         // Darker variant

// Status colors
val AccentGarnet = Color(0xFFB22222)             // Critical alerts, errors
val AccentGarnetDim = Color(0xFF8B1A1A)          // Darker variant
val AccentEmerald = Color(0xFF1B7340)            // Connected, success
val AccentEmeraldDim = Color(0xFF156233)         // Inactive

// Utility
val GridLines = Color(0xFFB8D0E8)               // Light blue grid/dividers
val Highlight = Color(0xFF0077CC)               // Bright blue for active indicators

// =============================================================
// TYPOGRAPHY - MONOSPACED TECHNICAL
// =============================================================

private val PollyTypography = Typography(
    // Headers
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = TextPrimary
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = TextPrimary
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = TextPrimary
    ),

    // Section labels
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        color = TextPrimary
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        color = TextPrimary
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        color = TextSecondary
    ),

    // Body text
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.5.sp,
        color = TextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp,
        color = TextPrimary
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
        color = TextSecondary
    ),

    // Labels
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        color = TextPrimary
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        color = TextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        color = TextTertiary
    )
)

// =============================================================
// SHAPES - SHARP RECTANGLES (NO ROUNDED CORNERS)
// =============================================================

private val PollyShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp)
)

// =============================================================
// THEME COMPOSABLE
// =============================================================

@Composable
fun PollyTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = lightColorScheme(
        // Primary colors (blueprint blue)
        primary = BlueprintBlue,
        onPrimary = Color.White,
        primaryContainer = BlueprintBlueDim,
        onPrimaryContainer = Color.White,

        // Secondary colors (emerald for success/connected)
        secondary = AccentEmerald,
        onSecondary = Color.White,
        secondaryContainer = AccentEmeraldDim,
        onSecondaryContainer = Color.White,

        // Background
        background = BackgroundPrimary,
        onBackground = TextPrimary,

        // Surface (cards, panels)
        surface = BackgroundSecondary,
        onSurface = TextPrimary,
        surfaceVariant = BackgroundTertiary,
        onSurfaceVariant = TextSecondary,

        // Outline (blueprint blue borders)
        outline = BlueprintBlue,
        outlineVariant = GridLines,

        // Error (red)
        error = AccentGarnet,
        onError = Color.White,
        errorContainer = AccentGarnetDim,
        onErrorContainer = Color.White,

        // Inverse (for contrast)
        inverseSurface = TextPrimary,
        inverseOnSurface = BackgroundPrimary,
        inversePrimary = Highlight,

        // Scrim (overlays)
        scrim = Color.Black.copy(alpha = 0.3f)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PollyTypography,
        shapes = PollyShapes,
        content = content
    )
}
