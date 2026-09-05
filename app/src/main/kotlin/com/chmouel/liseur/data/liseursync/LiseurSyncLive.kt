package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.LiveChanges
import com.chmouel.liseur.data.remote.LiveIdentity
import com.chmouel.liseur.data.remote.LiveRefresh
import com.chmouel.liseur.data.remote.LiveStreamFailure
import com.chmouel.liseur.data.remote.LiveTopic
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import org.json.JSONObject

internal fun liveTopics(type: String?, data: String): Set<LiveTopic> {
    if (type != "invalidate") return emptySet()
    return try {
        val array = JSONObject(data).optJSONArray("topics") ?: return emptySet()
        buildSet {
            for (i in 0 until array.length()) {
                when (array.optString(i)) {
                    "positions" -> add(LiveTopic.POSITIONS)
                    "annotations" -> add(LiveTopic.ANNOTATIONS)
                    "insights" -> add(LiveTopic.INSIGHTS)
                }
            }
        }
    } catch (_: org.json.JSONException) {
        emptySet()
    }
}

/**
 * okhttp-sse searches for a newline without a limit and accumulates all data
 * lines. Bound the raw frame before either allocation, not in onEvent.
 */
internal class BoundedEventSource(source: Source, private val limit: Long = 64 * 1024) :
    ForwardingSource(source) {
    private var frameBytes = 0L
    private var lineBytes = 0L
    private var afterCr = false

    override fun read(sink: Buffer, byteCount: Long): Long {
        val chunk = Buffer()
        val read = super.read(chunk, minOf(byteCount, 8192))
        if (read <= 0) return read
        for (index in 0 until read) {
            val byte = chunk[index].toInt()
            if (afterCr && byte == 10) {
                afterCr = false
                continue
            }

            afterCr = false
            if (++frameBytes > limit) throw IOException("Live frame exceeds $limit bytes")
            if (byte == 10 || byte == 13) {
                if (lineBytes == 0L) frameBytes = 0
                lineBytes = 0
                afterCr = byte == 13
            } else {
                lineBytes++
            }
        }

        sink.write(chunk, read)
        return read
    }
}

internal class RetryAdviceSource(
    source: Source,
    private val retryMillis: AtomicLong,
) : ForwardingSource(source) {
    private val line = StringBuilder()

    override fun read(sink: Buffer, byteCount: Long): Long {
        val chunk = Buffer()
        val read = super.read(chunk, byteCount)
        if (read <= 0) return read
        for (index in 0 until read) {
            val byte = chunk[index].toInt()
            if (byte == '\n'.code || byte == '\r'.code) {
                val value = line.toString().removePrefix("retry:").trim().toLongOrNull()
                if (line.startsWith("retry:") && value != null && value >= 0) {
                    retryMillis.set(value)
                }
                line.setLength(0)
            } else if (line.length < 32) {
                line.append(byte.toChar())
            }
        }
        sink.write(chunk, read)
        return read
    }
}

class LiseurSyncLive(
    private val refreshTopics: suspend (LiveIdentity, Set<LiveTopic>) -> LiveRefresh,
    client: OkHttpClient = RemoteHttp.default(),
    idleMillis: Long = 60_000,
) : LiveChanges {
    private val client = client.newBuilder()
        .readTimeout(idleMillis, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // Application interceptors see the decompressed body. Bounding
            // a network interceptor's gzip bytes leaves inflated frames unbounded.
            val body = response.body
            val retryMillis = chain.request().tag(AtomicLong::class.java) ?: AtomicLong(-1)
            val bounded = RetryAdviceSource(BoundedEventSource(body.source()), retryMillis).buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source(): BufferedSource = bounded
            }).build()
        }
        .build()

    override suspend fun refresh(identity: LiveIdentity, topics: Set<LiveTopic>): LiveRefresh =
        refreshTopics(identity, topics)

    override fun events(server: RemoteServer): Flow<Set<LiveTopic>> =
        stream(server.baseUrl, server.credentials)

    internal fun stream(baseUrl: String, credentials: RemoteCredentials?): Flow<Set<LiveTopic>> = flow {
        if (credentials == null) return@flow
        val wake = Channel<Unit>(Channel.CONFLATED)
        val pending = mutableSetOf<LiveTopic>()
        val retryMillis = AtomicLong(-1)
        val request = Request.Builder()
            .url(LiseurSyncApi.url(baseUrl, "/v1/events"))
            .header("Accept", "text/event-stream")
            .also { credentials.signInto(it) }
            .tag(AtomicLong::class.java, retryMillis)
            .build()
        val source = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val topics = liveTopics(type, data)
                if (topics.isEmpty()) return
                synchronized(pending) { pending.addAll(topics) }
                wake.trySend(Unit)
            }

            override fun onClosed(eventSource: EventSource) {
                wake.close(LiveStreamFailure(retryMillis = retryMillis.get().takeIf { it >= 0 }))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                wake.close(
                    LiveStreamFailure(
                        response?.code,
                        response?.header("Retry-After"),
                        retryMillis.get().takeIf { it >= 0 },
                    ),
                )
            }
        })
        try {
            for (ignored in wake) {
                val topics = synchronized(pending) { pending.toSet().also { pending.clear() } }
                if (topics.isNotEmpty()) emit(topics)
            }
        } finally {
            source.cancel()
            wake.cancel()
        }
    }
}
