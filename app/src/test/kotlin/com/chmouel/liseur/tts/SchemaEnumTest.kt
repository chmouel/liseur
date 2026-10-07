package com.chmouel.liseur.tts

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SchemaEnumTest {

    private fun voices(json: String): List<String>? {
        val schema = JSONObject(json.replace("REF", "\$ref"))
        return SchemaEnum.values(schema, schema.getJSONObject("properties").getJSONObject("voice"))
    }

    @Test
    fun `any branch will do, so every branch's presets are offered`() {
        assertEquals(
            listOf("A", "B", "C"),
            voices("""{"properties":{"voice":{"anyOf":[{"enum":["A","B"]},{"type":"string"},{"enum":["B","C"]}]}}}"""),
        )
    }

    @Test
    fun `every branch must hold, so only presets they share are offered`() {
        assertEquals(
            listOf("B"),
            voices("""{"properties":{"voice":{"allOf":[{"enum":["A","B"]},{"type":"string"},{"enum":["B","C"]}]}}}"""),
        )
    }

    @Test
    fun `exactly one branch must hold, so a preset two branches share is not offered`() {
        assertEquals(
            listOf("A", "C"),
            voices("""{"properties":{"voice":{"oneOf":[{"enum":["A","B"]},{"enum":["B","C"]}]}}}"""),
        )
    }

    @Test
    fun `a free-text branch inside an intersection keeps the other side's presets`() {
        assertEquals(
            listOf("B"),
            voices("""{"properties":{"voice":{"allOf":[{"anyOf":[{"enum":["A"]},{"type":"string"}]},{"enum":["B"]}]}}}"""),
        )
    }

    @Test
    fun `a free-text voice has no presets`() {
        assertNull(voices("""{"properties":{"voice":{"anyOf":[{"type":"string"},{"type":"null"}]}}}"""))
    }

    @Test
    fun `a broken reference fails even after a good branch`() {
        try {
            voices("""{"properties":{"voice":{"anyOf":[{"enum":["A"]},{"REF":"#/definitions/Missing"}]}}}""")
            fail("expected an invalid response")
        } catch (_: SpeechError.InvalidResponse) {
        }
    }

    @Test
    fun `a null branch takes no voice name, so the presets stay`() {
        assertEquals(
            listOf("A", "B"),
            voices("""{"properties":{"voice":{"oneOf":[{"enum":["A","B"]},{"type":"null"}]}}}"""),
        )
    }

    @Test
    fun `a reference still answers to the keywords beside it`() {
        val defs = """"definitions":{"V":{"type":"string","enum":["A","B"]}}"""
        assertEquals(
            listOf("B"),
            voices("""{$defs,"properties":{"voice":{"REF":"#/definitions/V","allOf":[{"enum":["B"]}]}}}"""),
        )
        try {
            voices("""{$defs,"properties":{"voice":{"REF":"#/definitions/V","allOf":[{"REF":"#/definitions/Missing"}]}}}""")
            fail("expected an invalid response")
        } catch (_: SpeechError.InvalidResponse) {
        }
    }

    @Test
    fun `DeepInfra's array and typed-reference shapes keep their presets`() {
        val defs = """"definitions":{"V":{"type":"string","default":"A","enum":["A","B"]}}"""
        assertEquals(
            listOf("A", "B"),
            voices("""{$defs,"properties":{"voice":{"type":"array","minItems":1,"items":{"REF":"#/definitions/V"}}}}"""),
        )
        assertEquals(
            listOf("A", "B"),
            voices("""{$defs,"properties":{"voice":{"REF":"#/definitions/V","type":"string","default":"none"}}}"""),
        )
    }
}
