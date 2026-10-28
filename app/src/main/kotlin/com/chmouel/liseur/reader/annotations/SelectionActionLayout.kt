package com.chmouel.liseur.reader.annotations

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

enum class SelectionAction(
    val id: String,
    val defaultIcon: SelectionActionIcon,
    val defaultStyle: SelectionActionStyle = SelectionActionStyle.ICON,
) {
    NOTE("note", SelectionActionIcon.NOTE), LOOK_UP("look_up", SelectionActionIcon.BOOK),
    COMPANION("companion", SelectionActionIcon.COMPANION), SEARCH("search", SelectionActionIcon.SEARCH),
    DELETE("delete", SelectionActionIcon.DELETE), TRANSLATE("translate", SelectionActionIcon.TRANSLATE),
    READ_ALOUD("read_aloud", SelectionActionIcon.READ_ALOUD), SHARE("share", SelectionActionIcon.SHARE),
    COPY("copy", SelectionActionIcon.COPY, SelectionActionStyle.HIDDEN),
    CITATION("citation", SelectionActionIcon.QUOTE, SelectionActionStyle.HIDDEN),
    OPEN_WITH("open_with", SelectionActionIcon.OPEN_WITH, SelectionActionStyle.HIDDEN),
    WEB_SEARCH("web_search", SelectionActionIcon.GLOBE, SelectionActionStyle.HIDDEN),
    WIKIPEDIA("wikipedia", SelectionActionIcon.GLOBE, SelectionActionStyle.HIDDEN),
    OPEN_LIBRARY("open_library", SelectionActionIcon.BOOK, SelectionActionStyle.HIDDEN),
    ASK_BRAVE("ask_brave", SelectionActionIcon.BRAVE, SelectionActionStyle.HIDDEN),
}

enum class SelectionActionIcon(val id: String) {
    NOTE("note"), BOOK("book"), COMPANION("companion"), SEARCH("search"),
    DELETE("delete"), TRANSLATE("translate"), READ_ALOUD("read_aloud"), SHARE("share"),
    COPY("copy"), QUOTE("quote"), OPEN_WITH("open_with"), GLOBE("globe"),
    STAR("star"), BOOKMARK("bookmark"), HEART("heart"), CHECK("check"),
    BRAVE("brave"),
}

enum class SelectionActionStyle(val id: String) {
    ICON("icon"), LABEL("label"), BOTH("both"), HIDDEN("hidden"),
}

sealed interface SelectionActionTarget {
    val id: String

    data class BuiltIn(val action: SelectionAction) : SelectionActionTarget {
        override val id: String get() = action.id
    }

    data class Website(override val id: String, val urlTemplate: String) : SelectionActionTarget
}

data class SelectionActionEntry(
    val target: SelectionActionTarget,
    val style: SelectionActionStyle,
    val customLabel: String? = null,
    val customIcon: SelectionActionIcon? = null,
) {
    constructor(action: SelectionAction, style: SelectionActionStyle, customLabel: String? = null, customIcon: SelectionActionIcon? = null) :
        this(SelectionActionTarget.BuiltIn(action), style, customLabel, customIcon)

    val id: String get() = target.id
    val action: SelectionAction? get() = (target as? SelectionActionTarget.BuiltIn)?.action
    val defaultIcon: SelectionActionIcon get() = action?.defaultIcon ?: SelectionActionIcon.GLOBE
}

data class SelectionActionLayout(val entries: List<SelectionActionEntry>) {
    fun serialize(): String = entries.joinToString(",") {
        val label = it.customLabel?.let { label -> URLEncoder.encode(label, "UTF-8") }
        val website = it.target as? SelectionActionTarget.Website
        "${it.id}:${it.style.id}" +
            (if (label != null || it.customIcon != null || website != null) ":${label.orEmpty()}" else "") +
            (if (it.customIcon != null || website != null) ":${it.customIcon?.id.orEmpty()}" else "") +
            (website?.let { site -> ":${URLEncoder.encode(site.urlTemplate, "UTF-8")}" } ?: "")
    }

    val shown: List<SelectionActionEntry> get() = entries.filter { it.style != SelectionActionStyle.HIDDEN }
    val hidden: List<SelectionActionEntry> get() = entries.filter { it.style == SelectionActionStyle.HIDDEN }

    /** Places [id] at [index] within the toolbar or the hidden zone; Delete cannot be hidden. */
    fun moveTo(id: String, index: Int, toHidden: Boolean): SelectionActionLayout {
        val entry = entries.firstOrNull { it.id == id } ?: return this
        if (toHidden && entry.action == SelectionAction.DELETE) return this
        val moved = when {
            toHidden -> entry.copy(style = SelectionActionStyle.HIDDEN)
            entry.style == SelectionActionStyle.HIDDEN -> entry.copy(style = shownStyle(entry))
            else -> entry
        }
        val rest = SelectionActionLayout(entries.filterNot { it.id == id })
        val zone = (if (toHidden) rest.hidden else rest.shown).toMutableList()
        zone.add(index.coerceIn(0, zone.size), moved)
        return SelectionActionLayout(if (toHidden) rest.shown + zone else zone + rest.hidden)
    }

