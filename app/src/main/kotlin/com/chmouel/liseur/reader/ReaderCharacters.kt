package com.chmouel.liseur.reader

import androidx.compose.runtime.Composable
import com.chmouel.liseur.characters.Candidate
import com.chmouel.liseur.characters.CharacterPanel
import kotlinx.coroutines.flow.StateFlow
import org.readium.r2.shared.publication.Locator

/**
 * What the reader needs to ask about a character and show its sources:
 * the panel from the character lookups, and the peek at a source from the
 * reader's own view model, which keeps the asking place saved meanwhile.
 */
class ReaderCharacters(
    val panel: StateFlow<CharacterPanel<Locator>?>,
    val peek: StateFlow<SourcePeek?>,
    val open: (selection: String, place: Locator, translated: Boolean) -> Unit,
    val retry: () -> Unit,
    val cancel: () -> Unit,
    val choose: (Candidate<Locator>) -> Unit,
    val hide: () -> Unit,
    val reveal: () -> Unit,
    val close: () -> Unit,
    /** Starts a peek, or restarts one under way; the place asked from and whether it is new. */
    val startPeek: () -> Pair<Locator, Boolean>?,
    val onUserTurn: () -> Unit,
    val endPeek: () -> Unit,
    val returnFromPeek: () -> Pair<Locator, Int>?,
    val returnArrived: (Int) -> Unit,
    val returnFailed: (Int) -> Unit,
    val showsPlace: (place: Locator, here: Locator) -> Boolean,
    /** Characters' settings, opened in place over the book. */
    val settings: @Composable (onBack: () -> Unit) -> Unit,
)
