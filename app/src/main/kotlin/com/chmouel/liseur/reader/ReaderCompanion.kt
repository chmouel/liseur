package com.chmouel.liseur.reader

import androidx.compose.runtime.Composable
import com.chmouel.liseur.companion.Candidate
import com.chmouel.liseur.companion.CompanionPanel
import com.chmouel.liseur.companion.CodexViewModel
import com.chmouel.liseur.companion.StoryPanel
import kotlinx.coroutines.flow.StateFlow
import org.readium.r2.shared.publication.Locator

/**
 * What the reader needs to ask about a character and show its sources:
 * the panel from the character lookups, and the peek at a source from the
 * reader's own view model, which keeps the asking place saved meanwhile.
 */
class ReaderCompanion(
    val codex: CodexViewModel,
    val panel: StateFlow<CompanionPanel<Locator>?>,
    val peek: StateFlow<SourcePeek?>,
    val open: (selection: String, place: Locator, translated: Boolean) -> Unit,
    val retry: () -> Unit,
    val cancel: () -> Unit,
    val choose: (Candidate<Locator>) -> Unit,
    val hide: () -> Unit,
    val reveal: () -> Unit,
    val close: () -> Unit,
    /** The story so far, and whether the book has a stored recap to show in it. */
    val story: StateFlow<StoryPanel?>,
    val hasStory: StateFlow<Boolean>,
    val openStory: (place: Locator, translated: Boolean) -> Unit,
    val hideStory: () -> Unit,
    val revealStory: () -> Unit,
    val closeStory: () -> Unit,
    /** Starts a peek, or restarts one under way; the place asked from and whether it is new. */
    val startPeek: () -> Pair<Locator, Boolean>?,
    val onUserTurn: () -> Unit,
    val endPeek: () -> Unit,
    val returnFromPeek: () -> Pair<Locator, Int>?,
    val returnArrived: (Int) -> Unit,
    val returnFailed: (Int) -> Unit,
    val showsPlace: (place: Locator, here: Locator) -> Boolean,
    /** Whether this book is recapped in the background; null while the companion is not set up. */
    val recapsHere: StateFlow<Boolean?>,
    val setRecapsHere: (Boolean) -> Unit,
    /** The reading companion's settings, opened in place over the book. */
    val settings: @Composable (onBack: () -> Unit) -> Unit,
)
