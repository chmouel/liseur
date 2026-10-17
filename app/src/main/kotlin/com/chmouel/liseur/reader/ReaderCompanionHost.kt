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
import com.chmouel.liseur.companion.CharacterProfiles
import com.chmouel.liseur.companion.CodexSheet
import com.chmouel.liseur.companion.Passage
import com.chmouel.liseur.companion.CodexTab
import com.chmouel.liseur.companion.about
import com.chmouel.liseur.reader.progress.ExactLocatorAnchor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

internal typealias ReaderGo = suspend (EpubNavigatorFragment, Locator, NavigatorPositionEvent) -> Boolean

/**
 * Codex over the book, with its Story tab and the companion's character
 * profiles, the companion's settings, and following a source or a
 * chapter: the sheet steps aside while it is a peek, and comes back with
 * Return or Show.
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
    val profiles by companion.codex.profiles.collectAsStateWithLifecycle()
    val story by companion.story.collectAsStateWithLifecycle()
    val storyAvailable by companion.storyAvailable.collectAsStateWithLifecycle()
    val savedRecaps by companion.recaps.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var peeked by rememberSaveable { mutableStateOf(false) }

    // Reading on from a source while the panel is put aside closes it: the
    // reader has left the profile behind.
    LaunchedEffect(peek != null) {
        if (peek != null) {
            peeked = true
        } else if (peeked) {
            peeked = false
            if (companion.codex.panel.value?.visible == false) companion.codex.close()
        }
    }
    // A profile lives on Codex's character page; closing Codex, or leaving the page
    // it backs, stops the lookup, so choosing a service later does not send it again.
    // Read from the view models, which set the page before its lookup, not from this frame's copies.
    LaunchedEffect(codex?.detail?.name, panel) {
        val lookup = companion.panel.value ?: return@LaunchedEffect
        val shown = companion.codex.panel.value?.detail?.name
        if (shown == null || !lookup.about(shown)) companion.close()
    }

    val follow: (Locator) -> Unit = follow@{ place ->
        val nav = navigator ?: return@follow
        scope.launch {
            // In scrolled reading the place on screen may be newer
            // than the one saved; it is the place to come back to.
            if (!progress.prepareCatchUp()) return@launch
            val (_, fresh) = companion.startPeek() ?: return@launch
            companion.codex.hide()
            if (!go(nav, place, NavigatorPositionEvent.LOCAL_JUMP)) {
                companion.codex.reveal()
                if (fresh) companion.endPeek()
            }
        }
    }
    val onSource: (Passage<Locator>) -> Unit = { follow(it.place) }
    // Out here, as the sheet leaves the screen while a source is a peek:
    // the names reset with a new Codex, the passages with a new name.
    val codexNames = rememberSaveable(codex != null, saver = LazyListState.Saver) { LazyListState() }
    val codexDetail = rememberSaveable(codex?.detail?.name, codex?.detail?.other, saver = LazyListState.Saver) { LazyListState() }
    // The Story tab is shown for the place Codex was opened from, only while
    // it is the tab in front and Codex is on screen: a peek or a character
    // page over it pauses it, so nothing new starts on its own meanwhile.
    val storyScope = codex?.scope?.takeIf { codex?.storyInFront == true && storyAvailable }
    LaunchedEffect(storyScope) {
        if (storyScope != null) companion.showStory(storyScope) else companion.hideStory()
    }
    // The service went away: Story's tab goes with it, and Names takes its place.
    LaunchedEffect(storyAvailable) {
        if (!storyAvailable && companion.codex.panel.value?.tab == CodexTab.STORY) companion.codex.showNames()
    }
    val storyList = rememberSaveable(codex != null, codex?.scope, saver = LazyListState.Saver) { LazyListState() }
    codex?.takeIf { it.visible }?.let {
        CodexSheet(
            panel = it,
            progress = scan,
            controller = companion.codex,
            onSource = onSource,
            namesList = codexNames,
            detailList = codexDetail,
            storyList = storyList,
            scanEnabled = codexEnabled,
            profiles = CharacterProfiles(
                lookup = panel,
                available = profiles,
                recaps = savedRecaps,
                ask = { name -> companion.ask(name, it.scope) },
                notes = { name, other -> it.scope?.let { scope -> companion.notes(name, other, scope) }.orEmpty() },
                pairs = { pairs -> it.scope?.let { scope -> companion.pairNotes(pairs, scope) }.orEmpty() },
                retry = companion.retry,
                cancel = companion.cancel,
                choose = companion.choose,
                setUp = { settingsOpen = true },
            ),
            storyAvailable = storyAvailable,
            story = story,
            storyActions = companion.storyActions,
            onChapter = follow,
            onChooseModel = { settingsOpen = true },
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
            companion.codex.reveal()
            companion.returnArrived(generation)
            return
        }
    }
    companion.returnFailed(generation)
}

private const val RETURN_CHECKS = 6
