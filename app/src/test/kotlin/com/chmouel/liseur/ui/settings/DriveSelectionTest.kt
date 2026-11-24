package com.chmouel.liseur.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSelectionTest {
    @Test
    fun `a connected server hides Drive until a switch is requested`() {
        assertFalse(showsLibrarySyncSection(true, false, true, true, false))
        assertTrue(showsLibrarySyncSection(true, false, true, true, true))
    }

    @Test
    fun `a requested switch is kept while the server stays connected`() {
        assertTrue(nextLibrarySyncSelected(true, true, true, false, true))
    }

    @Test
    fun `a server arriving without a request clears a stale selection`() {
        assertFalse(nextLibrarySyncSelected(true, true, false, false, true))
    }

    @Test
    fun `a disconnected server with active Drive selects it`() {
        assertTrue(nextLibrarySyncSelected(false, false, false, true, true))
    }

    @Test
    fun `builds without Drive never show it`() {
        assertFalse(showsLibrarySyncSection(false, false, false, true, true))
        assertFalse(nextLibrarySyncSelected(true, false, false, true, false))
    }

    @Test
    fun `editing an address keeps the connect form`() {
        assertFalse(showsLibrarySyncSection(false, true, true, true, false))
    }
}
