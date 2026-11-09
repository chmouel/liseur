package com.chmouel.liseur.companion

import com.chmouel.liseur.reader.annotations.NoteMarkdown

/**
 * Where a companion answer saved as a note goes: the passage selected,
 * and the mark already on it when there is one. No target makes it a
 * note on the book itself.
 */
data class NoteTarget<P>(val place: P, val annotationId: String?)

/**
 * A companion answer as text: [plain] for the clipboard, [markdown] for a
 * note, in the small Markdown notes draw: the title and sections as
 * headings, aliases in italic under the title, names in bold.
 */
data class CompanionNote(val plain: String, val markdown: String)

/** One item of a section, [name] first when it has one, as a relationship does. */
data class NoteItem(val text: String, val name: String? = null)

object CompanionNotes {
    /**
     * [title], the [aliases] under it with their heading, then each
     * section with items: its heading and one item per line. In the note
     * a section of one item is a paragraph rather than a list of one.
     */
    fun text(
        title: String,
        sections: List<Pair<String, List<NoteItem>>>,
        aliases: Pair<String, String>? = null,
    ): CompanionNote {
        val alias = aliases?.takeIf { it.second.isNotBlank() }?.let { it.first.trim() to it.second.trim() }
        val kept = sections.map { (heading, items) ->
            heading.trim() to items.map { NoteItem(it.text.trim(), it.name?.trim()?.takeIf(String::isNotEmpty)) }
                .filter { it.text.isNotEmpty() }
        }.filter { it.second.isNotEmpty() }
        val plain = buildString {
            append(title.trim())
            (listOfNotNull(alias?.let { it.first to listOf(NoteItem(it.second)) }) + kept).forEach { (heading, items) ->
                append("\n\n").append(heading)
                items.forEach { item ->
                    append("\n- ")
                    item.name?.let { append(it).append(": ") }
                    append(item.text)
                }
            }
        }
        val markdown = buildString {
            append("## ").append(escape(title.trim()))
            alias?.let { append("\n*").append(escape(it.second)).append('*') }
            kept.forEach { (heading, items) ->
                append("\n\n### ").append(escape(heading))
                val lines = items.map { item ->
                    item.name?.let { "**${escape(it)}**: ${escape(item.text)}" } ?: escape(item.text)
                }
                val paragraph = lines.singleOrNull()?.takeIf { plainLine(it) }
                if (paragraph != null) {
                    append('\n').append(paragraph)
                } else {
                    lines.forEach { append("\n- ").append(it) }
                }
            }
        }
        return CompanionNote(plain, markdown)
    }

    /** Model text with every character a note would read as formatting made literal. */
    fun escape(text: String): String = text.replace(ESCAPED) { "\\${it.value}" }

    private val ESCAPED = Regex("""[\\*_]""")

    /** A line that starts no list, quote or heading, so it can stand as a paragraph. */
    private fun plainLine(line: String): Boolean =
        '\n' !in line && NoteMarkdown.parse(line).lines.single().kind == NoteMarkdown.LineKind.TEXT

    /** The note to keep when [answer] is saved on a mark that may already hold the reader's own [existing] note. */
    fun appended(existing: String?, answer: String): String =
        existing?.trimEnd()?.takeIf { it.isNotBlank() }?.let { "$it\n\n$answer" } ?: answer

    fun <P> profile(profile: CompanionAnswer.Profile<P>, headings: ProfileHeadings): CompanionNote = text(
        profile.name,
        listOf(
            headings.identity to items(profile.identity.map { it.text }),
            headings.backstory to items(profile.backstory.map { it.text }),
            headings.relationships to profile.relationships.map { NoteItem(it.text, it.name) },
        ),
        headings.aliases to profile.aliases.joinToString(", ") { it.name },
    )

    fun <P> term(term: CompanionAnswer.Term<P>, nature: String, headings: TermHeadings): CompanionNote = text(
        "${term.name} ($nature)",
        listOf(
            headings.what to items(term.what.map { it.text }),
            headings.role to items(term.role.map { it.text }),
            headings.changes to items(term.changes.map { it.text }),
            headings.connections to term.connections.map { NoteItem(it.text, it.name) },
            headings.unknown to items(term.unknown),
        ),
        headings.aliases to term.aliases.joinToString(", ") { it.name },
    )

    fun <P> whereWasI(answer: WhereWasI<P>, title: String, headings: WhereHeadings): CompanionNote = text(
        title,
        listOf(
            headings.stopped to items(listOfNotNull(answer.stopped?.text)),
            headings.objective to items(listOfNotNull(answer.objective?.text)),
            headings.conflict to items(listOfNotNull(answer.conflict?.text)),
            headings.focus to items(listOfNotNull(answer.focus?.text)),
            headings.tone to items(listOfNotNull(answer.tone?.text)),
            headings.questions to items(answer.questions),
        ),
    )

    private fun items(texts: List<String>) = texts.map { NoteItem(it) }
}

data class ProfileHeadings(val aliases: String, val identity: String, val backstory: String, val relationships: String)

data class TermHeadings(
    val aliases: String,
    val what: String,
    val role: String,
    val changes: String,
    val connections: String,
    val unknown: String,
)

data class WhereHeadings(
    val stopped: String,
    val objective: String,
    val conflict: String,
    val focus: String,
    val tone: String,
    val questions: String,
)
