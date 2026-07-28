package com.chmouel.liseur.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Serif display faces give the app its bookish identity; Literata lands
// with the typography phase and will replace the platform serif here.
private val Serif = FontFamily.Serif

val LiseurTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Serif),
        displayMedium = displayMedium.copy(fontFamily = Serif),
        displaySmall = displaySmall.copy(fontFamily = Serif),
        headlineLarge = headlineLarge.copy(fontFamily = Serif),
        headlineMedium = headlineMedium.copy(fontFamily = Serif),
        headlineSmall = headlineSmall.copy(fontFamily = Serif),
        titleLarge = titleLarge.copy(
            fontFamily = Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
        ),
    )
}
