package com.chmouel.liseur.ui.theme

import android.content.res.AssetManager
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The bundled Literata, the same face the reader offers, so the chrome
 * around a book is set in the type of one. The platform serif it
 * replaces looked like a fallback because it was one.
 */
fun literataFamily(assets: AssetManager): FontFamily = FontFamily(
    Font("fonts/Literata.ttf", assets),
    Font("fonts/Literata-Italic.ttf", assets, style = FontStyle.Italic),
)

fun liseurTypography(serif: FontFamily): Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = serif),
        displayMedium = displayMedium.copy(fontFamily = serif),
        displaySmall = displaySmall.copy(fontFamily = serif),
        headlineLarge = headlineLarge.copy(fontFamily = serif),
        headlineMedium = headlineMedium.copy(fontFamily = serif),
        headlineSmall = headlineSmall.copy(fontFamily = serif),
        titleLarge = titleLarge.copy(
            fontFamily = serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
        ),
        titleMedium = titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        titleSmall = titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 18.sp,
        ),
    )
}

/** The platform-serif fallback, for previews and tests without assets. */
val LiseurTypography = liseurTypography(FontFamily.Serif)
