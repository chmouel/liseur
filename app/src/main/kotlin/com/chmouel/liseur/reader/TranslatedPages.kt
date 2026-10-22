package com.chmouel.liseur.reader

import android.webkit.WebView
import com.chmouel.liseur.reader.chrome.attachedWebViews
import com.chmouel.liseur.reader.chrome.layoutPasses
import com.chmouel.liseur.reader.chrome.visibleWebView
import com.chmouel.liseur.translate.BookFacts
import com.chmouel.liseur.translate.PageReader
import com.chmouel.liseur.translate.PrecedingSentences
import com.chmouel.liseur.translate.PrecedingText
import com.chmouel.liseur.translate.PageSentence
import com.chmouel.liseur.translate.PageSentences
import com.chmouel.liseur.translate.PageTranslation
import com.chmouel.liseur.translate.PageTranslationState
import com.chmouel.liseur.tts.BoundedSentenceTokenizer
import com.chmouel.liseur.tts.PublicationUtteranceCursor
import com.chmouel.liseur.tts.SpeechKey
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.Language

/**
 * The reader's half of translating the page: the book's sentences as
 * read aloud walks them, each translation put into whichever web view
 * holds its chapter, and where the reader is told back to the run.
 */
internal object TranslatedPages {
    private const val CSS_SELECTOR = "cssSelector"

    // A view set aside after it was picked may not answer until shown again; the next layout pass retries.
    private const val ANSWER_MS = 2_000L

    // Names each following of a run to the page, which starts clean for a new one.
    private var follows = 0

    /** The sentences from [place] onwards, cut as read aloud cuts them. */
    fun sentences(publication: Publication, place: Locator, source: String?): PageSentences =
        PublicationContext.sentences(publication, place, source)

    /**
     * The book's sentences before one, read from the start of its chapter
     * file the way [sentences] reads them, for the text a translation
     * takes with it.
     */
    fun preceding(publication: Publication, source: String?): PrecedingText =
        PublicationContext.preceding(publication, source)

    /**
     * What an AI translator may be told about each chapter file of
     * [publication], by the path a [PageSentence] names it with: worked
     * out once, the first time it is asked.
     */
    fun bookFacts(publication: Publication): suspend (href: String) -> BookFacts? =
        PublicationContext.bookFacts(publication)

    /** What names a place the walk starts from: its chapter and element. */
    fun key(place: Locator): String =
        "${ResourceAddress.canonicalPath(place.href.toString())}#${place.locations.otherLocations[CSS_SELECTOR]}"