    /** A newly shown entry lands at the end of the toolbar, a newly hidden one at the top of the hidden zone. */
    fun withStyle(id: String, style: SelectionActionStyle): SelectionActionLayout =
        SelectionActionLayout(entries.map {
            if (it.id == id) it.copy(style = validStyle(it.action, style)) else it
        }).partitioned()

    fun withLabel(id: String, label: String?): SelectionActionLayout =
        SelectionActionLayout(entries.map {
            val name = label?.trim()?.takeIf { text -> text.isNotEmpty() }
            if (it.id == id && (it.action != null || name != null)) it.copy(customLabel = name) else it
        })

    fun withIcon(id: String, icon: SelectionActionIcon?): SelectionActionLayout =
        SelectionActionLayout(entries.map {
            if (it.id == id) it.copy(customIcon = icon?.takeIf { selected -> selected != it.defaultIcon }) else it
        })

    fun withWebsite(id: String, name: String, urlTemplate: String, icon: SelectionActionIcon?): SelectionActionLayout {
        val template = selectionWebsiteTemplate(urlTemplate) ?: return this
        if (!isWebsiteId(id) || name.isBlank()) return this
        val previous = entries.firstOrNull { it.id == id }
        val entry = SelectionActionEntry(SelectionActionTarget.Website(id, template),
            previous?.style ?: SelectionActionStyle.BOTH, name.trim(), icon?.takeIf { it != SelectionActionIcon.GLOBE })
        return if (previous == null) SelectionActionLayout(shown + entry + hidden)
        else SelectionActionLayout(entries.map { if (it.id == id) entry else it })
    }

    fun removeWebsite(id: String): SelectionActionLayout =
        SelectionActionLayout(entries.filterNot { it.id == id && it.target is SelectionActionTarget.Website })

    private fun partitioned(): SelectionActionLayout = SelectionActionLayout(shown + hidden)

    fun reset(): SelectionActionLayout = SelectionActionLayout(default.shown + entries
        .filter { it.target is SelectionActionTarget.Website }
        .map { it.copy(style = SelectionActionStyle.BOTH, customIcon = null) } + default.hidden)

    companion object {
        val default = SelectionActionLayout(SelectionAction.entries.map { SelectionActionEntry(it, it.defaultStyle) })

        fun parse(value: String?): SelectionActionLayout {
            if (value == null) return default
            val result = mutableListOf<SelectionActionEntry>()
            value.split(',').forEach { part ->
                val fields = part.split(':', limit = 5)
                val id = fields[0]
                if (result.any { it.id == id }) return@forEach
                val action = SelectionAction.entries.firstOrNull { it.id == id }
                val target = if (action != null) SelectionActionTarget.BuiltIn(action) else {
                    if (!isWebsiteId(id)) return@forEach
                    val template = fields.getOrNull(4)?.let(::decode)?.let(::selectionWebsiteTemplate) ?: return@forEach
                    SelectionActionTarget.Website(id, template)
                }
                val style = SelectionActionStyle.entries.firstOrNull { it.id == fields.getOrNull(1) }
                    ?: action?.defaultStyle ?: SelectionActionStyle.BOTH
                val label = fields.getOrNull(2)?.let(::decode)?.trim()?.takeIf { it.isNotEmpty() }
                if (action == null && label == null) return@forEach
                val icon = SelectionActionIcon.entries.firstOrNull {
                    it.id == fields.getOrNull(3) && it != (action?.defaultIcon ?: SelectionActionIcon.GLOBE)
                }
                result.add(SelectionActionEntry(target, validStyle(action, style), label, icon))
            }
            SelectionAction.entries.forEachIndexed { index, action ->
                if (result.none { it.action == action }) {
                    val successor = SelectionAction.entries.drop(index + 1)
                        .firstOrNull { next -> result.any { it.action == next } }
                    val position = result.indexOfFirst { it.action == successor }.let { if (it < 0) result.size else it }
                    result.add(position, SelectionActionEntry(action, action.defaultStyle))
                }
            }
            return SelectionActionLayout(result).partitioned()
        }

        private fun decode(value: String): String? = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()

        private fun isWebsiteId(id: String): Boolean = id.startsWith("custom_") &&
            runCatching { UUID.fromString(id.removePrefix("custom_")).toString() == id.removePrefix("custom_") }.getOrDefault(false)

        private fun shownStyle(entry: SelectionActionEntry): SelectionActionStyle =
            entry.action?.defaultStyle?.takeIf { it != SelectionActionStyle.HIDDEN }
                ?: if (entry.action == null) SelectionActionStyle.BOTH else SelectionActionStyle.ICON

        private fun validStyle(action: SelectionAction?, style: SelectionActionStyle): SelectionActionStyle =
            if (action == SelectionAction.DELETE && style == SelectionActionStyle.HIDDEN) SelectionAction.DELETE.defaultStyle else style
    }
}
