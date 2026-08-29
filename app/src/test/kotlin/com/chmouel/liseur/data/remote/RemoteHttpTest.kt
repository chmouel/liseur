package com.chmouel.liseur.data.remote

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download client's read timeout, against #89: 30 seconds -- fine
 * for an API call -- was aborting book transfers the moment a modest
 * self-hosted server paused for breath, which the server then logged as
 * the client hanging up mid-write rather than as a timeout of its own.
 * [RemoteHttp.forDownloads] gives transfers longer to stall before that
 * happens, without touching the timeout every other call still uses.
 */
class RemoteHttpTest {

    @Test
    fun `download client tolerates a longer stall than the default one`() {
        val default = RemoteHttp.default()
        val downloads = RemoteHttp.forDownloads()

        assertTrue(
            "download read timeout (${downloads.readTimeoutMillis}) should exceed " +
                "the default (${default.readTimeoutMillis})",
            downloads.readTimeoutMillis > default.readTimeoutMillis,
        )
        assertEquals(
            TimeUnit.SECONDS.toMillis(90).toInt(),
            downloads.readTimeoutMillis,
        )
    }

    @Test
    fun `download client keeps the same connect timeout`() {
        assertEquals(
            RemoteHttp.default().connectTimeoutMillis,
            RemoteHttp.forDownloads().connectTimeoutMillis,
        )
    }
}
