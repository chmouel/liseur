package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.bookorbit.BookOrbitCfi.Component.Step
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/** DOM-verified locations; callers must still bind the document to the selected EPUB. */
object BookOrbitCfiDom {
    data class Position(val node: Node, val offset: Int, val sideBias: BookOrbitCfi.SideBias?)
    data class Range(val start: Position, val end: Position)

    /** Capture text positions only; return nothing unless the generated CFI resolves back here. */
    fun capture(
        publication: BookOrbitEpubPackage,
        href: String,
        document: Document,
        start: Position,
        end: Position? = null,
        skipWrapper: (Element) -> Boolean = { false },
    ): String? {
        val item = publication.spine.singleOrNull { it.href == href } ?: return null
        val startPath = encodePath(document, start, skipWrapper) ?: return null
        val endPath = end?.let { encodePath(document, it, skipWrapper) ?: return null }
        val prefix = encodeStep(publication.spineStep) + encodeStep(item.step)
        val raw = if (endPath == null) {
            "epubcfi($prefix!$startPath)"
        } else {
            "epubcfi($prefix!,$startPath,$endPath)"
        }
        val parsed = BookOrbitCfi.parse(raw)
        val resource = BookOrbitCfiResource.locate(parsed, publication) ?: return null
        val resolved = resolve(resource, document, skipWrapper) ?: return null
        if (resolved.start != start || resolved.end != (end ?: start)) return null
        return raw
    }

    /**
     * [skipWrapper] flattens only caller-identified reader-injected elements.
     * Never skip an EPUB element: doing so would shift the CFI's child indices.
     */
    fun resolve(
        resource: BookOrbitCfiResource,
        document: Document,
        skipWrapper: (Element) -> Boolean = { false },
    ): Range? {
        val root = document.documentElement ?: return null
        val start = walk(root, resource.start, skipWrapper) ?: return null
        val end = resource.end?.let { walk(root, it, skipWrapper) ?: return null } ?: start
        if (start.node === end.node) {
            if (start.offset > end.offset) return null
        } else if (start.node.compareDocumentPosition(end.node).toInt() and Node.DOCUMENT_POSITION_FOLLOWING.toInt() == 0) {
            return null
        }
        return Range(start, end)
    }

    private sealed interface Slot {
        class Text(val nodes: MutableList<Node> = mutableListOf()) : Slot
        data class ElementNode(val node: Element) : Slot
    }

    private fun walk(root: Element, path: BookOrbitCfi.Path, skip: (Element) -> Boolean): Position? {
        var current = root
        for ((position, component) in path.components.withIndex()) {
            val step = component as? Step ?: return null
            if (step.index <= 0) return null
            if (position != path.components.lastIndex && step.parameters.containsKey("s")) return null
            val slot = slots(current, skip).getOrNull(step.index - 1) ?: return null
            val sideBias = when (step.parameters["s"]?.singleOrNull()) {
                null -> path.offset?.sideBias
                "a" -> BookOrbitCfi.SideBias.AFTER
                "b" -> BookOrbitCfi.SideBias.BEFORE
                else -> return null
            }
            if (path.offset?.sideBias != null && step.parameters["s"] != null &&
                path.offset.sideBias != sideBias
            ) return null
            when (slot) {
                is Slot.ElementNode -> {
                    if (step.index % 2 != 0 || (step.id != null && !hasAssertedId(slot.node, step.id))) {
                        return null
                    }
                    if (position == path.components.lastIndex) {
                        if (path.offset != null) return null
                        return Position(slot.node, 0, sideBias)
                    }
                    current = slot.node
                }
                is Slot.Text -> {
                    if (step.index % 2 == 0 || position != path.components.lastIndex ||
                        step.id != null || slot.nodes.isEmpty()
                    ) return null
                    val text = slot.nodes.joinToString("") { it.nodeValue }
                    val offset = path.offset?.character ?: 0
                    if (offset !in 0..text.length ||
                        path.offset?.textBefore?.let { !text.substring(0, offset).endsWith(it) } == true ||
                        path.offset?.textAfter?.let { !text.substring(offset).startsWith(it) } == true
                    ) return null
                    var remaining = offset
                    for (node in slot.nodes) {
                        if (remaining <= node.nodeValue.length) return Position(node, remaining, sideBias)
                        remaining -= node.nodeValue.length
                    }
                    return null
                }
            }
        }
        return null
    }

    private fun hasAssertedId(element: Element, asserted: String): Boolean {
        val id = element.getAttribute("id").takeIf { it.isNotEmpty() }
        val xmlId = element.getAttributeNS("http://www.w3.org/XML/1998/namespace", "id")
            .ifEmpty { element.getAttribute("xml:id") }.takeIf { it.isNotEmpty() }
        return (id == null || xmlId == null || id == xmlId) && (id ?: xmlId) == asserted
    }

    private fun encodePath(document: Document, position: Position, skip: (Element) -> Boolean): String? {
        val root = document.documentElement ?: return null
        if (position.node.ownerDocument !== document ||
            position.node.nodeType != Node.TEXT_NODE && position.node.nodeType != Node.CDATA_SECTION_NODE ||
            position.offset !in 0..position.node.nodeValue.length
        ) return null
        val steps = mutableListOf<String>()
        var node: Node = position.node
        while (node !== root) {
            var parent = node.parentNode as? Element ?: return null
            while (skip(parent)) parent = parent.parentNode as? Element ?: return null
            val indexed = slots(parent, skip)
            val slotIndex = indexed.indexOfFirst { slot ->
                when (slot) {
                    is Slot.Text -> slot.nodes.any { it === node }
                    is Slot.ElementNode -> slot.node === node
                }
            }
            if (slotIndex < 0) return null
            val step = when (val slot = indexed[slotIndex]) {
                is Slot.Text -> {
                    var offset = position.offset
                    for (part in slot.nodes) {
                        if (part === node) break
                        offset += part.nodeValue.length
                    }
                    val bias = position.sideBias?.let { "[;s=${it.wireValue}]" }.orEmpty()
                    "/${slotIndex + 1}:$offset$bias"
                }
                is Slot.ElementNode -> {
                    val id = slot.node.getAttribute("id").ifEmpty {
                        slot.node.getAttributeNS("http://www.w3.org/XML/1998/namespace", "id")
                            .ifEmpty { slot.node.getAttribute("xml:id") }
                    }
                    val assertion = id.takeIf { it.isNotEmpty() }?.let { "[${escape(it)}]" }.orEmpty()
                    "/${slotIndex + 1}$assertion"
                }
            }
            steps.add(step)
            node = parent
        }
        return steps.asReversed().joinToString("").takeIf { it.isNotEmpty() }
    }

    private fun encodeStep(step: Step): String =
        "/${step.index}" + (step.id?.let { "[${escape(it)}]" } ?: "")

    private fun escape(value: String): String =
        buildString {
            for (char in value) {
                if (char in "^[](),;=") append('^')
                append(char)
            }
        }

    private fun slots(parent: Element, skip: (Element) -> Boolean): List<Slot> {
        val result = mutableListOf<Slot>(Slot.Text())
        fun append(node: Node) {
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> (result.last() as Slot.Text).nodes.add(node)
                Node.ELEMENT_NODE -> {
                    val element = node as Element
                    if (skip(element)) {
                        val children = element.childNodes
                        for (i in 0 until children.length) append(children.item(i))
                    } else {
                        result.add(Slot.ElementNode(element))
                        result.add(Slot.Text())
                    }
                }
            }
        }
        val children = parent.childNodes
        for (i in 0 until children.length) append(children.item(i))
        return result
    }
}
