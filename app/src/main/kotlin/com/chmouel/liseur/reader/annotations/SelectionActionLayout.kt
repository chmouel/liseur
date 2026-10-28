package com.chmouel.liseur.reader.annotations

import java.net.URLDecoder
import java.net.URLEncoder

enum class SelectionAction(val id: String, val defaultStyle: SelectionActionStyle = SelectionActionStyle.ICON) {
    NOTE("note"), LOOK_UP("look_up"), COMPANION("companion"), SEARCH("search"),
    DELETE("delete"), TRANSLATE("translate"), READ_ALOUD("read_aloud"), SHARE("share"),
    COPY("copy", SelectionActionStyle.HIDDEN), CITATION("citation", SelectionActionStyle.HIDDEN),
    OPEN_WITH("open_with", SelectionActionStyle.HIDDEN), WEB_SEARCH("web_search", SelectionActionStyle.HIDDEN),
}

enum class SelectionActionStyle(val id: String) {
    ICON("icon"), LABEL("label"), BOTH("both"), HIDDEN("hidden"),
}

data class SelectionActionEntry(val action: SelectionAction, val style: SelectionActionStyle, val customLabel: String? = null)

data class SelectionActionLayout(val entries: List<SelectionActionEntry>) {
    fun serialize(): String = entries.joinToString(",") {
        "${it.action.id}:${it.style.id}" + (it.customLabel?.let { label -> ":${URLEncoder.encode(label, "UTF-8")}" } ?: "")
    }

    fun move(action: SelectionAction, index: Int): SelectionActionLayout {
        val entry = entries.firstOrNull { it.action == action } ?: return this
        val remaining = entries.filterNot { it.action == action }.toMutableList()
        remaining.add(index.coerceIn(0, remaining.size), entry)
        return SelectionActionLayout(remaining)
    }

    fun moveBy(action: SelectionAction, offset: Int): SelectionActionLayout {
        val index = entries.indexOfFirst { it.action == action }
        return if (index < 0) this else move(action, index + offset)
    }

    fun withStyle(action: SelectionAction, style: SelectionActionStyle): SelectionActionLayout =
        SelectionActionLayout(entries.map {
            if (it.action == action) it.copy(style = validStyle(action, style)) else it
        })

    fun withLabel(action: SelectionAction, label: String?): SelectionActionLayout =
        SelectionActionLayout(entries.map {
            if (it.action == action) it.copy(customLabel = label?.trim()?.takeIf { text -> text.isNotEmpty() }) else it
        })

    companion object {
        val default = SelectionActionLayout(SelectionAction.entries.map { SelectionActionEntry(it, it.defaultStyle) })

        fun parse(value: String?): SelectionActionLayout {
            if (value == null) return default
            val result = mutableListOf<SelectionActionEntry>()
            value.split(',').forEach { part ->
                val fields = part.split(':', limit = 3)
                val action = SelectionAction.entries.firstOrNull { it.id == fields[0] } ?: return@forEach
                if (result.any { it.action == action }) return@forEach
                val style = SelectionActionStyle.entries.firstOrNull { it.id == fields.getOrNull(1) }
                    ?: action.defaultStyle
                val label = fields.getOrNull(2)?.let { encoded ->
                    runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                }
                result.add(SelectionActionEntry(action, validStyle(action, style), label))
            }
            SelectionAction.entries.forEachIndexed { index, action ->
                if (result.none { it.action == action }) {
                    val successor = SelectionAction.entries.drop(index + 1)
                        .firstOrNull { next -> result.any { it.action == next } }
                    val position = result.indexOfFirst { it.action == successor }.let { if (it < 0) result.size else it }
                    result.add(position, SelectionActionEntry(action, action.defaultStyle))
                }
            }
            return SelectionActionLayout(result)
        }

        private fun validStyle(action: SelectionAction, style: SelectionActionStyle): SelectionActionStyle =
            if (action == SelectionAction.DELETE && style == SelectionActionStyle.HIDDEN) action.defaultStyle else style
    }
}
