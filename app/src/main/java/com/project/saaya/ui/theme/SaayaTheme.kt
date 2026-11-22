package com.project.saaya.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SaayaBlack = Color(0xFF101114)
val SaayaWhite = Color(0xFFFFFFFF)
val SaayaBlue = Color(0xFF2563EB)

val SaayaSurface = Color(0xFFF6F7F9)
val SaayaBorder = Color(0xFFE4E7EC)
val SaayaTextSecondary = Color(0xFF667085)

val SaayaEmergencyRed = Color(0xFFD92D20)
val SaayaSuccessGreen = Color(0xFF16803C)

private val SaayaLightColors = lightColorScheme(
    primary = SaayaBlack,
    onPrimary = SaayaWhite,

    secondary = SaayaBlue,
    onSecondary = SaayaWhite,

    background = SaayaWhite,
    onBackground = SaayaBlack,

    surface = SaayaWhite,
    onSurface = SaayaBlack,

    surfaceVariant = SaayaSurface,
    onSurfaceVariant = SaayaTextSecondary,

    outline = SaayaBorder,

    error = SaayaEmergencyRed,
    onError = SaayaWhite
)

@Composable
fun SaayaTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SaayaLightColors,
        typography = Typography(),
        content = content
    )
}
