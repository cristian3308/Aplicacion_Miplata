package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = LightBlueAccentDark,
    onPrimary = LightBlueOnAccentDark,
    primaryContainer = LightBlueContainerDark,
    onPrimaryContainer = LightBlueOnContainerDark,
    secondary = IncomeGreenDark,
    onSecondary = IncomeOnGreenDark,
    secondaryContainer = IncomeGreenContainerDark,
    onSecondaryContainer = IncomeOnGreenContainerDark,
    tertiary = DebtOrangeDark,
    onTertiary = DebtOnOrangeDark,
    tertiaryContainer = DebtOrangeContainerDark,
    onTertiaryContainer = DebtOnOrangeContainerDark,
    error = AlertRedDark,
    onError = AlertOnRedDark,
    errorContainer = AlertRedContainerDark,
    onErrorContainer = AlertOnRedContainerDark,
    background = NavyBackgroundDark,
    onBackground = OnSurfaceDark,
    surface = NavySurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = NavySurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = NavyCardBorderDark
)

private val LightColorScheme = lightColorScheme(
    primary = LightBlueAccentLight,
    onPrimary = LightBlueOnAccentLight,
    primaryContainer = LightBlueContainerLight,
    onPrimaryContainer = LightBlueOnContainerLight,
    secondary = IncomeGreenLight,
    onSecondary = IncomeOnGreenLight,
    secondaryContainer = IncomeGreenContainerLight,
    onSecondaryContainer = IncomeOnGreenContainerLight,
    tertiary = DebtOrangeLight,
    onTertiary = DebtOnOrangeLight,
    tertiaryContainer = DebtOrangeContainerLight,
    onTertiaryContainer = DebtOnOrangeContainerLight,
    error = AlertRedLight,
    onError = AlertOnRedLight,
    errorContainer = AlertRedContainerLight,
    onErrorContainer = AlertOnRedContainerLight,
    background = NavyBackgroundLight,
    onBackground = OnSurfaceLight,
    surface = NavySurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = NavySurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = NavyCardBorderLight
)

val MiPlataShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun MiPlataTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = MiPlataShapes,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MiPlataTheme(darkTheme = darkTheme, content = content)
}
