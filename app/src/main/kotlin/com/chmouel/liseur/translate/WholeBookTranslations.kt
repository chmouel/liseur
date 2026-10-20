package com.chmouel.liseur.translate

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.chmouel.liseur.AppContainer
import com.chmouel.liseur.data.library.openableUri
import com.chmouel.liseur.reader.TranslatedPages
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication

internal class WholeBookTranslations(
    private val context: Context,
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val openPublication: suspend (String, suspend (Publication) -> Unit) -> Unit =
        { bookUrl, work -> container.withTranslationPublication(bookUrl, work) },
) {
    private val saved: SavedTranslations get() = container.savedTranslations
    private val lock = Any()
    private var active: String? = null
    private var pending: WholeBookPreview? = null
    private var approved: WholeBookPreview? = null
    private var pausePersistence: Job? = null
    private var cancel: (() -> Unit)? = null
    private var stopped = false
    private var stopReason = "interrupted"
    private val initialized = scope.async { saved.reconcileRunning(emptySet()) }
    internal data class StartFailure(val bookUrl: String, val error: Exception)
    private val _startFailures = MutableSharedFlow<StartFailure>(extraBufferCapacity = 1)
    val startFailures = _startFailures.asSharedFlow()

    val records: Flow<List<KeptTranslation>> = flow {
        initialized.await()
        emitAll(saved.kept)
    }

    suspend fun inspect(bookUrl: String): WholeBookInfo {
        initialized.await()
        val book = container.database.bookDao().getByUrl(bookUrl) ?: throw IOException("Book is not in the library")
        val record = saved.getKept(bookUrl)
        val settings = container.appSettings.settings.first()
        val target = TranslationLanguages.target(settings.translationTarget, Locale.getDefault())
        return container.withTranslationPublication(bookUrl) { publication ->
            val source = record?.source ?: TranslationLanguages.source(publication.metadata.languages)
            val identity = try {
                (container.translate as? ServiceTranslate)?.wholeBookIdentity(source, target)
            } catch (e: TranslationError.NotSetUp) {
                null
            }
            WholeBookInfo(book.title, source, target, identity != null, record?.let { it.identity == identity })
        }
    }

    suspend fun preview(bookUrl: String, retryFailures: Boolean = false): WholeBookPreview {
        initialized.await()
        val record = saved.kept.first().firstOrNull { it.bookUrl == bookUrl }
        return container.withTranslationPublication(bookUrl) { publication ->
            val settings = container.appSettings.settings.first()
            preview(
                bookUrl,
                record?.source ?: TranslationLanguages.source(publication.metadata.languages),
                record?.target ?: TranslationLanguages.target(settings.translationTarget, Locale.getDefault()),
                retryFailures,
            )
        }
    }

    suspend fun preview(bookUrl: String, source: String?, target: String, retryFailures: Boolean = false): WholeBookPreview = coroutineScope {
        initialized.await()
        val feature = container.translate as? ServiceTranslate ?: throw TranslationError.NotSetUp()
        val book = container.database.bookDao().getByUrl(bookUrl) ?: throw IOException("Book is not in the library")
        val fingerprint = fingerprint(bookUrl)
        val retentionStamp = saved.retentionStamp(bookUrl)
        val translator = feature.openPinned(source, target)
        try {
            val price = async { feature.wholeBookPrice() }
            val identity = translator.answering()
            val requestCharacters = feature.wholeBookPrompt(source, target)
            val coverage = container.withTranslationPublication(bookUrl) { publication ->
                val requests = SentenceRequests(translator, TranslatedPages.preceding(publication, source), TranslatedPages.bookFacts(publication))
                WholeBookTranslation(translator, requests, store(bookUrl), requestCharacters = requestCharacters)
                    .scan(publication.translationSentences(source), retryFailures)
            }
            if (fingerprint(bookUrl) != fingerprint) throw IOException("The book changed while scanning")
            if (translator.answering() != identity) throw TranslationError.Changed()
            WholeBookPreview(
                bookUrl, book.title, source, target, identity, coverage.total, coverage.cached, coverage.failed,
                coverage.characters, translator.destination, translationCost(coverage, price.await()),
                fingerprint, retryFailures, retentionStamp,
            )
        } finally {
            translator.close()
        }
    }

    /**
     * Starts [bookUrl]'s paused job again without the full check, when the
     * book file, the service and the languages are still the ones the
     * reader approved. False when it needs the check first, or, when
     * [automatic], when the pause was not a passing trouble.
     */
    suspend fun resume(bookUrl: String, automatic: Boolean = false): Boolean {
        initialized.await()
        synchronized(lock) { pausePersistence }?.join()
        // Taken before any wait, so a delete meanwhile makes the job's start find another generation.
        val record = saved.getKept(bookUrl) ?: return false
        val stamp = saved.retentionStamp(bookUrl)
        if (stamp.generation != record.generation) return false
        if (record.state != "paused" || !resumable(record.reason, automatic)) return false
        val approved = record.fingerprint ?: return false
        val feature = container.translate as? ServiceTranslate ?: return false
        try {
            if (currentTarget() != record.target) return false
            if (feature.wholeBookIdentity(record.source, record.target) != record.identity) return false
            val book = container.database.bookDao().getByUrl(bookUrl) ?: return false
            if (fingerprint(bookUrl) != approved) return false
            if (synchronized(lock) { active != null }) return false
            start(
                WholeBookPreview(
                    bookUrl, book.title, record.source, record.target, record.identity, record.total, record.done,
                    record.failed, 0, null, null, approved, retentionStamp = stamp, resumed = true,
                ),
            )
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cannot resume without checking: ${e.javaClass.simpleName}")
            return false
        }
    }

    private suspend fun currentTarget(): String =
        TranslationLanguages.target(container.appSettings.settings.first().translationTarget, Locale.getDefault())

    /** Resumes the most recent job that a passing trouble paused; called when the app comes to the foreground. */
    suspend fun resumeInterrupted() {
        try {
            initialized.await()
            saved.keptRecords().filter { it.state == "paused" && resumable(it.reason, automatic = true) }
                .firstOrNull { resume(it.bookUrl, automatic = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cannot resume an interrupted translation: ${e.javaClass.simpleName}")
        }
    }

    fun start(preview: WholeBookPreview, retryFailures: Boolean = false) {
        require(retryFailures == preview.retryFailures)
        synchronized(lock) {
            if (active != null) throw WholeBookBusyException()
            active = preview.bookUrl
            pending = preview
            approved = preview
            stopReason = "interrupted"
            stopped = false
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, WholeBookTranslationService::class.java))
        } catch (e: RuntimeException) {
            synchronized(lock) {
                pending = null
                active = null
                approved = null
            }
            throw e
        }
    }

    fun pause(bookUrl: String) {
        synchronized(lock) {
            if (active == bookUrl) {
                stopReason = "paused"
                stopped = true
                pending = null
                persistStartupPause()
                cancel?.invoke()
            }
        }
    }

    fun stopActive(reason: String = "paused") {
        synchronized(lock) {
            if (!stopped || reason == "paused") stopReason = reason
            stopped = true
            pending = null
            persistStartupPause()
            cancel?.invoke()
        }
    }

    // The service's lazy worker may never enter execute, so persist on the application scope too.
    private fun persistStartupPause() {
        val preview = approved ?: return
        val reason = stopReason
        val previous = pausePersistence
        pausePersistence = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                previous?.join()
                initialized.await()
                saved.pauseBeforeBegin(preview, reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _startFailures.tryEmit(StartFailure(preview.bookUrl, e))
                Log.w(TAG, "Could not persist translation pause: ${e.javaClass.simpleName}")
            }
        }
    }

    suspend fun delete(bookUrl: String) {
        pause(bookUrl)
        saved.remove(bookUrl)
    }

    internal fun takePending(): WholeBookPreview? = synchronized(lock) {
        pending.also {
            pending = null
            if (it == null && cancel == null) {
                active = null
                approved = null
            }
        }
    }

    internal fun attachCancellation(bookUrl: String, stop: () -> Unit) {
        synchronized(lock) {
            if (active != bookUrl || stopped) stop() else cancel = stop
        }
    }

    internal fun release(bookUrl: String) {
        synchronized(lock) {
            if (active == bookUrl) {
                active = null
                cancel = null
                approved = null
            }
        }
    }

    internal suspend fun execute(preview: WholeBookPreview, progress: (Int, Int) -> Unit) {
        var record: KeptTranslation? = null
        var translator: SentenceTranslator? = null
        try {
            container.translationAdmissions.bulk(preview.bookUrl, preview.identity) {
                initialized.await()
                if (fingerprint(preview.bookUrl) != preview.sourceFingerprint) throw IOException("The book changed after scanning")
                val feature = container.translate as? ServiceTranslate ?: throw TranslationError.NotSetUp()
                val pinned = feature.openPinned(preview.source, preview.target, preview.identity)
                translator = pinned
                openPublication(preview.bookUrl) { publication ->
                    if (pinned.answering() != preview.identity) throw TranslationError.SettingsChanged()
                    if (preview.resumed && currentTarget() != preview.target) throw TranslationError.SettingsChanged()
                    // Keep the committed generation even if cancellation arrives as Room returns it.
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        record = saved.begin(
                            preview.bookUrl, preview.source, preview.target, preview.identity, preview.total,
                            retryFailures = preview.retryFailures, preview = preview,
                        )
                    }
                    currentCoroutineContext().ensureActive()
                    val current = checkNotNull(record)
                    val requests = SentenceRequests(
                        pinned, TranslatedPages.preceding(publication, preview.source), TranslatedPages.bookFacts(publication),
                    )
                    WholeBookTranslation(
                        pinned, requests,
                        store(preview.bookUrl, current.generation, preview.sourceFingerprint, preview.total, progress),
                        online = container.networkAvailability::isAvailable,
                        requestCharacters = feature.wholeBookPrompt(preview.source, preview.target),
                    ).run(publication.translationSentences(preview.source), preview.identity, preview.total)
                }
                saved.modeFor(preview.bookUrl).remember(PageTranslationModes.Mode(preview.source, preview.target))
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val reason = synchronized(lock) { stopReason }
                val current = record
                if (current != null) saved.pause(current.bookUrl, current.generation, reason)
                else saved.pauseBeforeBegin(preview, reason)
            }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Whole-book translation paused: ${e.javaClass.simpleName}")
            val reason = wholeBookPauseReasonFor(e)
            val current = record
            if (current != null) saved.pause(current.bookUrl, current.generation, reason)
            else {
                _startFailures.tryEmit(StartFailure(preview.bookUrl, e))
                throw e
            }
        } finally {
            translator?.close()
        }
    }

    suspend fun retain(preview: WholeBookPreview): Unit =
        container.translationAdmissions.bulk(preview.bookUrl, preview.identity) {
            initialized.await()
            check(preview.total == preview.cached + preview.failed) { "Translation is not complete" }
            if (fingerprint(preview.bookUrl) != preview.sourceFingerprint) throw IOException("The book changed after scanning")
            val feature = container.translate as? ServiceTranslate ?: throw TranslationError.NotSetUp()
            val pinned = feature.openPinned(preview.source, preview.target, preview.identity)
            try {
                container.withTranslationPublication(preview.bookUrl) { publication ->
                    val requests = SentenceRequests(
                        pinned, TranslatedPages.preceding(publication, preview.source), TranslatedPages.bookFacts(publication),
                    )
                    saved.retain(preview) {
                        val coverage = WholeBookTranslation(pinned, requests, store(preview.bookUrl)) { 0 }
                            .scan(publication.translationSentences(preview.source))
                        if (fingerprint(preview.bookUrl) != preview.sourceFingerprint) throw IOException("The book changed while checking")
                        if (pinned.answering() != preview.identity) throw TranslationError.SettingsChanged()
                        coverage
                    }
                }
            } finally {
                pinned.close()
            }
        }

    private fun store(
        bookUrl: String,
        generation: Long? = null,
        sourceFingerprint: String? = null,
        total: Int = 0,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): WholeBookStore = object : WholeBookStore {
        override suspend fun scanLookups(keys: List<String>): List<WholeBookLookup> =
            saved.scanLookups(bookUrl, keys).map { WholeBookLookup(it.translation, it.failed) }

        override suspend fun lookup(key: String): WholeBookLookup {
            val found = saved.strictLookup(bookUrl, key, generation)
            if (found.invalidated) throw CancellationException("Translation deleted")
            return WholeBookLookup(found.translation, found.failed)
        }

        override suspend fun commit(key: String, translation: String?, done: Int, failed: Int): Boolean {
            val current = checkNotNull(generation)
            val result = if (translation == null) saved.saveFailure(bookUrl, current, key, done, failed)
            else saved.saveSuccess(bookUrl, current, key, translation, done, failed)
            if (result == KeptWriteResult.INVALIDATED) throw CancellationException("Translation deleted")
            val success = translation != null || saved.strictLookup(bookUrl, key, current).translation != null
            if (success && translation == null) {
                if (saved.progress(bookUrl, current, done + 1, failed - 1) == KeptWriteResult.INVALIDATED) {
                    throw CancellationException("Translation deleted")
                }
            }
            progress(done + failed, total)
            return success
        }

        override suspend fun finish(done: Int, failed: Int) {
            if (sourceFingerprint != null && fingerprint(bookUrl) != sourceFingerprint) throw IOException("The book changed while translating")
            if (saved.complete(bookUrl, checkNotNull(generation)) == KeptWriteResult.INVALIDATED) {
                throw CancellationException("Translation deleted")
            }
        }
    }

    private suspend fun fingerprint(bookUrl: String): String {
        val book = container.database.bookDao().getByUrl(bookUrl) ?: throw IOException("Book is not in the library")
        val url = book.openableUri() ?: throw IOException("Book is not downloaded")
        return container.bookFingerprints.compute(url)?.sha256 ?: throw IOException("Cannot fingerprint the book")
    }

    private companion object {
        const val TAG = "WholeBookTranslation"
    }
}

