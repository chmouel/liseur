package com.chmouel.liseur.insights

import android.content.Context
import com.chmouel.liseur.BuildConfig
import com.posthog.PersonProfiles
import com.posthog.PostHog
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogInterface
import com.posthog.internal.EndpointSpec
import com.posthog.internal.PostHogApiEndpoint
import com.posthog.internal.PostHogQueue
import com.posthog.internal.PostHogQueueInterface
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

fun createUsageReporting(context: Context): UsageReporting = PlayUsageReporting(context)

internal class PlayUsageReporting(
    context: Context,
    private val token: String = if (BuildConfig.BUILD_TYPE == "release") BuildConfig.REPORTING_PROJECT_TOKEN else BuildConfig.REPORTING_TEST_TOKEN,
    private val host: String = "https://eu.i.posthog.com",
) : UsageReporting {
    private val preference = ReportingPreference(context.noBackupFilesDir)
    @Volatile private var choice = preference.read()
    private val state = MutableStateFlow(choice.enabled)
    override val enabled = state.asStateFlow()
    private val directory = File(context.noBackupFilesDir, "usage-reporting-queue")
    @Volatile private var client: PostHogInterface? = null
    private var transport: OkHttpClient? = null
    @Volatile private var gate = AtomicBoolean(false)
    private val queues = mutableListOf<Pair<PostHogQueueInterface<PostHogEvent>, ExecutorService>>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var crashHandler: Thread.UncaughtExceptionHandler? = null
    private val crashing = AtomicBoolean(false)

    init {
        if (choice.enabled && token.isNotBlank()) startSafely() else directory.deleteRecursively()
    }

    // Lock-free: opt-out holds the monitor while it drains queues, and
    // captures come from the main thread. The SDK's beforeSend and the
    // transport recheck the gate, so a capture racing opt-out is dropped.
    override fun capture(event: UsageEvent) {
        val sending = gate
        val target = client
        val installation = choice.installation
        if (sending.get()) target?.capture(event.wireName, distinctId = installation)
    }

    override suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        synchronized(this@PlayUsageReporting) {
            if (choice.enabled == enabled) return@synchronized
            if (!enabled) gate.set(false)
            val next = ReportingChoice(enabled, UUID.randomUUID().toString())
            try {
                preference.write(next)
            } finally {
                if (!enabled) stop()
            }
            choice = next
            state.value = enabled
            if (enabled && token.isNotBlank()) startSafely()
        }
    }

    private fun startSafely() {
        try {
            start()
        } catch (_: Exception) {
            stop()
        }
    }

    @Suppress("DEPRECATION")
    private fun start() {
        // Each identity queues in its own folder, so a write that outlived a
        // previous opt-out can never be sent under the new identity.
        val storage = File(directory, choice.installation)
        directory.listFiles()?.filter { it != storage }?.forEach { it.deleteRecursively() }
        val sending = AtomicBoolean(true)
        gate = sending
        val http = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                if (!sending.get() || chain.request().url.encodedPath !in setOf("/batch/", "/batch")) {
                    throw IOException("Reporting disabled")
                }
                chain.proceed(chain.request())
            }.build()
        transport = http
        val config = PostHogConfig(
            projectToken = token,
            host = host,
            preloadFeatureFlags = false,
            remoteConfig = false,
            personProfiles = PersonProfiles.NEVER,
            setDefaultPersonProperties = false,
            sessionReplay = false,
            surveys = false,
            queueProvider = { settings, api, endpoint, prefix, executor ->
                val spec = when (endpoint) {
                    PostHogApiEndpoint.BATCH -> EndpointSpec.batch(settings, api, prefix)
                    PostHogApiEndpoint.SNAPSHOT -> EndpointSpec.snapshot(settings, api, prefix)
                }
                PostHogQueue(settings, spec, executor).also { queues += it to executor }
            },
        ).apply {
            httpClient = http
            storagePrefix = storage.absolutePath
            replayStoragePrefix = File(storage, "replay").absolutePath
            logsStoragePrefix = File(storage, "logs").absolutePath
            persistOptOut = false
            debug = false
            addBeforeSend { event ->
                if (!sending.get()) null else {
                    @Suppress("UNCHECKED_CAST")
                    val exception = event.properties?.get("\$exception_list") as? List<Map<String, Any>>
                    ReportingPayload.properties(event.event, BuildConfig.VERSION_NAME, exception)?.let {
                        PostHogEvent(event.event, choice.installation, it.toMutableMap(), event.timestamp, event.uuid)
                    }
                }
            }
        }
        client = client?.also { it.setup(config); it.optIn() } ?: PostHog.with(config)
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        crashHandler = Thread.UncaughtExceptionHandler { thread, error ->
            try {
                if (sending.get() && crashing.compareAndSet(false, true)) {
                    val worker = Thread({
                        try {
                            client?.capture("\$exception", distinctId = choice.installation,
                                properties = ReportingPayload.properties("\$exception", BuildConfig.VERSION_NAME, ReportingPayload.exception(error)))
                        } catch (_: Throwable) {
                        }
                    }, "usage-crash-persistence").apply { isDaemon = true }
                    worker.start()
                    worker.join(2_000)
                }
            } catch (_: Throwable) {
            } finally {
                previousHandler?.uncaughtException(thread, error)
            }
        }.also(Thread::setDefaultUncaughtExceptionHandler)
    }

    internal fun flush() {
        client?.flush()
    }

    @Synchronized
    internal fun stop() {
        gate.set(false)
        transport?.dispatcher?.cancelAll()
        if (Thread.getDefaultUncaughtExceptionHandler() === crashHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
        client?.optOut()
        client?.close()
        queues.forEach { (queue, executor) ->
            try {
                queue.clear()
                executor.submit {}.get(5, TimeUnit.SECONDS)
            } catch (_: Exception) {
                executor.shutdownNow()
                // The SDK would reuse this dead executor on re-enable.
                client = null
            }
        }
        queues.clear()
        directory.deleteRecursively()
        transport?.connectionPool?.evictAll()
        transport = null
        crashing.set(false)
    }
}
