package com.chmouel.liseur.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.AbsoluteUrl

/**
 * What a tap on a link in the book means.
 *
 * Without one of these the navigator is left to answer for itself, and its
 * answer to every link is the same: go there. For a footnote that is the
 * wrong answer twice over — the reader loses the page they were on, and the
 * note Readium had already lifted out of the back matter is thrown away
 * unread.
 *
 * Three kinds of link arrive here.
 *
 * A **note Readium recognised** comes with its content attached, in a
 * [HyperlinkNavigator.FootnoteContext]. Nothing to fetch: pop it up.
 *
 * A **link into this book** comes with nothing but itself. It might be a note
 * spelled a way Readium does not know — `role="doc-noteref"`, an `<aside>`,
 * an EPUB2 anchor with no type at all — or it might be a cross-reference the
 * reader means to follow. Only the target can say which, and reading the
 * target takes a coroutine, so the answer here is always "no, I'll handle
 * it": either the note pops up, or [onFollow] makes the jump that was going
 * to happen anyway. One frame later, and with a way back.
 *
 * A **link out of the book** is not ours to follow. It goes to [onExternal],
 * which asks first — a reader who taps a footnote should never find that
 * their phone has quietly opened a browser on somebody else's server.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReaderNavigatorListener(
    private val scope: CoroutineScope,
    private val noteAt: suspend (Link) -> String?,
    private val onFootnote: (html: String, link: Link) -> Unit,
    private val onFollow: (Link) -> Unit,
    private val onExternal: (AbsoluteUrl) -> Unit,
) : EpubNavigatorFragment.Listener {

    override fun shouldFollowInternalLink(
        link: Link,
        context: HyperlinkNavigator.LinkContext?,
    ): Boolean {
        val known = (context as? HyperlinkNavigator.FootnoteContext)?.noteContent
        if (!known.isNullOrBlank()) {
            onFootnote(known, link)
            return false
        }

        if (link.url().fragment.isNullOrBlank()) {
            // A whole resource is never a note; it is a chapter, and going
            // there is what was asked for.
            onFollow(link)
            return false
        }

        scope.launch {
            // A cancellation is not a failed lookup, and must not be turned
            // into one: swallowing it here would let the reader be navigated
            // by a coroutine belonging to a screen that is already going away.
            val note = try {
                noteAt(link)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An unreadable resource is not worth a message. The link
                // still goes where it said it would.
                null
            }
            if (note != null) onFootnote(note, link) else onFollow(link)
        }
        return false
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        onExternal(url)
    }
}
