package com.chmouel.liseur.translate

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationSetupTest {
    @Test
    fun `setup picks the phone translator when available`() = runTest {
        val candidates = listOf(
            DeviceTranslationService.ID to flowOf(true),
            "mlkit" to flowOf(true),
        )
        assertEquals(DeviceTranslationService.ID, setupDefault(candidates))
    }

    @Test
    fun `setup uses ML Kit when the device translator is unavailable`() = runTest {
        val candidates = listOf(
            DeviceTranslationService.ID to flowOf(false),
            "mlkit" to flowOf(true),
        )
        assertEquals("mlkit", setupDefault(candidates))
    }

    @Test
    fun `setup never picks an unconfigured network service`() = runTest {
        assertNull(setupDefault(listOf("server" to flowOf(false))))
    }

    @Test
    fun `setup remains visible when nothing was chosen or the choice is broken`() {
        assertEquals(true, shouldOfferTranslationSetup(true, false, false))
        assertEquals(true, shouldOfferTranslationSetup(true, true, false))
        assertEquals(false, shouldOfferTranslationSetup(true, true, true))
        assertEquals(false, shouldOfferTranslationSetup(false, false, false))
    }

    @Test
    fun `dead phone translator is hidden unless currently chosen or availability is unknown`() {
        val services = listOf(DeviceTranslationService.ID, "mlkit", "gemini", "server")
        assertEquals(listOf("mlkit", "gemini", "server"), offeredServiceIds(services, null, false))
        assertEquals(services, offeredServiceIds(services, null, null))
        assertEquals(services, offeredServiceIds(services, DeviceTranslationService.ID, false))
    }
}
