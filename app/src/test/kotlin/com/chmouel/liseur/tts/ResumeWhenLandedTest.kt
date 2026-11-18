package com.chmouel.liseur.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeWhenLandedTest {
    @Test
    fun `a pause put back resumes once it has landed on its sentence`() {
        val resume = ResumeWhenLanded()
        resume.want()
        assertTrue(resume.take(ReadAloudPlayback.Landing.Sentence))
    }

    @Test
    fun `nothing resumes when nobody asked for it`() {
        assertFalse(ResumeWhenLanded().take(ReadAloudPlayback.Landing.Sentence))
    }

    @Test
    fun `an element start or a failure never plays`() {
        for (landing in listOf(ReadAloudPlayback.Landing.ElementStart, ReadAloudPlayback.Landing.Failed)) {
            val resume = ResumeWhenLanded()
            resume.want()
            assertFalse(resume.take(landing))
        }
    }

    @Test
    fun `a pause or a stop during the alignment wins over the put-back`() {
        val paused = ResumeWhenLanded()
        paused.want()
        paused.drop()
        assertFalse(paused.take(ReadAloudPlayback.Landing.Sentence))

        val stopped = ResumeWhenLanded()
        stopped.want()
        stopped.drop()
        assertFalse(stopped.take(ReadAloudPlayback.Landing.Sentence))
    }

    @Test
    fun `the put-back is asked for once only`() {
        val resume = ResumeWhenLanded()
        resume.want()
        assertTrue(resume.take(ReadAloudPlayback.Landing.Sentence))
        assertFalse(resume.take(ReadAloudPlayback.Landing.Sentence))
    }
}
