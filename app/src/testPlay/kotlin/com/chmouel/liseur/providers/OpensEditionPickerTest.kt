package com.chmouel.liseur.providers

import com.chmouel.liseur.R
import com.chmouel.liseur.hardcover.HardcoverAccount
import com.chmouel.liseur.hardcover.HardcoverBookLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpensEditionPickerTest {
    private val connectedAccount = HardcoverAccount(
        userId = 42L,
        username = "reader",
        tokenCipher = "sealed:tok",
        addedAt = 1L,
    )

    @Test
    fun `only an unmatched book that is not kept off hardcover opens the picker`() {
        assertTrue(opensEditionPicker(matched = false, excluded = false))
        assertFalse(opensEditionPicker(matched = false, excluded = true))
        assertFalse(opensEditionPicker(matched = true, excluded = false))
    }

    @Test
    fun `not configured without account shows not connected subtitle and open settings action`() {
        val view = hardcoverBookDetailsView(link = null, account = null)
        assertEquals(R.string.hardcover_book_not_connected, view.subtitleRes)
        assertNull(view.matchedEditionTitle)
        assertNull(view.statusChip)
        assertFalse(view.showSyncSwitch)
        assertNull(view.syncSwitchChecked)
        assertNull(view.primaryAction)
        assertFalse(view.primaryActionEnabled)
        assertEquals(listOf(HardcoverAction.OPEN_SETTINGS), view.secondaryActions)
        assertFalse(view.showUpdateDetails)
    }

    @Test
    fun `refused token shows needs new token subtitle and open settings action`() {
        val view = hardcoverBookDetailsView(
            link = HardcoverBookLink(
                bookUrl = "calibre:1",
                hardcoverBookId = 10L,
                editionId = 20L,
                editionTitle = "The Long Way",
            ),
            account = connectedAccount.copy(tokenRefused = true),
        )
        assertEquals(R.string.hardcover_book_needs_new_token, view.subtitleRes)
        assertNull(view.matchedEditionTitle)
        assertNull(view.statusChip)
        assertFalse(view.showSyncSwitch)
        assertNull(view.syncSwitchChecked)
        assertNull(view.primaryAction)
        assertFalse(view.primaryActionEnabled)
        assertEquals(listOf(HardcoverAction.OPEN_SETTINGS), view.secondaryActions)
        assertFalse(view.showUpdateDetails)
    }

    @Test
    fun `unmatched book hides sync switch and offers choose edition, push and keep off`() {
        val view = hardcoverBookDetailsView(null, connectedAccount)
        assertEquals(R.string.hardcover_book_unmatched, view.subtitleRes)
        assertNull(view.matchedEditionTitle)
        assertEquals(HardcoverStatusChip.NOT_LINKED, view.statusChip)
        assertFalse(view.showSyncSwitch)
        assertNull(view.syncSwitchChecked)
        assertEquals(HardcoverAction.CHOOSE_EDITION, view.primaryAction)
        assertTrue(view.primaryActionEnabled)
        assertEquals(listOf(HardcoverAction.CHOOSE_AND_PUSH, HardcoverAction.KEEP_OFF), view.secondaryActions)
        assertFalse(view.showUpdateDetails)
    }

    @Test
    fun `excluded unmatched book shows unchecked sync switch and prominent choose edition`() {
        val view = hardcoverBookDetailsView(
            HardcoverBookLink(bookUrl = "calibre:1", excluded = true),
            connectedAccount,
        )
        assertEquals(R.string.hardcover_book_unmatched, view.subtitleRes)
        assertNull(view.matchedEditionTitle)
        assertEquals(HardcoverStatusChip.NOT_LINKED, view.statusChip)
        assertTrue(view.showSyncSwitch)
        assertEquals(false, view.syncSwitchChecked)
        assertEquals(HardcoverAction.CHOOSE_EDITION, view.primaryAction)
        assertTrue(view.primaryActionEnabled)
        assertTrue(view.secondaryActions.isEmpty())
        assertFalse(view.showUpdateDetails)
    }

    @Test
    fun `linked book with sync on shows linked chip, checked switch, push, update, change edition and unlink`() {
        val view = hardcoverBookDetailsView(
            HardcoverBookLink(
                bookUrl = "calibre:1",
                hardcoverBookId = 10L,
                editionId = 20L,
                editionTitle = "The Long Way",
                excluded = false,
            ),
            connectedAccount,
        )
        assertEquals(R.string.hardcover_book_matched, view.subtitleRes)
        assertEquals("The Long Way", view.matchedEditionTitle)
        assertEquals(HardcoverStatusChip.LINKED, view.statusChip)
        assertTrue(view.showSyncSwitch)
        assertEquals(true, view.syncSwitchChecked)
        assertEquals(HardcoverAction.PUSH, view.primaryAction)
        assertTrue(view.primaryActionEnabled)
        assertEquals(listOf(HardcoverAction.CHANGE_EDITION, HardcoverAction.UNLINK), view.secondaryActions)
        assertTrue(view.showUpdateDetails)
    }

    @Test
    fun `a linked edition without a title says linked instead of a bare placeholder`() {
        listOf(null, " ").forEach { title ->
            val view = hardcoverBookDetailsView(
                HardcoverBookLink(bookUrl = "calibre:1", hardcoverBookId = 10L, editionId = 20L, editionTitle = title),
                connectedAccount,
            )
            assertEquals(R.string.hardcover_book_linked, view.subtitleRes)
            assertNull(view.matchedEditionTitle)
        }
    }

    @Test
    fun `linked book with sync off disables push and keeps update, change edition and unlink`() {
        val view = hardcoverBookDetailsView(
            HardcoverBookLink(
                bookUrl = "calibre:1",
                hardcoverBookId = 10L,
                editionId = 20L,
                editionTitle = "The Long Way",
                excluded = true,
            ),
            connectedAccount,
        )
        assertEquals(R.string.hardcover_book_matched, view.subtitleRes)
        assertEquals("The Long Way", view.matchedEditionTitle)
        assertEquals(HardcoverStatusChip.LINKED, view.statusChip)
        assertTrue(view.showSyncSwitch)
        assertEquals(false, view.syncSwitchChecked)
        assertEquals(HardcoverAction.PUSH, view.primaryAction)
        assertFalse(view.primaryActionEnabled)
        assertEquals(listOf(HardcoverAction.CHANGE_EDITION, HardcoverAction.UNLINK), view.secondaryActions)
        assertTrue(view.showUpdateDetails)
    }
}
