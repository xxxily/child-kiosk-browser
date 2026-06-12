package com.example.childkiosk.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ChildColorScheme = lightColorScheme(
    primary = Color(0xFFFFB300),         // 暖橙
    onPrimary = Color(0xFF402900),
    primaryContainer = Color(0xFFFFE082),
    onPrimaryContainer = Color(0xFF402900),
    secondary = Color(0xFF26C6DA),       // 天蓝
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2EBF2),
    onSecondaryContainer = Color(0xFF003940),
    tertiary = Color(0xFF66BB6A),        // 草绿
    onTertiary = Color.White,
    background = Color(0xFFFFFCF6),
    onBackground = Color(0xFF4E342E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF4E342E),
    surfaceVariant = Color(0xFFFFF3D6),
    onSurfaceVariant = Color(0xFF6D4C41),
    error = Color(0xFFD32F2F),
    onError = Color.White
)

private val ChildShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val ChildTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Black),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
)

@Composable
fun ChildKioskTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ChildColorScheme,
        typography = ChildTypography,
        shapes = ChildShapes,
        content = content
    )
}
