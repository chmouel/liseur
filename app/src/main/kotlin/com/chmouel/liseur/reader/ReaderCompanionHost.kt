package com.chmouel.liseur.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
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
import com.chmouel.liseur.companion.CompanionSheet
import com.chmouel.liseur.companion.CodexSheet
import com.chmouel.liseur.companion.Passage
import com.chmouel.liseur.companion.StorySheet
import com.chmouel.liseur.reader.progress.ExactLocatorAnchor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

internal typealias ReaderGo = suspend (EpubNavigatorFragment, Locator, NavigatorPositionEvent) -> Boolean

/**
 * The character panel, the Codex and the story so far over the book, the
 * companion's settings, and following a source or a chapter: the panel
 * steps aside while it is a peek, and comes back with Return or Show.
 */
@Composable
internal fun CompanionPanelHost(
    companion: ReaderCompanion,
    navigator: EpubNavigatorFragment?,
    go: ReaderGo,
    scope: CoroutineScope,
    progress: ReaderProgressActions,
) {
    val panel by companion.panel.collectAsStateWithLifecycle()
    val peek by companion.peek.collectAsStateWithLifecycle()
    val codex by companion.codex.panel.collectAsStateWithLifecycle()
    val scan by companion.codex.progress.collectAsStateWithLifecycle()
    val codexEnabled by companion.codex.enabled.collectAsStateWithLifecycle()
    val story by companion.story.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var peeked by rememberSaveable { mutableStateOf(false) }

    // Reading on from a source while the panel is put aside closes it: the
    // reader has left the profile behind.
    LaunchedEffect(peek != null) {
        if (peek != null) {
            peeked = true
        } else if (peeked) {
            peeked = false
            if (companion.panel.value?.visible == false) companion.close()
            if (companion.codex.panel.value?.visible == false) companion.codex.close()
            if (companion.story.value?.visible == false) companion.closeStory()
        }
    }

    val follow: (Locator) -> Unit = follow@{ place ->
        val nav = navigator ?: return@follow
        scope.launch {
            // In scrolled reading the place on screen may be newer
            // than the one saved; it is the place to come back to.
            if (!progress.prepareCatchUp()) return@launch
            val (_, fresh) = companion.startPeek() ?: return@launch
            companion.hide()
            companion.codex.hide()
            companion.hideStory()
            if (!go(nav, place, NavigatorPositionEvent.LOCAL_JUMP)) {
                companion.reveal()
                companion.codex.reveal()
                companion.revealStory()
                if (fresh) companion.endPeek()
            }
        }
    }
    val onSource: (Passage<Locator>) -> Unit = { follow(it.place) }
    // Out here, as the sheet leaves the screen while a source is a peek:
    // the names reset with a new Codex, the passages with a new name.
    val codexNames = rememberSaveable(codex != null, saver = LazyListState.Saver) { LazyListState() }
    val codexDetail = rememberSaveable(codex?.detail?.name, codex?.detail?.other, saver = LazyListState.Saver) { LazyListState() }
    codex?.takeIf { it.visible }?.let { CodexSheet(it, scan, companion.codex, onSource, codexNames, codexDetail) }
    val storyList = rememberSaveable(story != null, saver = LazyListState.Saver) { LazyListState() }
    story?.takeIf { it.visible }?.let { StorySheet(it, onChapter = follow, onDismiss = companion.closeStory, list = storyList) }

    panel?.takeIf { it.visible }?.let { shown ->
        val ceiling = shown.scope?.lo
        val target = (shown.view as? com.chmouel.liseur.companion.CompanionView.Profile)?.profile?.name
            ?: shown.name
            ?: shown.selection
        CompanionSheet(
            panel = shown,
            onSource = onSource,
            onRetry = companion.retry,
            onCancel = companion.cancel,
            onChoose = companion.choose,
            onSetUp = { settingsOpen = true },
            onChooseModel = { settingsOpen = true },
            onCodex = if (codexEnabled && ceiling != null) {
                {
                    companion.close()
                    companion.codex.openAt(ceiling, shown.titles, target)
                }
            } else null,
            onDismiss = companion.close,
        )
    }

    if (settingsOpen) {
        // A panel waiting on a server asks by itself once one is chosen.
        val close: () -> Unit = { settingsOpen = false }
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) { companion.settings(close) }
        }
    }
}

/**
 * Goes back to where the reader asked and ends the peek only once the
 * page there is seen on screen; a Return that does not get there leaves
 * the source a peek, still saved nowhere.
 */
internal suspend fun returnFromSource(
    companion: ReaderCompanion,
    navigator: EpubNavigatorFragment,
    go: ReaderGo,
    settle: suspend () -> Unit,
) {
    val (origin, generation) = companion.returnFromPeek() ?: return
    if (!go(navigator, origin, NavigatorPositionEvent.LOCAL_JUMP)) {
        companion.returnFailed(generation)
        return
    }
    repeat(RETURN_CHECKS) {
        settle()
        val arrived = if (ExactLocatorAnchor.isExact(origin)) {
            ExactLocatorAnchor.verify(navigator, origin)
        } else {
            companion.showsPlace(origin, navigator.currentLocator.value)
        }
        if (arrived) {
            companion.reveal()
            companion.codex.reveal()
            companion.revealStory()
            companion.returnArrived(generation)
            return
        }
    }
    companion.returnFailed(generation)
}

private const val RETURN_CHECKS = 6
