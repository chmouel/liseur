package com.chmouel.liseur.companion

/**
 * What Codex offers with and without a companion service and the
 * on-device scan: Story only with a service, Names and Relationships only
 * with the scan. A character page has its Profile while there is a
 * service to ask or no mentions to list instead, and opens on it then.
 */
internal data class CodexViews(
    /** The tabs over the lists, in order; none leaves a character page alone, closed by Back. */
    val tabs: List<CodexTab>,
    /** A character page's panes, in order. */
    val panes: List<CharacterPane>,
    /** The pane a character page opens on. */
    val opening: CharacterPane,
)

internal fun codexViews(serviceChosen: Boolean, scanEnabled: Boolean): CodexViews {
    val profiled = serviceChosen || !scanEnabled
    return CodexViews(
        tabs = CodexTab.entries.filter { if (it == CodexTab.STORY) serviceChosen else scanEnabled },
        panes = listOfNotNull(CharacterPane.PROFILE.takeIf { profiled }, CharacterPane.MENTIONS.takeIf { scanEnabled }),
        opening = if (profiled) CharacterPane.PROFILE else CharacterPane.MENTIONS,
    )
}

/**
 * What [profile] says of its character's link to [other], a name from
 * Codex: the relationship naming them, matched as whole words either way,
 * so Tobin finds "Tobin the elder" and "Captain Tobin Hale" finds
 * "Tobin Hale". An exact name wins over a looser one.
 */
fun <P> relationOf(profile: CompanionAnswer.Profile<P>, other: String): Relationship<P>? {
    val wanted = PassageText.fold(other).takeIf { it.isNotEmpty() } ?: return null
    return profile.relationships.firstOrNull { PassageText.fold(it.name) == wanted }
        ?: profile.relationships.firstOrNull {
            PassageText.mentions(it.name, other) || PassageText.mentions(other, it.name)
        }
}

/** Codex's entry for [name], compared as passage lookup compares names. */
fun codexEntryFor(entries: List<CodexEntry>, name: String): CodexEntry? {
    val wanted = PassageText.fold(name).takeIf { it.isNotEmpty() } ?: return null
    return entries.firstOrNull { PassageText.fold(it.name) == wanted }
}

/**
 * Whether this lookup is about the character Codex calls [name]: asked
 * about it, or chosen among the characters it could mean. Both sides drop
 * a possessive or elision first, as Codex stores names.
 */
fun CompanionPanel<*>.about(name: String): Boolean {
    val wanted = characterKey(name).takeIf { it.isNotEmpty() } ?: return false
    val profile = (view as? CompanionView.Profile<*>)?.profile?.name
    return listOfNotNull(this.name, also, selection, profile).any { characterKey(it) == wanted }
}

/** A name as Codex stores it, folded: "Tobin’s" and "tobin" are one character. */
fun characterKey(name: String): String = PassageText.fold(CodexExtractor.storedName(name.trim()))

/** A claim a saved recap makes in the chapter [unit] recaps; [with] names the other side of a relationship. */
data class RecapNote<P>(val unit: Int, val text: String, val sources: List<Passage<P>>, val with: String? = null)

/**
 * What the saved recaps of chapters before [scope] say of [name]: the facts
 * and relationships of every character one of whose names is [name], or a
 * name an earlier recap gives it, chapter by chapter. On a pair's page,
 * [other] keeps only the relationships between the two, told from either
 * side. Names are whole names compared as Codex compares them, never parts
 * of one, and aliases are taken one step only, so a shared title does not
 * join two people.
 */
fun recapClaims(
    saved: List<Pair<Int, ChapterRecap>>,
    scope: AskingScope,
    name: String,
    other: String? = null,
): List<Pair<Int, RecapFact>> {
    val earlier = saved.filter { (unit, _) -> unit < scope.lo }.sortedBy { it.first }
    fun RecapCharacter.keys() = names.map(::characterKey).filter { it.isNotEmpty() }
    // The name, and every name a recap before the place gives whoever bears it,
    // minus any another character also bears as an alias of its own: a shared title joins no one.
    fun known(name: String): Set<String> {
        val key = characterKey(name).takeIf { it.isNotEmpty() } ?: return emptySet()
        val characters = earlier.flatMap { (_, recap) -> recap.characters }
        val given = characters.filter { key in it.keys() }.flatMap { it.keys() } + key
        fun shared(candidate: String) =
            characters.any { candidate in it.keys() && key !in it.keys() && characterKey(it.name) != candidate }
        return given.filterNot(::shared).toSet()
    }
    val asked = known(name).takeIf { it.isNotEmpty() } ?: return emptyList()
    val paired = other?.let(::known)?.takeIf { it.isNotEmpty() }
    fun RecapCharacter.named(keys: Set<String>) = keys().any { it in keys }
    return earlier.flatMap { (unit, recap) ->
        val self = recap.characters.filter { it.named(asked) }
        val claims = if (paired == null) {
            self.flatMap { it.facts + it.relationships }
        } else {
            val others = recap.characters.filter { it.named(paired) }
            fun toward(from: List<RecapCharacter>, to: List<RecapCharacter>, keys: Set<String>) = from.flatMap { character ->
                character.relationships.filter { fact ->
                    val with = fact.with?.let(::characterKey) ?: return@filter false
                    with in keys || to.any { with in it.keys() }
                }
            }
            toward(self, others, paired) + toward(others, self, asked)
        }
        // The same wording can link different partners; the target tells them apart.
        claims.distinctBy { it.text to it.with }.map { unit to it }
    }
}

/** The passages among [passages] that [fact] cites, only where their text is unchanged since the recap. */
fun <P> recapSources(fact: RecapFact, passages: List<Passage<P>>): List<Passage<P>> {
    val byStored = passages.associateBy(Recaps::stored)
    return fact.refs.mapNotNull { byStored[it] }.distinctBy { it.ref }
}
