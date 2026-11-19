package com.chmouel.liseur.vocabulary

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.chmouel.liseur.ui.LocalEInk

internal data class RecallColors(val accent: Color, val container: Color)

@Composable
internal fun recallColors(rating: Recall): RecallColors {
    val scheme = MaterialTheme.colorScheme
    if (LocalEInk.current) return RecallColors(scheme.onSurface, scheme.surface)
    val dark = scheme.surface.luminance() < 0.5f
    val hue = when (rating) {
        Recall.AGAIN -> if (dark) Color(0xFFFF8C94) else Color(0xFFA82435)
        Recall.HARD -> if (dark) Color(0xFFF4BE65) else Color(0xFF805000)
        Recall.GOOD -> if (dark) Color(0xFF7BDA9A) else Color(0xFF216638)
        Recall.EASY -> scheme.primary
    }
    return RecallColors(hue, lerp(scheme.surface, hue, if (dark) 0.16f else 0.10f))
}
