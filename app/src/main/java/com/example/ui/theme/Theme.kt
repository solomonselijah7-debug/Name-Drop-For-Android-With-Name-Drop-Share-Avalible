package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class IosColors(
    val background: Color,
    val card: Color,
    val secondaryFill: Color,
    val separator: Color,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val blue: Color,
    val green: Color,
    val red: Color,
    val orange: Color,
    val isDark: Boolean
)

val LocalIosColors = staticCompositionLocalOf {
    IosColors(
        background = LightBackground,
        card = LightCard,
        secondaryFill = LightSecondaryFill,
        separator = LightSeparator,
        labelPrimary = LightLabelPrimary,
        labelSecondary = LightLabelSecondary,
        blue = IosBlueLight,
        green = IosGreenLight,
        red = IosRedLight,
        orange = IosOrange,
        isDark = false
    )
}

private val LightColorScheme = lightColorScheme(
    primary = IosBlueLight,
    onPrimary = Color.White,
    secondary = IosGreenLight,
    onSecondary = Color.White,
    tertiary = IosIndigo,
    background = LightBackground,
    onBackground = LightLabelPrimary,
    surface = LightCard,
    onSurface = LightLabelPrimary,
    surfaceVariant = LightSecondaryFill,
    onSurfaceVariant = LightLabelSecondary,
    outline = LightSeparator,
    error = IosRedLight,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = IosBlue,
    onPrimary = Color.White,
    secondary = IosGreen,
    onSecondary = Color.Black,
    tertiary = IosIndigo,
    background = DarkBackground,
    onBackground = DarkLabelPrimary,
    surface = DarkCard,
    onSurface = DarkLabelPrimary,
    surfaceVariant = DarkSecondaryFill,
    onSurfaceVariant = DarkLabelSecondary,
    outline = DarkSeparator,
    error = IosRed,
    onError = Color.White
)

private val NameDropShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun NameDropTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val iosColors = if (darkTheme) {
        IosColors(
            background = DarkBackground,
            card = DarkCard,
            secondaryFill = DarkSecondaryFill,
            separator = DarkSeparator,
            labelPrimary = DarkLabelPrimary,
            labelSecondary = DarkLabelSecondary,
            blue = IosBlue,
            green = IosGreen,
            red = IosRed,
            orange = IosOrange,
            isDark = true
        )
    } else {
        IosColors(
            background = LightBackground,
            card = LightCard,
            secondaryFill = LightSecondaryFill,
            separator = LightSeparator,
            labelPrimary = LightLabelPrimary,
            labelSecondary = LightLabelSecondary,
            blue = IosBlueLight,
            green = IosGreenLight,
            red = IosRedLight,
            orange = IosOrange,
            isDark = false
        )
    }

    CompositionLocalProvider(LocalIosColors provides iosColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = NameDropTypography,
            shapes = NameDropShapes,
            content = content
        )
    }
}
