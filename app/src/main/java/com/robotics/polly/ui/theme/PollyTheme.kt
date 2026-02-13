package com.robotics.polly.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ═══════════════════════════════════════════════════════════
// COLOR PALETTE - HIGH-END TUI AESTHETIC
// ═══════════════════════════════════════════════════════════

// Faded black jeans - washed out, lived-in black
val BackgroundPrimary = Color(0xFF1E1E1C)       // Main background
val BackgroundSecondary = Color(0xFF252522)     // Cards, panels
val BackgroundTertiary = Color(0xFF2C2C28)      // Elevated surfaces

// Edison bulb warm white - not pure white, aged incandescent glow
val TextPrimary = Color(0xFFF5F3E8)             // Main text/lines
val TextSecondary = Color(0xFFD4D2C4)           // Dimmed labels
val TextTertiary = Color(0xFFB0AEA0)            // Hints, disabled

// Deep vintage couch colors - matte, understated
val AccentGarnet = Color(0xFF6B3939)            // Critical alerts, warnings
val AccentGarnetDim = Color(0xFF5C2E2E)         // Hover states
val AccentEmerald = Color(0xFF3A5C46)           // Active, success, motors on
val AccentEmeraldDim = Color(0xFF2E5C3E)        // Inactive motors

// Utility
val GridLines = Color(0xFF3A3A36)               // Subtle grid/dividers
val Highlight = Color(0xFFFFE6A0)               // CRT glow effect (rare use)

// ═══════════════════════════════════════════════════════════
// TYPOGRAPHY - MONOSPACED TERMINAL AESTHETIC
// ═══════════════════════════════════════════════════════════

private val PollyTypography = Typography(
    // Headers (ALL CAPS)
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

// ═══════════════════════════════════════════════════════════
// SHAPES - SHARP RECTANGLES (NO ROUNDED CORNERS)
// ═══════════════════════════════════════════════════════════

private val PollyShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp)
)

// ═══════════════════════════════════════════════════════════
// THEME COMPOSABLE
// ═══════════════════════════════════════════════════════════

@Composable
fun PollyTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = darkColorScheme(
        // Primary colors (emerald for active states)
        primary = AccentEmerald,
        onPrimary = TextPrimary,
        primaryContainer = AccentEmeraldDim,
        onPrimaryContainer = TextPrimary,
        
        // Secondary colors (garnet for warnings)
        secondary = AccentGarnet,
        onSecondary = TextPrimary,
        secondaryContainer = AccentGarnetDim,
        onSecondaryContainer = TextPrimary,
        
        // Background
        background = BackgroundPrimary,
        onBackground = TextPrimary,
        
        // Surface (cards, panels)
        surface = BackgroundSecondary,
        onSurface = TextPrimary,
        surfaceVariant = BackgroundTertiary,
        onSurfaceVariant = TextSecondary,
        
        // Outline (borders, dividers)
        outline = GridLines,
        outlineVariant = GridLines,
        
        // Error (use garnet)
        error = AccentGarnet,
        onError = TextPrimary,
        errorContainer = AccentGarnetDim,
        onErrorContainer = TextPrimary,
        
        // Inverse (for contrast)
        inverseSurface = TextPrimary,
        inverseOnSurface = BackgroundPrimary,
        inversePrimary = AccentEmeraldDim,
        
        // Scrim (overlays)
        scrim = Color.Black.copy(alpha = 0.5f)
    )
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = PollyTypography,
        shapes = PollyShapes,
        content = content
    )
}
