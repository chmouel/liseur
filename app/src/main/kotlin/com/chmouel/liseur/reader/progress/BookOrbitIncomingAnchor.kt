package com.chmouel.liseur.reader.progress

import com.chmouel.liseur.data.bookorbit.BookOrbitCfi
import com.chmouel.liseur.data.bookorbit.BookOrbitCfiDom
import com.chmouel.liseur.data.bookorbit.BookOrbitCfiResource
import com.chmouel.liseur.data.bookorbit.BookOrbitEpubPackage
import com.chmouel.liseur.reader.ResourceAddress
import org.readium.r2.shared.publication.Locator
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/** A foreign CFI's original-XHTML passage, pending verification in Readium's DOM. */
internal object BookOrbitIncomingAnchor {
    data class Target(val href: String, val anchor: ViewportTextAnchor)

    /** A restore proposal only; the active Readium navigator must verify it after going there. */
    fun mark(base: Locator, target: Target): Locator? {
        val href = ResourceAddress.canonicalPath(base.href.toString())
        if (href == null || href != ResourceAddress.canonicalPath(target.href)) return null
        return ExactLocatorAnchor.mark(base, target.anchor).takeIf(ExactLocatorAnchor::isExact)
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
        val block = generateSequence(element) { it.parentNode as? Element }
            .firstOrNull { it.localName?.lowercase() in BLOCK_TAGS }
            ?: return null
        val body = generateSequence(block) { it.parentNode as? Element }
            .firstOrNull { it.localName?.lowercase() == "body" } ?: return null
        val selector = selector(body, block) ?: return null
        val offset = textOffset(block, node, position.offset) ?: return null
        val text = block.textContent ?: return null
        val word = Regex("\\S+").find(text, offset) ?: return null
        val before = text.substring(0, word.range.first).takeLastCodePoints(32)
        val after = text.substring(word.range.last + 1).takeCodePoints(32)
        return Target(
            href = resource.href,
            anchor = ViewportTextAnchor(
                cssSelector = selector,
                before = before,
                highlight = word.value.takeCodePoints(64),
                after = after,
            ),
        )
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

    private val BLOCK_TAGS = setOf(
        "p", "li", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6", "div",
    )
}
