package com.chmouel.liseur.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsZipBackupUiTest {
    @Test
    fun `a plain export still runs after the app was recreated behind the picker`() {
        assertEquals(
            PickedExport.RUN,
            pickedExport(pending = true, needsPassword = false, hasPassword = false, available = true),
        )
    }

    @Test
    fun `an encrypted export whose password was lost is abandoned`() {
        assertEquals(
            PickedExport.ABANDON,
            pickedExport(pending = true, needsPassword = true, hasPassword = false, available = true),
        )
    }

    @Test
    fun `an encrypted export with its password runs`() {
        assertEquals(
            PickedExport.RUN,
            pickedExport(pending = true, needsPassword = true, hasPassword = true, available = true),
        )
    }

    @Test
    fun `a picked file is discarded when no export is pending or the screen is busy or closing`() {
        assertEquals(
            PickedExport.DISCARD,
            pickedExport(pending = false, needsPassword = false, hasPassword = false, available = true),
        )
        assertEquals(
            PickedExport.DISCARD,
            pickedExport(pending = true, needsPassword = true, hasPassword = true, available = false),
        )
    }
}