    /** The selected words as the translated page currently shows them. */
    @OptIn(ExperimentalReadiumApi::class)
    suspend fun selectedText(nav: EpubNavigatorFragment): String? {
        val web = visibleWebView(nav.publicationView) ?: return null
        val url = web.url ?: return null
        val result = try {
            withTimeoutOrNull(ANSWER_MS) {
                nav.evaluateJavascript(SELECTION_TEXT_SCRIPT)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        return decodeSelectionText(result)?.takeIf { stillShows(nav, web, url) }
    }

    @OptIn(ExperimentalReadiumApi::class)
    suspend fun selectionIntersectsAppliedSwap(nav: EpubNavigatorFragment, sentence: PageSentence): Boolean? {
        val web = visibleWebView(nav.publicationView) ?: return null
        val url = web.url ?: return null
        if (ResourceAddress.canonicalPath(url) != sentence.href) return null
        val result = try {
            withTimeoutOrNull(ANSWER_MS) {
                nav.evaluateJavascript(PageSwaps.selectionIntersectsAppliedSwap(url, sentence))?.trim()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val intersects = when (result) {
            "true" -> true
            "false" -> false
            else -> return null
        }
        return intersects.takeIf { stillShows(nav, web, url) }
    }

    internal fun decodeSelectionText(result: String?): String? {
        if (result == null) return null
        val text = try {
            JSONTokener(result).nextValue() as? String
        } catch (_: JSONException) {
            null
        } ?: return null
        return text.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * [selection], made on translated words, pointed at the book's sentence
     * they translate, or null when they are not in a translated sentence.
     */
    fun original(run: PageTranslation<*>, selection: Locator): Locator? {
        val href = ResourceAddress.canonicalPath(selection.href.toString()) ?: return null
        val selected = selection.text.highlight ?: return null
        val sentence = run.original(href, selected, selection.text.before) ?: return null
        val selector = sentence.selector ?: return null
        return selection.copy(
            locations = selection.locations.copy(
                otherLocations = selection.locations.otherLocations + (CSS_SELECTOR to selector),
            ),
            text = Locator.Text(before = sentence.before, highlight = sentence.text),
        )
    }

    /**
     * Keeps every web view showing what [run] translated in its chapter,
     * and tells [run] where the reader is, until cancelled. [touched]
     * collects the views that were changed, for [restore]. On electronic
     * paper the translations land together once the run has caught up,
     * rather than one sentence at a time, each a refresh of the panel.
     * [spoken] is marked over its translation; a WebView that cannot mark
     * it puts the words to mark in [unmarked], for a Readium decoration.
     */
    suspend fun follow(
        nav: EpubNavigatorFragment,
        run: PageTranslation<Locator>,
        touched: MutableSet<WebView>,
        eInk: Boolean,
        spoken: StateFlow<PageSentence?> = MutableStateFlow(null),
        unmarked: MutableStateFlow<Pair<PageSentence, Locator.Text>?> = MutableStateFlow(null),
    ) {
        val root = nav.publicationView
        val id = ++follows
        val sent = WeakHashMap<WebView, Pair<String, Int>>()
        val marked = WeakHashMap<WebView, PageSentence?>()
        merge(nav.currentLocator.map { }, layoutPasses(root), run.swaps.map { }, run.state.map { }, spoken.map { }).collect {
            val rewritten = mutableSetOf<WebView>()
            if (!eInk || run.state.value != PageTranslationState.Translating) {
                for (web in attachedWebViews(root)) {
                    val url = web.url ?: continue
                    val href = ResourceAddress.canonicalPath(url) ?: continue
                    val swaps = run.swaps.value[href].orEmpty()
                    if (swaps.isEmpty()) {
                        // Changed by an earlier run and set aside before it could be put back.
                        if (web in touched && web.evaluate(PageSwaps.RESTORE) != null) touched -= web
                        continue
                    }
                    val from = sent[web]?.takeIf { it.first == href }?.second ?: 0
                    // Before the script goes: Stop cannot call back one already sent.
                    touched += web
                    var held = web.evaluate(PageSwaps.sync(url, id, from, swaps.drop(from)))?.toIntOrNull() ?: continue
                    // A reloaded page, or one an earlier run had changed, holds none of them.
                    if (held < from) held = web.evaluate(PageSwaps.sync(url, id, held, swaps.drop(held)))?.toIntOrNull() ?: continue
                    if (held != from) rewritten += web
                    sent[web] = href to held
                }
            }
            markSpoken(root, spoken.value, marked, rewritten, unmarked)
            measure(nav, run)
        }
    }

    /**
     * Marks the voice's sentence in the view holding its chapter, over its
     * translation, as Readium's own mark would over the book's words. A
     * view is marked again only when the sentence moved or its [rewritten]
     * words took the old mark with them, not on every layout pass.
     */
    private suspend fun markSpoken(
        root: android.view.View,
        sentence: PageSentence?,
        marked: WeakHashMap<WebView, PageSentence?>,
        rewritten: Set<WebView>,
        unmarked: MutableStateFlow<Pair<PageSentence, Locator.Text>?>,
    ) {
        if (sentence == null) unmarked.value = null
        for (web in attachedWebViews(root)) {
            val url = web.url ?: continue
            val href = ResourceAddress.canonicalPath(url) ?: continue
            val here = sentence?.takeIf { it.href == href }
            if (here == null && web !in marked) continue
            if (here != null && marked[web] == here && web !in rewritten) continue
            val answer = web.evaluate(PageSwaps.mark(url, here)) ?: continue
            if (here == null) {
                marked -= web
            } else if (answer != "false") {
                // Not found yet, as on a page still loading: asked again on the next pass.
                marked[web] = here
                unmarked.value = quoted(answer)?.let { here to it }
            }
        }
    }

    // The `{before, text}` PageSwaps answers with, as Readium's text to find; null for anything else.
    private fun quoted(answer: String): Locator.Text? {
        val shown = try {
            JSONObject(answer)
        } catch (_: JSONException) {
            return null
        }
        val words = shown.optString("text").takeIf { it.isNotBlank() } ?: return null
        return Locator.Text(before = shown.optString("before").ifEmpty { null }, highlight = words)
    }

    /** Whether the voice's sentence at [locator] is on screen in a translated page, where its translation stands. */
    @OptIn(ExperimentalReadiumApi::class)
    suspend fun isShowing(nav: EpubNavigatorFragment, locator: Locator): Boolean {
        val web = visibleWebView(nav.publicationView) ?: return false
        val url = web.url ?: return false
        val text = locator.text.highlight?.takeIf { it.isNotBlank() } ?: return false
        val sentence = SpeechKey.sentenceAt(text, locator)
        if (ResourceAddress.canonicalPath(url) != sentence.href) return false
        val answer = web.evaluate(PageSwaps.showing(url, sentence))
        return answer == "true" && stillShows(nav, web, url)
    }

    /**
     * [locator], the voice's sentence on a translated page, pointed at the
     * words shown in its place, for the page to go to them; null when no
     * view holding its chapter finds them, as when it is in another one.
     */
    @OptIn(ExperimentalReadiumApi::class)
    suspend fun shownLocator(nav: EpubNavigatorFragment, locator: Locator): Locator? {
        val text = locator.text.highlight?.takeIf { it.isNotBlank() } ?: return null
        val sentence = SpeechKey.sentenceAt(text, locator)
        val root = nav.publicationView
        // A later page of the chapter on screen is in the view on screen.
        val web = (listOfNotNull(visibleWebView(root)) + attachedWebViews(root))
            .firstOrNull { view -> view.url?.let(ResourceAddress::canonicalPath) == sentence.href } ?: return null
        val url = web.url ?: return null
        val answer = web.evaluate(PageSwaps.shownWords(url, sentence)) ?: return null
        return quoted(answer)?.let { locator.copy(text = it) }
    }

    @OptIn(ExperimentalReadiumApi::class)
    private suspend fun measure(nav: EpubNavigatorFragment, run: PageTranslation<Locator>) {
        val web = visibleWebView(nav.publicationView) ?: return
        val url = web.url ?: return
        val href = ResourceAddress.canonicalPath(url) ?: return
        val shown = nav.currentLocator.value
        val at = web.position()
        // The reader may have turned to another page while the view answered,
        // in this chapter too; the turn is measured again when it arrives.
        fun moved() = !stillShows(nav, web, url) || nav.currentLocator.value != shown || web.position() != at
        val walked = run.walkedIn(href)
        val reached = if (walked.isEmpty()) PageSwaps.UNSEEN else web.evaluate(PageSwaps.reached(url, walked))?.toIntOrNull() ?: return
        if (moved()) return
        if (reached >= 0) {
            run.onReader(PageReader.Within(walked[reached]))
            return
        }
        if (reached == PageSwaps.PAST) {
            run.onReader(PageReader.Beyond)
            return
        }
        val place = try {
            nav.firstVisibleElementLocator()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        if (moved() || ResourceAddress.canonicalPath(place.href.toString()) != href) return
        run.onReader(PageReader.Elsewhere(place, key(place), before = reached == PageSwaps.BEFORE))
    }

    /** Whether the reader has gone past [sentence] on the page shown now; false when it is not there. */
    suspend fun behind(nav: EpubNavigatorFragment, sentence: PageSentence): Boolean {
        val web = visibleWebView(nav.publicationView) ?: return false
        val url = web.url ?: return false
        if (ResourceAddress.canonicalPath(url) != sentence.href) return false
        val shown = nav.currentLocator.value
        val at = web.position()
        val answer = web.evaluate(PageSwaps.behind(url, sentence))
        // Scrolled back while the view answered, the reader may no longer be past it.
        return answer == "true" && stillShows(nav, web, url) && nav.currentLocator.value == shown && web.position() == at
    }

    private fun stillShows(nav: EpubNavigatorFragment, web: WebView, url: String) =
        visibleWebView(nav.publicationView) === web && web.url == url

    private const val SELECTION_TEXT_SCRIPT =
        "(window.getSelection ? window.getSelection().toString() : '')"

    // Where the view is now: a scrolled page moves before its debounced locator says so.
    private fun WebView.position(): List<Int> {
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        return listOf(scrollX, scrollY, origin[0], origin[1])
    }

    /**
     * Puts the book's own words back in every view in [touched], then
     * [onRestored] once every view on screen or beside it has answered.
     * Views Readium has set aside are told too, without waiting on an
     * answer they may not give until shown again, and are told once more
     * when they come back.
     */
    suspend fun restore(nav: EpubNavigatorFragment, touched: MutableSet<WebView>, onRestored: () -> Unit) {
        touched.filterNot { it.isAttachedToWindow }.forEach { it.evaluateJavascript(PageSwaps.RESTORE, null) }
        var restored = false
        suspend fun settle(): Boolean {
            restoreAttached(touched)
            if (!restored && touched.none { it.isAttachedToWindow }) {
                restored = true
                onRestored()
            }
            return touched.isNotEmpty()
        }
        if (!settle()) return
        // A view that did not answer is asked again, even when nothing is laid out meanwhile.
        val retries = flow {
            while (!restored) {
                delay(ANSWER_MS)
                emit(Unit)
            }
        }
        merge(layoutPasses(nav.publicationView), retries).takeWhile { settle() }.collect {}
    }

    private suspend fun restoreAttached(touched: MutableSet<WebView>) {
        for (web in touched.toList()) {
            if (!web.isAttachedToWindow) continue
            if (web.evaluate(PageSwaps.RESTORE) != null) touched -= web
        }
    }

    /** What [script] gives back, or null when the view does not answer in time. */
    private suspend fun WebView.evaluate(script: String): String? = withTimeoutOrNull(ANSWER_MS) {
        suspendCancellableCoroutine { continuation ->
            evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it) { _, _, _ -> } }
        }
    }
}