/** Another book's job holds the single whole-book slot; the reader must wait for it or pause it. */
internal class WholeBookBusyException : IllegalStateException("Another book is already being translated")

/**
 * The pause reason [WholeBookTranslations.execute] keeps for [e], read
 * back by [resumable] and by the sheet's pause banner; a typo on either
 * side breaks the resume path.
 */
internal fun wholeBookPauseReasonFor(e: Exception): String = when (e) {
    is TranslationError.Changed, is TranslationError.SettingsChanged -> "settings_changed"
    is TranslationError.Network -> "network"
    is TranslationError.RateLimited -> "rate_limited:${e.code}"
    is TranslationError.Service -> "service:${e.code}"
    is TranslationError -> "service"
    else -> "storage"
}

/**
 * Whether a job paused for [reason] may go on without a new check: never
 * after changed settings or a storage or book failure, and only after a
 * passing trouble (a killed app, Android's time limit, a lost connection,
 * a busy or failing server) when nobody asked. A 402 waits for the reader.
 */
internal fun resumable(reason: String?, automatic: Boolean): Boolean {
    if (reason == "settings_changed" || reason == "storage") return false
    if (!automatic) return true
    if (reason in listOf("interrupted", "timeout", "network", "rate_limited:429")) return true
    // A 402 is a spent quota or an unpaid bill, which only the reader can settle.
    if (reason?.startsWith("service:") != true) return false
    val code = wholeBookPauseCode(reason) ?: return false
    return code == 408 || code in 500..599
}
