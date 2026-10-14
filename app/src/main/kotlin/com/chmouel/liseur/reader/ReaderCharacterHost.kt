package com.chmouel.liseur.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.characters.CharacterSheet
import com.chmouel.liseur.reader.progress.ExactLocatorAnchor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

internal typealias ReaderGo = suspend (EpubNavigatorFragment, Locator, NavigatorPositionEvent) -> Boolean

/**
 * The character panel over the book, its settings, and following a
 * source: the panel steps aside while the source is a peek, and comes
 * back with Return or Show profile.
 */
@Composable
internal fun CharacterPanelHost(
    characters: ReaderCharacters,
    navigator: EpubNavigatorFragment?,
    go: ReaderGo,
    scope: CoroutineScope,
    progress: ReaderProgressActions,
) {
    val panel by characters.panel.collectAsStateWithLifecycle()
    val peek by characters.peek.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var peeked by rememberSaveable { mutableStateOf(false) }

    // Reading on from a source while the panel is put aside closes it: the
    // reader has left the profile behind.
    LaunchedEffect(peek != null) {
        if (peek != null) {
            peeked = true
        } else if (peeked) {
            peeked = false
            if (characters.panel.value?.visible == false) characters.close()
        }
    }

    panel?.takeIf { it.visible }?.let { shown ->
        CharacterSheet(
            panel = shown,
            onSource = { passage ->
                val nav = navigator ?: return@CharacterSheet
                scope.launch {
                    // In scrolled reading the place on screen may be newer
                    // than the one saved; it is the place to come back to.
                    if (!progress.prepareCatchUp()) return@launch
                    val (_, fresh) = characters.startPeek() ?: return@launch
                    characters.hide()
                    if (!go(nav, passage.place, NavigatorPositionEvent.LOCAL_JUMP)) {
                        characters.reveal()
                        if (fresh) characters.endPeek()
                    }
                }
            },
            onRetry = characters.retry,
            onCancel = characters.cancel,
            onChoose = characters.choose,
            onSetUp = { settingsOpen = true },
            onDismiss = characters.close,
        )
    }

    if (settingsOpen) {
        // A panel waiting on a server asks by itself once one is chosen.
        val close: () -> Unit = { settingsOpen = false }
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) { characters.settings(close) }
        }
    }
}

/**
 * Goes back to where the reader asked and ends the peek only once the
 * page there is seen on screen; a Return that does not get there leaves
 * the source a peek, still saved nowhere.
 */
internal suspend fun returnFromSource(
    characters: ReaderCharacters,
    navigator: EpubNavigatorFragment,
    go: ReaderGo,
    settle: suspend () -> Unit,
) {
    val (origin, generation) = characters.returnFromPeek() ?: return
    if (!go(navigator, origin, NavigatorPositionEvent.LOCAL_JUMP)) {
        characters.returnFailed(generation)
        return
    }
    repeat(RETURN_CHECKS) {
        settle()
        val arrived = if (ExactLocatorAnchor.isExact(origin)) {
            ExactLocatorAnchor.verify(navigator, origin)
        } else {
            characters.showsPlace(origin, navigator.currentLocator.value)
        }
        if (arrived) {
            characters.reveal()
            characters.returnArrived(generation)
            return
        }
    }
    characters.returnFailed(generation)
}

private const val RETURN_CHECKS = 6
