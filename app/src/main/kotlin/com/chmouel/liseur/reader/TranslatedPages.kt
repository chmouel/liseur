package com.chmouel.liseur.reader

import android.webkit.WebView
import com.chmouel.liseur.reader.chrome.attachedWebViews
import com.chmouel.liseur.reader.chrome.layoutPasses
import com.chmouel.liseur.reader.chrome.visibleWebView
import com.chmouel.liseur.translate.PageReader
import com.chmouel.liseur.translate.PageSentence
import com.chmouel.liseur.translate.PageSentences
import com.chmouel.liseur.translate.PageTranslation
import com.chmouel.liseur.translate.PageTranslationState
import com.chmouel.liseur.tts.BoundedSentenceTokenizer
import com.chmouel.liseur.tts.PublicationUtteranceCursor
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
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
    fun sentences(publication: Publication, place: Locator, source: String?): PageSentences {
        val cursor = PublicationUtteranceCursor(
            publication,
            place,
            BoundedSentenceTokenizer.factory(1),
            source?.let(::Language),
            overrideContentLanguage = false,
        )
        return PageSentences {
            val (text, locator) = cursor.nextLocated() ?: return@PageSentences null
            PageSentence(
                text = text.text,
                href = ResourceAddress.canonicalPath(locator.href.toString()) ?: locator.href.toString(),
                selector = locator.locations.otherLocations[CSS_SELECTOR] as? String,
                before = locator.text.before,
            )
        }
    }

    /** What names a place the walk starts from: its chapter and element. */
    fun key(place: Locator): String =
        "${ResourceAddress.canonicalPath(place.href.toString())}#${place.locations.otherLocations[CSS_SELECTOR]}"

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
     */
    suspend fun follow(nav: EpubNavigatorFragment, run: PageTranslation<Locator>, touched: MutableSet<WebView>, eInk: Boolean) {
        val root = nav.publicationView
        val id = ++follows
        val sent = WeakHashMap<WebView, Pair<String, Int>>()
        merge(nav.currentLocator.map { }, layoutPasses(root), run.swaps.map { }, run.state.map { }).collect {
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
                    sent[web] = href to held
                }
            }
            measure(nav, run)
        }
    }

    @OptIn(ExperimentalReadiumApi::class)
    private suspend fun measure(nav: EpubNavigatorFragment, run: PageTranslation<Locator>) {
        val web = visibleWebView(nav.publicationView) ?: return
        val url = web.url ?: return
        val href = ResourceAddress.canonicalPath(url) ?: return
        val shown = nav.currentLocator.value
        val origin = IntArray(2)
        // Where the view is now: a scrolled page moves before its debounced locator says so.
        fun position(): List<Int> {
            web.getLocationOnScreen(origin)
            return listOf(web.scrollX, web.scrollY, origin[0], origin[1])
        }
        val at = position()
        // The reader may have turned to another page while the view answered,
        // in this chapter too; the turn is measured again when it arrives.
        fun moved() = !stillShows(nav, web, url) || nav.currentLocator.value != shown || position() != at
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
        val answer = web.evaluate(PageSwaps.behind(url, sentence))
        return answer == "true" && stillShows(nav, web, url)
    }

    private fun stillShows(nav: EpubNavigatorFragment, web: WebView, url: String) =
        visibleWebView(nav.publicationView) === web && web.url == url

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
