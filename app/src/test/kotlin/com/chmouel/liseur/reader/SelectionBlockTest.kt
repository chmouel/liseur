package com.chmouel.liseur.reader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionBlockTest {
    private fun answer(href: String, selector: String) =
        JSONObject.quote(JSONObject().put("href", href).put("selector", selector).toString())

    @Test
    fun `the selector is kept only when read from the selection's file`() {
        val result = answer("https://readium_package/OEBPS/c1.xhtml", "body > p:nth-of-type(3)")
        assertEquals("body > p:nth-of-type(3)", SelectionBlock.parse(result, "OEBPS/c1.xhtml"))
        assertNull(SelectionBlock.parse(result, "OEBPS/c2.xhtml"))
        assertNull(SelectionBlock.parse("null", "OEBPS/c1.xhtml"))
        assertNull(SelectionBlock.parse(null, "OEBPS/c1.xhtml"))
    }
    @Test
    fun `vocabulary gets surrounding text only for the active selection`() {
        val response = JSONObject()
            .put("href", "https://readium_package/OEBPS/c1.xhtml")
            .put("selector", "#passage")
            .put("text", "boat")
            .put("before", "The ")
            .put("after", " sank.")
            .toString()
        assertEquals(
            SelectionBlock.Capture("#passage", "The ", " sank."),
            SelectionBlock.parseCapture(response, "OEBPS/c1.xhtml", "boat"),
        )
        assertNull(SelectionBlock.parseCapture(response, "OEBPS/c1.xhtml", "shore"))
    }

}
