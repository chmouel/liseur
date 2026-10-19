package com.chmouel.liseur.reader

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
 * wrong answer twice over — the reader loses the page they were on, and
 * the note Readium had already lifted out of the back matter is thrown away
 * unread.
 *
 * Two kinds of link arrive here.
 *
 * A **note Readium recognised** comes with its content attached, in a
 * [HyperlinkNavigator.FootnoteContext]. Nothing to fetch: pop it up.
 * Readium's own handling of that spelling is left exactly as it was.
 *
 * Any other **link into this book** goes to [LinkTaps], the same funnel the
 * page script's same-document taps use: it might be a note spelled a way
 * Readium does not know — `role="doc-noteref"`, an `<aside>`, an EPUB2
 * anchor with no type at all — or a cross-reference the reader means to
 * follow. Only the target can say which, and reading the target takes a
 * coroutine, so the answer is always "no, I'll handle it": either the note
 * pops up, or the jump that was going to happen anyway happens one frame
 * later, and with a way back.
 *
 * A **link out of the book** is not ours to follow. It goes to [onExternal],
 * which asks first — a reader who taps a footnote should never find that
 * their phone has quietly opened a browser on somebody else's server.
 */
@OptIn(ExperimentalReadiumApi::class)
internal class ReaderNavigatorListener(
    private val linkTaps: LinkTaps,
    private val onExternal: (AbsoluteUrl) -> Unit,
) : EpubNavigatorFragment.Listener {

    override fun shouldFollowInternalLink(
        link: Link,
        context: HyperlinkNavigator.LinkContext?,
    ): Boolean {
        val known = (context as? HyperlinkNavigator.FootnoteContext)?.noteContent
        if (!known.isNullOrBlank()) {
            linkTaps.fromReadiumFootnote(link, known)
            return false
        }
        linkTaps.fromReadium(link)
        return false
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        onExternal(url)
    }
}
