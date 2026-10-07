package com.chmouel.liseur.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudControllerPolicyTest {
    private val own = 10_123

    @Test
    fun `another app's controller is turned away`() {
        assertFalse(ReadAloudControllerPolicy.accepts(10_456, own, trusted = false, notificationController = false))
    }

    @Test
    fun `this app, the platform and the notification get in`() {
        assertTrue(ReadAloudControllerPolicy.accepts(own, own, trusted = false, notificationController = false))
        assertTrue(ReadAloudControllerPolicy.accepts(1_000, own, trusted = true, notificationController = false))
        assertTrue(ReadAloudControllerPolicy.accepts(own, own, trusted = false, notificationController = true))
    }
}
