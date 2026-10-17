package com.chmouel.liseur.reader

import androidx.compose.runtime.Composable
import com.chmouel.liseur.companion.Candidate
import com.chmouel.liseur.companion.CompanionPanel
import com.chmouel.liseur.companion.CodexViewModel
import com.chmouel.liseur.companion.AskingScope
import com.chmouel.liseur.companion.RecapNote
import com.chmouel.liseur.companion.SavedRecap
import com.chmouel.liseur.companion.StoryActions
import com.chmouel.liseur.companion.StoryView
import kotlinx.coroutines.flow.StateFlow
import org.readium.r2.shared.publication.Locator

/**
 * What the reader needs to ask about a character and show its sources:
 * Codex, whose character pages show the lookups' profiles, and the peek
 * at a source from the reader's own view model, which keeps the asking
 * place saved meanwhile.
 */
class ReaderCompanion(
    val codex: CodexViewModel,
    val panel: StateFlow<CompanionPanel<Locator>?>,
    val peek: StateFlow<SourcePeek?>,
    val open: (selection: String, place: Locator, translated: Boolean) -> Unit,
    /** Asks about a name on a character page, in the chapters Codex froze. */
    val ask: (name: String, scope: AskingScope?) -> Unit,
    /** What saved recaps already say of a name, or of a pair, before a scope; sends nothing. */
    val notes: suspend (name: String, other: String?, scope: AskingScope) -> List<RecapNote<Locator>>,
    /** For the pairs on the relationships map, what saved recaps last say links each; sends nothing. */
    val pairNotes: suspend (pairs: List<Pair<String, String>>, scope: AskingScope) -> Map<Pair<String, String>, String>,
    val retry: () -> Unit,
    val cancel: () -> Unit,
    val choose: (Candidate<Locator>) -> Unit,
    val close: () -> Unit,
    /** Codex's Story tab: whether it is there, what it shows while on screen, and what it can do. */
    val storyAvailable: StateFlow<Boolean>,
    /** This book's saved recaps as they change; what recap notes are read again on. */
    val recaps: StateFlow<List<SavedRecap>>,
    val story: StateFlow<StoryView?>,
    val showStory: (AskingScope) -> Unit,
    val hideStory: () -> Unit,
    val storyActions: StoryActions,
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
