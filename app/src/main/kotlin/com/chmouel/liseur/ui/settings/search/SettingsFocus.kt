package com.chmouel.liseur.ui.settings.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.navigation.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The row a search result asked for, until its page has shown it. */
object SettingsFocus {
    var key: String? by mutableStateOf(null)
}

/** Long enough for the page to slide in and an Advanced group to open. */
private const val SETTLE_MS = 400L
private const val PULSE_MS = 1_600
private const val HIGHLIGHT_ALPHA = 0.18f

/**
 * Marks a row as the place of [entries] on its page. When search opens one
 * of them, the row scrolls into view and its background lights up briefly.
 */
fun Modifier.settingsAnchor(vararg entries: SettingsEntry): Modifier = composed {
    val eInk = LocalEInk.current
    val requester = remember { BringIntoViewRequester() }
    val glow = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val color = MaterialTheme.colorScheme.primary
    val target = SettingsFocus.key
    val mine = target != null && entries.any { it.key == target }
    LaunchedEffect(mine) {
        if (!mine) return@LaunchedEffect
        delay(SETTLE_MS)
        requester.bringIntoView()
        // Clearing the key restarts this effect, so the pulse runs outside it.
        scope.launch {
            if (eInk) {
                glow.snapTo(1f)
                delay(PULSE_MS.toLong())
                glow.snapTo(0f)
            } else {
                glow.animateTo(1f, tween(PULSE_MS / 4))
                glow.animateTo(0f, tween(PULSE_MS * 3 / 4))
            }
        }
        SettingsFocus.key = null
    }
    bringIntoViewRequester(requester).drawBehind {
        val alpha = glow.value
        if (alpha > 0f) drawRect(color.copy(alpha = HIGHLIGHT_ALPHA * alpha))
    }
}

/** Opens [page]'s Advanced group when search is heading for a row in it. */
@Composable
fun ExpandForSettingsFocus(page: Route, expand: () -> Unit) {
    val target = SettingsFocus.key
    val onExpand by rememberUpdatedState(expand)
    LaunchedEffect(target) {
        if (target != null && SettingsEntries.all().any { it.key == target && it.page == page && it.advanced }) {
            onExpand()
        }
    }
}
