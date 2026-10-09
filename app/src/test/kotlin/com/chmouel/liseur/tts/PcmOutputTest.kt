package com.chmouel.liseur.tts

import android.app.Application
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class PcmOutputTest {
    @Test
    fun completedAudioKeepsItsHeardPositionUntilHalted() = runTest {
        val output = AudioTrackPcmOutput(writer = StandardTestDispatcher(testScheduler))
        val frames = 3 * SpeechAudio.SAMPLE_RATE
        val pcm = ByteArray(frames * 2)
        try {
            output.play(pcm)
            assertEquals(frames, output.halt())
            assertNull(output.halt())

            output.play(pcm, fromFrame = SpeechAudio.SAMPLE_RATE)
            assertEquals(frames, output.halt())
        } finally {
            output.release()
        }
    }
}
