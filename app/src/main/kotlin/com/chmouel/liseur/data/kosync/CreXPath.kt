package com.chmouel.liseur.data.kosync

import com.chmouel.liseur.data.bookorbit.BookOrbitEpubPackage
import com.chmouel.liseur.reader.progress.ViewportTextAnchor
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The CRe XPath a KOReader-engine reader opens a Liseur text anchor at
 * (liseur-sync ADR-0059, issue #561).
 *
 * Ported from liseur-sync's `internal/xpointer` outgoing half, which is
 * the same rule set the server translates by: text runs counted the way
 * CrossPoint counts them, one spine item per `DocFragment`, indexes
 * always spelled out. Anything that cannot be derived exactly answers
 * null, and the caller keeps sending its percentage.
 *
 * The one deliberate divergence is the offset. KOReader's engine drops an
 * ASCII whitespace character that follows another one before counting,
 * CrossPoint counts raw code points, so an offset is only emitted when no
 * such pair precedes it in the run; otherwise the path points at the start
 * of the same text node, which both engines read the same way.
 */
internal object CreXPath {

    // Parsed by BookOrbitEpubPackage's hardened reader rather than tokenized
    // again here: it keeps comments and CDATA as nodes of their own and expands
    // entities inline, which is exactly how CrossPoint splits a text run, and its
    // DTD and entity hardening is the one that gets reviewed.
    fun forAnchor(xhtml: ByteArray, fragment: Int, anchor: ViewportTextAnchor): String? = try {
        if (longestSourceRun(BookOrbitEpubPackage.decodeXmlBytes(xhtml)) >= ENGINE_TEXT_SPLIT) {
            null
        } else {
            chapter(BookOrbitEpubPackage.parseSpineXhtml(xhtml))?.let { derive(it, fragment, anchor) }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * An upper bound on the source length of any text node: text runs from
     * one piece of markup to the next, and every piece starts with `<`. A
     * CDATA section counts as text joined to its neighbours, since a `<`
     * inside it is text. UTF-16 units are never fewer than the characters
     * they encode.
     */
    private fun longestSourceRun(source: String): Int {
        var longest = 0
        var start = 0
        var i = source.indexOf('<')
        while (i >= 0) {
            if (source.startsWith(CDATA_OPEN, i)) {
                val end = source.indexOf(CDATA_CLOSE, i + CDATA_OPEN.length)
                if (end < 0) return Int.MAX_VALUE
                i = source.indexOf('<', end + CDATA_CLOSE.length)
                continue
            }
            longest = maxOf(longest, i - start)
            start = i
            i = source.indexOf('<', i + 1)
        }
        return maxOf(longest, source.length - start)
    }

    private const val CDATA_OPEN = "<![CDATA["
    private const val CDATA_CLOSE = "]]>"

    /** One text node as CrossPoint counts them: the character data between two pieces of markup. */
    private class Run(
        val text: String,
        /** 1-based among the parent's counted text nodes; a hidden run keeps 0. */
        val index: Int,
        val hidden: Boolean,
        val blank: Boolean,
        val parent: Box,
    )

    private class Item(val element: Box?, val run: Run?)

    private class Box(
        val name: String,
        val id: String,
        /** 1-based among element siblings of the same name. */
        val index: Int,
        val parent: Box?,
        val hidden: Boolean,
    ) {
        val content = ArrayList<Item>()
        val nameCounts = HashMap<String, Int>()
        var textCount = 0
    }

    private class Chapter(val body: Box, val ids: Map<String, Int>)

    // CrossPoint skips the text of these and of everything inside them; the
    // element itself still takes its place among its siblings.
    private val HIDDEN = setOf("head", "style", "script", "title", "rp")

    private fun chapter(document: Document): Chapter? {
        val root = document.documentElement ?: return null
        // Uniqueness is document-wide, as liseur-sync counts it, head included.
        val ids = HashMap<String, Int>()
        val all = document.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val id = (all.item(i) as Element).getAttribute("id")
            if (id.isNotEmpty()) ids[id] = (ids[id] ?: 0) + 1
        }
        var body: Box? = null
        var nodes = 0

        fun walk(node: Node, parent: Box?, depth: Int): Boolean {
            var child = node.firstChild
            while (child != null) {
                when (child.nodeType) {
                    Node.ELEMENT_NODE -> {
                        if (++nodes > MAX_NODES) return false
                        val element = child as Element
                        val id = element.getAttribute("id")
                        val name = element.localName ?: element.tagName
                        if (parent == null) {
                            // Only the first <body> is counted from; what
                            // follows it in the document is not read.
                            if (body == null && name.equals("body", ignoreCase = true)) {
                                val opened = Box(name, id, 1, null, false)
                                body = opened
                                if (!walk(child, opened, 0)) return false
                            }
                        } else {
                            if (depth >= MAX_DEPTH) return false
                            val opened = Box(
                                name = name,
                                id = id,
                                index = (parent.nameCounts[name] ?: 0) + 1,
                                parent = parent,
                                hidden = parent.hidden || name.lowercase() in HIDDEN,
                            )
                            parent.nameCounts[name] = opened.index
                            parent.content += Item(opened, null)
                            if (!walk(child, opened, depth + 1)) return false
                        }
                    }

                    // An empty run is no node at all; a comment or a
                    // processing instruction ends the run before it without
                    // contributing one.
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                        val text = child.nodeValue.orEmpty()
                        if (parent != null && text.isNotEmpty()) {
                            if (++nodes > MAX_NODES) return false
                            var index = 0
                            if (!parent.hidden) index = ++parent.textCount
                            parent.content += Item(
                                null,
                                Run(text, index, parent.hidden, blank(text), parent),
                            )
                        }
                    }
                }
                child = child.nextSibling
            }
            return true
        }

        if (!walk(root, null, 0)) return null
        return body?.let { Chapter(it, ids) }
    }

    /** CRe's IsEmptySpace: only these four count. */
    private fun blank(text: String): Boolean =
        text.all { it == ' ' || it == '\r' || it == '\n' || it == '\t' }

    private fun derive(chapter: Chapter, fragment: Int, anchor: ViewportTextAnchor): String? {
        if (anchor.highlight.isEmpty()) return null
        val block = lookup(chapter, anchor.cssSelector) ?: return null
        val text = blockText(block)
        val quote = anchor.before + anchor.highlight + anchor.after
        if (occurrences(text, quote) != 1) return null

        val want = text.indexOf(quote) + anchor.before.length
        val found = runAt(block, want) ?: return null
        val (run, start) = found
        if (run.hidden || run.blank) return null

        // KOReader may drop a whitespace-only text node before this one, which
        // would move its text() index. No sibling of the kind, no ambiguity.
        for (item in run.parent.content) {
            val sibling = item.run ?: continue
            if (sibling === run) break
            if (!sibling.hidden && sibling.blank) return null
        }

        val steps = ArrayList<Box>()
        var element: Box? = run.parent
        while (element != null && element !== chapter.body) {
            steps += element
            element = element.parent
        }
        if (element == null || steps.size > MAX_STEPS) return null

        val offset = run.text.codePointCount(0, want - start)
        val path = StringBuilder("/body/DocFragment[")
            .append(fragment)
            .append("]/body")
        for (i in steps.indices.reversed()) {
            path.append('/').append(steps[i].name).append('[').append(steps[i].index).append(']')
        }
        return path
            .append("/text()[").append(run.index).append("].")
            .append(if (collapseInvariant(run.text, offset)) offset else 0)
            .toString()
    }

    private fun runAt(block: Box, want: Int): Pair<Run, Int>? {
        var at = 0

        fun walk(element: Box): Pair<Run, Int>? {
            for (item in element.content) {
                val nested = item.element
                if (nested != null) {
                    walk(nested)?.let { return it }
                    continue
                }
                val run = item.run ?: continue
                if (want < at + run.text.length) return run to at
                at += run.text.length
            }
            return null
        }
        return walk(block)
    }

    /** A block's whole text, hidden words included, as the readers quote from it. */
    private fun blockText(block: Box): String {
        val out = StringBuilder()

        fun walk(element: Box) {
            for (item in element.content) {
                val nested = item.element
                if (nested != null) walk(nested) else item.run?.let { out.append(it.text) }
            }
        }
        walk(block)
        return out.toString()
    }

    private fun occurrences(text: String, quote: String): Int {
        if (quote.isEmpty()) return 0
        var found = 0
        var at = 0
        while (found < 2) {
            val next = text.indexOf(quote, at)
            if (next < 0) return found
            at = next + 1
            found++
        }
        return found
    }

    /**
     * Whether KOReader counts the same number of code points before the
     * offset as CrossPoint does. CREngine keeps a single leading space and
     * every Unicode space, and drops an ASCII whitespace character only when
     * it follows another one in the same run.
     */
    private fun collapseInvariant(text: String, offset: Int): Boolean {
        val end = text.offsetByCodePoints(0, offset)
        var space = false
        for (i in 0 until end) {
            val ascii = text[i] == ' ' || text[i] == '\t' || text[i] == '\r' || text[i] == '\n'
            if (ascii && space) return false
            space = ascii
        }
        return true
    }

    private fun lookup(chapter: Chapter, selector: String): Box? {
        val name = selector.trim()
        if (name.startsWith("#")) {
            val id = cssUnescape(name.substring(1)) ?: return null
            if (chapter.ids[id] != 1) return null
            return findById(chapter.body, id)
        }
        val parts = name.split(">")
        if (parts[0].trim() != "body") return null
        var element = chapter.body
        for (part in parts.drop(1)) {
            val step = part.trim()
            val cut = step.indexOf(":nth-of-type(")
            if (cut <= 0 || !step.endsWith(")")) return null
            val want = step.substring(cut + ":nth-of-type(".length, step.length - 1).toIntOrNull()
                ?: return null
            element = element.content.firstNotNullOfOrNull { item ->
                item.element?.takeIf { it.name == step.substring(0, cut) && it.index == want }
            } ?: return null
        }
        return element
    }

    private fun findById(block: Box, id: String): Box? {
        if (block.id == id) return block
        for (item in block.content) {
            item.element?.let { findById(it, id)?.let { found -> return found } }
        }
        return null
    }

    /** CSSOM's CSS.escape read back, the way both readers write an id. */
    private fun cssUnescape(value: String): String? {
        val out = StringBuilder()
        var at = 0
        while (at < value.length) {
            if (value[at] != '\\') {
                out.append(value[at])
                at++
                continue
            }
            at++
            if (at >= value.length) return null
            var end = at
            while (end < value.length && end - at < 6 && value[end].isHexDigit()) end++
            if (end == at) {
                val codePoint = value.codePointAt(at)
                out.appendCodePoint(codePoint)
                at += Character.charCount(codePoint)
                continue
            }
            val escaped = value.substring(at, end).toIntOrNull(16) ?: return null
            out.appendCodePoint(
                if (escaped == 0 || escaped > Character.MAX_CODE_POINT || escaped in 0xD800..0xDFFF) {
                    0xFFFD
                } else {
                    escaped
                },
            )
            at = end
            if (at < value.length && (value[at] == ' ' || value[at] == '\t' || value[at] == '\n')) {
                at++
            }
        }
        return out.toString()
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    // Ordinary chapters are a few thousand nodes and a dozen elements deep. A
    // pathological one must not turn a page turn into an unbounded tree walk.
    private const val MAX_NODES = 200_000
    private const val MAX_DEPTH = 200
    private const val MAX_STEPS = 64

    // CREngine cuts a source text node of this many characters or more into
    // several, before expanding entities, which moves text() indexes and offsets.
    private const val ENGINE_TEXT_SPLIT = 8192
}
