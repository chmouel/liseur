package com.chmouel.liseur.providers

import com.chmouel.liseur.hardcover.HardcoverBookLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpensEditionPickerTest {
    @Test
    fun `only an unmatched book that is not kept off hardcover opens the picker`() {
        assertTrue(opensEditionPicker(matched = false, excluded = false))
        assertFalse(opensEditionPicker(matched = false, excluded = true))
        assertFalse(opensEditionPicker(matched = true, excluded = false))
    }

    @Test
    fun `unmatched book hides sync switch and offers choose edition, push and keep off`() {
        val view = hardcoverBookDetailsView(null)
        assertNull(view.matchedEditionTitle)
        assertFalse(view.showSyncSwitch)
        assertNull(view.syncSwitchChecked)
        assertEquals(HardcoverAction.CHOOSE_EDITION, view.primaryAction)
        assertEquals(listOf(HardcoverAction.CHOOSE_AND_PUSH, HardcoverAction.KEEP_OFF), view.secondaryActions)
    }

    @Test
    fun `excluded unmatched book shows unchecked sync switch so it can be re-included`() {
        val view = hardcoverBookDetailsView(HardcoverBookLink(bookUrl = "calibre:1", excluded = true))
        assertNull(view.matchedEditionTitle)
        assertTrue(view.showSyncSwitch)
        assertEquals(false, view.syncSwitchChecked)
        assertNull(view.primaryAction)
        assertEquals(listOf(HardcoverAction.CHOOSE_EDITION), view.secondaryActions)
    }

    @Test
    fun `linked book with sync on shows checked switch and prominent push with secondary change edition`() {
        val view = hardcoverBookDetailsView(
            HardcoverBookLink(
                bookUrl = "calibre:1",
                hardcoverBookId = 10L,
                editionId = 20L,
                editionTitle = "The Long Way",
                excluded = false,
            ),
        )
        assertEquals("The Long Way", view.matchedEditionTitle)
        assertTrue(view.showSyncSwitch)
        assertEquals(true, view.syncSwitchChecked)
        assertEquals(HardcoverAction.PUSH, view.primaryAction)
        assertEquals(listOf(HardcoverAction.CHANGE_EDITION), view.secondaryActions)
    }

    @Test
    fun `linked book with sync off hides push and keeps change edition accessible`() {
        val view = hardcoverBookDetailsView(
            HardcoverBookLink(
                bookUrl = "calibre:1",
                hardcoverBookId = 10L,
                editionId = 20L,
                editionTitle = "The Long Way",
                excluded = true,
            ),
        )
        assertEquals("The Long Way", view.matchedEditionTitle)
        assertTrue(view.showSyncSwitch)
        assertEquals(false, view.syncSwitchChecked)
        assertNull(view.primaryAction)
        assertEquals(listOf(HardcoverAction.CHANGE_EDITION), view.secondaryActions)
    }
}
