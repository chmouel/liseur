package com.chmouel.liseur.companion

/**
 * Where a companion answer saved as a note goes: the passage selected,
 * and the mark already on it when there is one. No target makes it a
 * note on the book itself.
 */
data class NoteTarget<P>(val place: P, val annotationId: String?)

/** A companion answer as plain text, for the clipboard and for notes. */
object CompanionNotes {
    /** [title], then each section with items: its heading and one item per line. */
    fun text(title: String, sections: List<Pair<String, List<String>>>): String = buildString {
        append(title.trim())
        sections.forEach { (heading, items) ->
            val lines = items.map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return@forEach
            append("\n\n").append(heading.trim())
            lines.forEach { append("\n- ").append(it) }
        }
    }

    /** The note to keep when [answer] is saved on a mark that may already hold the reader's own [existing] note. */
    fun appended(existing: String?, answer: String): String =
        existing?.trimEnd()?.takeIf { it.isNotBlank() }?.let { "$it\n\n$answer" } ?: answer

    fun <P> profile(profile: CompanionAnswer.Profile<P>, headings: ProfileHeadings): String = text(
        profile.name,
        listOf(
            headings.aliases to listOf(profile.aliases.joinToString(", ") { it.name }),
            headings.identity to profile.identity.map { it.text },
            headings.backstory to profile.backstory.map { it.text },
            headings.relationships to profile.relationships.map { "${it.name}: ${it.text}" },
        ),
    )

    fun <P> term(term: CompanionAnswer.Term<P>, nature: String, headings: TermHeadings): String = text(
        "${term.name} ($nature)",
        listOf(
            headings.aliases to listOf(term.aliases.joinToString(", ") { it.name }),
            headings.what to term.what.map { it.text },
            headings.role to term.role.map { it.text },
            headings.changes to term.changes.map { it.text },
            headings.connections to term.connections.map { "${it.name}: ${it.text}" },
            headings.unknown to term.unknown,
        ),
    )

    fun <P> whereWasI(answer: WhereWasI<P>, title: String, headings: WhereHeadings): String = text(
        title,
        listOf(
            headings.stopped to listOfNotNull(answer.stopped?.text),
            headings.objective to listOfNotNull(answer.objective?.text),
            headings.conflict to listOfNotNull(answer.conflict?.text),
            headings.focus to listOfNotNull(answer.focus?.text),
            headings.tone to listOfNotNull(answer.tone?.text),
            headings.questions to answer.questions,
        ),
    )
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
