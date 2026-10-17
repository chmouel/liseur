package com.chmouel.liseur.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterPageTest {
    private fun profile(vararg names: String) = CompanionAnswer.Profile<Int>(
        name = "Mira",
        aliases = emptyList(),
        identity = emptyList(),
        backstory = emptyList(),
        relationships = names.map { Relationship(it, "about $it", emptyList()) },
    )

    @Test fun `a pair finds the relationship naming the other as whole words, the exact name first`() {
        assertEquals("Tobin", relationOf(profile("Tobin the elder", "Tobin"), "Tobin")?.name)
        assertEquals("Tobin the elder", relationOf(profile("Tobin the elder"), "Tobin")?.name)
        assertEquals("Tobin Hale", relationOf(profile("Tobin Hale"), "Captain Tobin Hale")?.name)
        assertEquals("O’Brien", relationOf(profile("O’Brien"), "o'brien")?.name)
        assertEquals("Élise", relationOf(profile("Élise"), "Elise")?.name)
        assertNull(relationOf(profile("Anna"), "Ann"))
        assertNull(relationOf(profile("Tobin"), "  "))
    }

    @Test fun `codex views follow the companion service and the on-device scan`() {
        val both = codexViews(serviceChosen = true, scanEnabled = true)
        assertEquals(listOf(CodexTab.STORY, CodexTab.NAMES, CodexTab.RELATIONSHIPS), both.tabs)
        assertEquals(listOf(CharacterPane.PROFILE, CharacterPane.MENTIONS), both.panes)
        assertEquals(CharacterPane.PROFILE, both.opening)

        val serviceOnly = codexViews(serviceChosen = true, scanEnabled = false)
        assertEquals(listOf(CodexTab.STORY), serviceOnly.tabs)
        assertEquals(listOf(CharacterPane.PROFILE), serviceOnly.panes)
        assertEquals(CharacterPane.PROFILE, serviceOnly.opening)

        val scanOnly = codexViews(serviceChosen = false, scanEnabled = true)
        assertEquals(listOf(CodexTab.NAMES, CodexTab.RELATIONSHIPS), scanOnly.tabs)
        assertEquals(listOf(CharacterPane.MENTIONS), scanOnly.panes)
        assertEquals(CharacterPane.MENTIONS, scanOnly.opening)

        // A page with nothing behind it: its profile alone, closed by Back.
        val neither = codexViews(serviceChosen = false, scanEnabled = false)
        assertEquals(emptyList<CodexTab>(), neither.tabs)
        assertEquals(listOf(CharacterPane.PROFILE), neither.panes)
        assertEquals(CharacterPane.PROFILE, neither.opening)
    }

    @Test fun `a lookup belongs to the page of the name it asked about or chose`() {
        val asked = CompanionPanel<Int>("“Tobin’s,”", PassageText.name("“Tobin’s,”"), null, CompanionView.Setup)
        assertTrue(asked.about("Tobin"))
        assertFalse(asked.about("Mira"))

        val chosen = CompanionPanel<Int>(
            "Tobin", "Tobin Hale", null,
            CompanionView.Profile(profile().copy(name = "Tobin Hale")), also = "Tobin",
        )
        assertTrue(chosen.about("Tobin"))
        assertTrue(chosen.about("Tobin Hale"))
    }

    private fun character(name: String, vararg aliases: String, facts: List<String> = emptyList(), with: Map<String, String> = emptyMap()) =
        RecapCharacter(
            name = name,
            aliases = aliases.toList(),
            facts = facts.map { RecapFact(it, emptyList()) },
            relationships = with.map { (other, text) -> RecapFact(text, emptyList(), other) },
        )

    @Test fun `recap claims come only from chapters before the scope, by whole name or alias`() {
        val saved = listOf(
            3 to ChapterRecap(emptyList(), listOf(character("Silver", "Long John", facts = listOf("Joins later.")))),
            0 to ChapterRecap(emptyList(), listOf(character("Long John Silver", "Barbecue", facts = listOf("Has one leg.")))),
            1 to ChapterRecap(emptyList(), listOf(character("Silverton", facts = listOf("Someone else.")), character("Barbecue", facts = listOf("Cooks.")))),
        )
        val scope = AskingScope(lo = 2, count = 4)

        assertEquals(listOf(0 to "Has one leg.", 1 to "Cooks."), recapClaims(saved, scope, "Barbecue’s").map { it.first to it.second.text })
        assertEquals(emptyList<Pair<Int, RecapFact>>(), recapClaims(saved, scope, "Silver"))
        assertEquals(emptyList<Pair<Int, RecapFact>>(), recapClaims(saved, scope, " "))
    }

    @Test fun `a pair keeps the relationships between the two, told from either side`() {
        val saved = listOf(
            0 to ChapterRecap(
                emptyList(),
                listOf(
                    character("Jim", facts = listOf("A boy."), with = mapOf("Long John" to "Fears him.", "Ben" to "Meets him.")),
                    character("Silver", "Long John", with = mapOf("Jim Hawkins" to "Watches him.")),
                    character("Jim Hawkins", "Jim"),
                ),
            ),
        )

        val claims = recapClaims(saved, AskingScope(lo = 1, count = 2), "Jim", "Silver").map { it.second.text }

        assertEquals(listOf("Fears him.", "Watches him."), claims)
    }

    @Test fun `relationships of the same wording toward different partners are both kept`() {
        val saved = listOf(
            0 to ChapterRecap(
                emptyList(),
                listOf(character("Jim", with = mapOf("Silver" to "Trusts him.", "Ben" to "Trusts him."))),
            ),
        )

        val claims = recapClaims(saved, AskingScope(lo = 1, count = 2), "Jim").map { it.second.with to it.second.text }

        assertEquals(listOf("Silver" to "Trusts him.", "Ben" to "Trusts him."), claims)
    }

    @Test fun `a shared title does not join two people`() {
        val saved = listOf(
            0 to ChapterRecap(emptyList(), listOf(character("Silver", "Captain", facts = listOf("Has one leg.")))),
            1 to ChapterRecap(emptyList(), listOf(character("Smollett", "Captain", facts = listOf("Commands the ship.")))),
        )
        val scope = AskingScope(lo = 2, count = 3)

        assertEquals(listOf(0 to "Has one leg."), recapClaims(saved, scope, "Silver").map { it.first to it.second.text })
        assertEquals(listOf(1 to "Commands the ship."), recapClaims(saved, scope, "Smollett").map { it.first to it.second.text })
    }

    @Test fun `a recap's sources are the cited passages whose text is unchanged`() {
        fun passage(ref: String, text: String) = Passage(ref, PassageText.hash(text), 0, text, ChapterRange(0, 0), 0)
        val kept = passage("r0.1", "Jim ran.")
        val changed = passage("r0.2", "Silver laughed.")
        val fact = RecapFact("Runs.", listOf(Recaps.stored(kept), "r0.2#stale", "r0.9#gone"))

        assertEquals(listOf(kept), recapSources(fact, listOf(kept, changed)))
    }

    @Test fun `an alias an earlier recap gives carries to chapters that use it alone, never from past the place or a second step`() {
        val saved = listOf(
            0 to ChapterRecap(emptyList(), listOf(character("Long John Silver", "Barbecue"))),
            1 to ChapterRecap(emptyList(), listOf(character("Barbecue", "the cook", facts = listOf("Cooks.")))),
            2 to ChapterRecap(emptyList(), listOf(character("the cook", facts = listOf("Someone else cooks.")))),
            3 to ChapterRecap(emptyList(), listOf(character("Long John Silver", "Ship's cook"), character("Ship's cook", facts = listOf("Later.")))),
        )

        val claims = recapClaims(saved, AskingScope(lo = 3, count = 4), "Long John Silver").map { it.second.text }

        assertEquals(listOf("Cooks."), claims)
    }
}
