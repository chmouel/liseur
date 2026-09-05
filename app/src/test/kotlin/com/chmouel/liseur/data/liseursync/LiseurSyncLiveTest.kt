package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.remote.LiveRefresh
import com.chmouel.liseur.data.remote.LiveRetry
import com.chmouel.liseur.data.remote.LiveStreamFailure
import com.chmouel.liseur.data.remote.LiveTopic
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class LiseurSyncLiveTest {
    @Test
    fun `only known invalidation topics are read`() {
        assertEquals(
            setOf(LiveTopic.POSITIONS, LiveTopic.ANNOTATIONS),
            liveTopics("invalidate", """{"topics":["positions","future","annotations","positions"]}"""),
        )
        assertTrue(liveTopics("other", """{"topics":["positions"]}""").isEmpty())
        assertTrue(liveTopics("invalidate", "{").isEmpty())
        assertTrue(liveTopics("invalidate", "{}").isEmpty())
    }

    @Test
    fun `retry policy stops refusals and does not reset on short connections`() {
        val retry = LiveRetry { 0.0 }
        assertEquals(15_000L, retry.delayMillis(LiveStreamFailure()))
        assertEquals(30_000L, retry.delayMillis(LiveStreamFailure(200)))
        assertEquals(600_000L, retry.delayMillis(LiveStreamFailure(429, "600")))
        assertEquals(120_000L, retry.delayMillis(LiveStreamFailure(429, "bad")))
        for (code in listOf(401, 403, 404, 501)) {
            assertNull(retry.delayMillis(LiveStreamFailure(code)))
        }
        repeat(50) { assertTrue(retry.delayMillis(LiveStreamFailure())!! <= 300_000) }
        assertEquals(
            120_000L,
            LiveRetry { 0.0 }.delayMillis(
                LiveStreamFailure(429, "Thu, 1 Jan 1970 00:02:00 GMT"), now = 0,
            ),
        )
    }

    @Test
    fun `raw bound rejects unterminated lines and accumulated data before parser`() {
        for (frame in listOf("data: " + "a".repeat(100), "data: x\n".repeat(20), ":" + "a".repeat(100))) {
            val source = BoundedEventSource(Buffer().writeUtf8(frame), 64).buffer()
            assertThrows(IOException::class.java) { source.readUtf8() }
        }
        for (newline in listOf("\n", "\r\n", "\r")) {
            val frames = (": heartbeat$newline$newline").repeat(100)
            assertEquals(frames, BoundedEventSource(Buffer().writeUtf8(frames), 32).buffer().readUtf8())
        }
    }

    @Test
    fun `opening frame is parsed and cancellation releases call`() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getLoopbackAddress(), 0)
            server.enqueue(
                MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                    .body(": comment\r\nevent: invalidate\r\ndata: {\"topics\":[\"insights\"]}\r\n\r\n")
                    .build(),
            )
            val live = LiseurSyncLive({ _, _ -> LiveRefresh() })
            assertEquals(
                setOf(LiveTopic.INSIGHTS),
                withTimeout(5_000) { live.stream(server.url("/").toString(), TOKEN).first() },
            )
            assertEquals("/v1/events", server.takeRequest().target)
        }
    }

    @Test
    fun `silent response times out but comment heartbeats keep socket alive`() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getLoopbackAddress(), 0)
            val live = LiseurSyncLive({ _, _ -> LiveRefresh() }, idleMillis = 400)
            server.enqueue(
                MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                    .body("event: invalidate\ndata: {\"topics\":[\"positions\"]}\n\n")
                    .bodyDelay(1, TimeUnit.SECONDS).build(),
            )
            val silent = runCatching {
                withTimeout(3_000) { live.stream(server.url("/").toString(), TOKEN).first() }
            }.exceptionOrNull()
            assertTrue(silent is LiveStreamFailure)

            val heartbeat = ": ping\n\n"
            server.enqueue(
                MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                    .body(heartbeat.repeat(16) + "event: invalidate\ndata: {\"topics\":[\"positions\"]}\n\n")
                    .throttleBody(heartbeat.length.toLong(), 40, TimeUnit.MILLISECONDS).build(),
            )
            assertEquals(
                setOf(LiveTopic.POSITIONS),
                withTimeout(5_000) { live.stream(server.url("/").toString(), TOKEN).first() },
            )
        }
    }

    @Test
    fun `cancelling a silent stream cancels its network call immediately`() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getLoopbackAddress(), 0)
            server.enqueue(
                MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                    .body("data: waiting\n\n").bodyDelay(3, TimeUnit.SECONDS).build(),
            )
            val cancelled = CountDownLatch(1)
            val client = RemoteHttp.default().newBuilder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.countDown() }
            }).build()
            val live = LiseurSyncLive({ _, _ -> LiveRefresh() }, client)
            val job = launch(Dispatchers.IO) { live.stream(server.url("/").toString(), TOKEN).collect {} }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            withTimeout(1_000) { job.cancelAndJoin() }
            assertTrue(cancelled.await(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `transport rejects an oversized unterminated frame`() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getLoopbackAddress(), 0)
            server.enqueue(
                MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                    .body("data: " + "x".repeat(100_000)).build(),
            )
            val live = LiseurSyncLive({ _, _ -> LiveRefresh() })
            assertTrue(
                runCatching {
                    withTimeout(3_000) { live.stream(server.url("/").toString(), TOKEN).first() }
                }.exceptionOrNull() is LiveStreamFailure,
            )
        }
    }

    @Test
    fun `gzip expansion is bounded before parsing the event`() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getLoopbackAddress(), 0)
            val compressed = Buffer()
            GzipSink(compressed).buffer().use {
                it.writeUtf8("event: invalidate\ndata: {\"topics\":[\"positions\"],\"padding\":\"")
                it.writeUtf8("x".repeat(100_000))
                it.writeUtf8("\"}\n\n")
            }
            assertTrue(compressed.size < 1_000)
            server.enqueue(
                MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                    .addHeader("Content-Encoding", "gzip").body(compressed).build(),
            )
            val live = LiseurSyncLive({ _, _ -> LiveRefresh() })
            assertTrue(
                runCatching {
                    withTimeout(3_000) { live.stream(server.url("/").toString(), TOKEN).first() }
                }.exceptionOrNull() is LiveStreamFailure,
            )
        }
    }

    private companion object {
        val TOKEN = RemoteCredentials.Bearer("test-only")
    }
}
