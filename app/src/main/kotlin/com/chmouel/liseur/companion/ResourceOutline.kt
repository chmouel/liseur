package com.chmouel.liseur.companion

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Selector
import java.util.IdentityHashMap

/**
 * A resource's elements in document order, to place chapter anchors and
 * passages against each other. Parsed the way Readium's content iterator
 * parses, so the selectors it gives find the same elements here.
 */
class ResourceOutline(html: String) {
    private val document = Jsoup.parse(html)
    private val order = IdentityHashMap<Element, Int>().apply {
        document.allElements.forEachIndexed { index, element -> put(element, index) }
    }

    /** Where the element [fragment] names starts, or null when nothing or more than one thing has that name. */
    fun anchor(fragment: String): Int? {
        val byId = document.getElementsByAttributeValue("id", fragment)
        val found = byId.ifEmpty { document.getElementsByAttributeValue("name", fragment) }
        return found.singleOrNull()?.let(order::get)
    }

    /** The first and last element in document order of what [selector] names, or null unless exactly one matches. */
    fun span(selector: String): IntRange? {
        val element = try {
            document.select(selector).singleOrNull()
        } catch (e: Selector.SelectorParseException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        val start = order[element] ?: return null
        return start..(start + element.allElements.size - 1)
    }

    /** Where [anchors] sit, leaving out those that cannot be found. */
    fun anchors(anchors: Set<String>): Map<String, Int> =
        anchors.mapNotNull { fragment -> anchor(fragment)?.let { fragment to it } }.toMap()
}
