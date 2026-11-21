package com.chmouel.liseur.reader.progress

import com.chmouel.liseur.data.bookorbit.BookOrbitCfi
import com.chmouel.liseur.data.bookorbit.BookOrbitCfiDom
import com.chmouel.liseur.data.bookorbit.BookOrbitCfiResource
import com.chmouel.liseur.data.bookorbit.BookOrbitEpubPackage
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.reader.ResourceAddress
import com.chmouel.liseur.reader.progress.ExactLocatorAnchor.BLOCK_TAGS
import com.chmouel.liseur.reader.progress.ExactLocatorAnchor.SVG_NON_TEXT_TAGS
import org.readium.r2.shared.publication.Locator
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/** A foreign CFI's original-XHTML passage, pending verification in Readium's DOM. */
internal object BookOrbitIncomingAnchor {
    /** [progression] is how far into the resource's text the passage starts, for showing a page. */
    data class Target(val href: String, val anchor: ViewportTextAnchor, val progression: Double? = null)

    fun mayOfferOnOpen(localLocatorJson: String?, localProgression: Double?): Boolean =
        !ReadingProgress.hasSavedPlace(localLocatorJson, localProgression)

    /** A restore proposal only; the active Readium navigator must verify it after going there. */
    fun mark(base: Locator, target: Target): Locator? {
        val href = ResourceAddress.canonicalPath(base.href.toString())
        if (href == null || href != ResourceAddress.canonicalPath(target.href)) return null
        val placed = target.progression?.let {
            base.copy(locations = base.locations.copy(progression = it))
        } ?: base
        return ExactLocatorAnchor.mark(placed, target.anchor).takeIf(ExactLocatorAnchor::isExact)
    }

    fun resolve(
        raw: String,
        publication: BookOrbitEpubPackage,
        document: Document,
    ): Target? {
        val cfi = try {
            BookOrbitCfi.parse(raw)
        } catch (_: BookOrbitCfi.ParseException) {
            return null
        }
        val resource = BookOrbitCfiResource.locate(cfi, publication) ?: return null
        val position = BookOrbitCfiDom.resolve(resource, document)?.start ?: return null
        val node = position.node
        val element = when (node.nodeType) {
            Node.ELEMENT_NODE -> node as Element
            Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> node.parentNode as? Element
            else -> null
        } ?: return null
        val body = generateSequence(element) { it.parentNode as? Element }
            .firstOrNull { it.localName?.lowercase() == "body" } ?: return null
        val (textNode, offsetInNode) =
            firstTextPosition(body, node, position.offset, position.sideBias) ?: return null
        val block = generateSequence(textNode.parentNode as? Element) { it.parentNode as? Element }
            .takeWhile { it !== body }
            .firstOrNull { it.localName?.lowercase() in BLOCK_TAGS }
            ?: body
        val selector = selector(body, block) ?: return null
        val offset = textOffset(block, textNode, offsetInNode) ?: return null
        val text = block.textContent ?: return null
        val run = inlineRun(block, textNode)
        val word = Regex("[^\\s\\p{Z}]+").findAll(text.substring(run))
            .firstOrNull { it.range.last + run.first >= offset }
            ?: return null
        val wordStart = (run.first + word.range.first).let {
            if (node.nodeType == Node.ELEMENT_NODE) maxOf(it, offset) else it
        }
        val wordEnd = run.first + word.range.last + 1
        val sliceStart = if (word.value.codePointCount(0, word.value.length) <= 64) {
            wordStart
        } else {
            val rawStart = offset.coerceIn(wordStart, wordEnd - 1)
            if (rawStart > wordStart && text[rawStart].isLowSurrogate() && text[rawStart - 1].isHighSurrogate()) {
                rawStart - 1
            } else {
                rawStart
            }
        }
        val highlight = text.substring(sliceStart, wordEnd).takeCodePoints(64)
        val before = text.substring(0, sliceStart).takeLastCodePoints(32)
        val after = text.substring(sliceStart + highlight.length).takeCodePoints(32)
        val quote = before + highlight + after
        if (text.indexOf(quote) != text.lastIndexOf(quote)) return null
        val whole = body.textContent.orEmpty().length
        val progression = textOffset(body, textNode, offsetInNode)
            ?.takeIf { whole > 0 }
            ?.let { (it.toDouble() / whole).coerceIn(0.0, 1.0) }
        return Target(
            href = resource.href,
            progression = progression,
            anchor = ViewportTextAnchor(
                cssSelector = selector,
                before = before,
                highlight = highlight,
                after = after,
            ),
        )
    }

    private fun firstTextPosition(
        body: Element,
        start: Node,
        within: Int,
        sideBias: BookOrbitCfi.SideBias?,
    ): Pair<Node, Int>? {
        fun hasVisibleWord(node: Node, fromOffset: Int): Boolean {
            if (node.nodeType != Node.TEXT_NODE && node.nodeType != Node.CDATA_SECTION_NODE) return false
            val value = node.nodeValue ?: return false
            val clamped = fromOffset.coerceIn(0, value.length)
            return value.substring(clamped).any { !it.isWhitespace() }
        }

        val ignoredAncestor = generateSequence(start) { it.parentNode }
            .takeWhile { it !== body }.lastOrNull(::isIgnored)
        if (ignoredAncestor == null && hasVisibleWord(start, within)) {
            return start to within
        }

        fun nextInBody(current: Node, enterChildren: Boolean): Node? {
            if (enterChildren && !isIgnored(current) && current.firstChild != null) {
                return current.firstChild
            }
            var cursor: Node? = current
            while (cursor != null && cursor !== body) {
                cursor.nextSibling?.let { return it }
                cursor = cursor.parentNode
            }
            return null
        }

        var cursor: Node? = if (ignoredAncestor != null) {
            nextInBody(ignoredAncestor, enterChildren = false)
        } else {
            val enterStart = start.nodeType == Node.ELEMENT_NODE &&
                sideBias != BookOrbitCfi.SideBias.AFTER
            nextInBody(start, enterChildren = enterStart)
        }

        while (cursor != null) {
            if (isIgnored(cursor)) {
                cursor = nextInBody(cursor, enterChildren = false)
                continue
            }
            if (hasVisibleWord(cursor, 0)) return cursor to 0
            cursor = nextInBody(cursor, enterChildren = true)
        }
        return null
    }

    // CSS can reveal hidden content, so only intrinsically non-rendering nodes are skipped.
    private fun isIgnored(node: Node): Boolean = node is Element && when (node.namespaceURI) {
        "http://www.w3.org/2000/svg" -> node.localName?.lowercase() in SVG_NON_TEXT_TAGS
        "http://www.w3.org/1999/xhtml", null -> node.localName?.lowercase() in VOID_TAGS
        else -> false
    }

    /** Word boundaries follow blocks; offsets still include every node in WebView's textContent. */
    private fun inlineRun(block: Element, target: Node): IntRange {
        var offset = 0
        var start = 0
        var end: Int? = null
        var found = false
        fun visit(node: Node) {
            when {
                node !== block && (isIgnored(node) ||
                    node is Element && node.localName?.lowercase() in BLOCK_TAGS) -> {
                    if (found && end == null) end = offset
                    offset += node.textContent.orEmpty().length
                    if (!found) start = offset
                }
                node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE -> {
                    if (node === target) found = true
                    offset += node.nodeValue.orEmpty().length
                }
                else -> {
                    val children = node.childNodes
                    for (i in 0 until children.length) visit(children.item(i))
                }
            }
        }
        visit(block)
        return start until (end ?: offset)
    }

    private fun selector(body: Element, block: Element): String? {
        if (body === block) return "body"
        val parts = mutableListOf<String>()
        var current = block
        while (current !== body) {
            val parent = current.parentNode as? Element ?: return null
            val name = current.localName ?: return null
            var index = 1
            var sibling = current.previousSibling
            while (sibling != null) {
                if (sibling is Element && sibling.localName == name) index++
                sibling = sibling.previousSibling
            }
            parts += "$name:nth-of-type($index)"
            current = parent
        }
        return "body > " + parts.asReversed().joinToString(" > ")
    }

    private fun textOffset(block: Element, target: Node, within: Int): Int? {
        var count = 0
        fun visit(node: Node): Int? {
            if (node === target) return count + within
            if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                count += node.nodeValue.length
            } else {
                var child = node.firstChild
                while (child != null) {
                    visit(child)?.let { return it }
                    child = child.nextSibling
                }
            }
            return null
        }
        return visit(block)
    }

    private fun String.takeCodePoints(count: Int): String =
        substring(0, offsetByCodePoints(0, codePointCount(0, length).coerceAtMost(count)))

    private fun String.takeLastCodePoints(count: Int): String =
        substring(offsetByCodePoints(length, -codePointCount(0, length).coerceAtMost(count)))

    private val VOID_TAGS = setOf("br", "hr", "img")
}
