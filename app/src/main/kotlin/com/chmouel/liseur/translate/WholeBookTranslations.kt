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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

internal class WholeBookTranslations(
    private val context: Context,
    private val container: AppContainer,
    scope: CoroutineScope,
) {
    private val saved: SavedTranslations get() = container.savedTranslations
    private val lock = Any()
    private var active: String? = null
    private var pending: WholeBookPreview? = null
    private var cancel: (() -> Unit)? = null
    private var stopped = false
    private var stopReason = "paused"
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
                WholeBookTranslation(translator, requests, store(bookUrl), requestCharacters)
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

    fun start(preview: WholeBookPreview, retryFailures: Boolean = false) {
        require(retryFailures == preview.retryFailures)
        synchronized(lock) {
            check(active == null) { "Another book is already being translated" }
            active = preview.bookUrl
            pending = preview
            stopReason = "paused"
            stopped = false
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, WholeBookTranslationService::class.java))
        } catch (e: RuntimeException) {
            synchronized(lock) {
                pending = null
                active = null
            }
            throw e
        }
    }

    fun pause(bookUrl: String) {
        synchronized(lock) {
            if (active == bookUrl) {
                stopped = true
                pending = null
                cancel?.invoke()
            }
        }
    }

    fun stopActive(reason: String = "paused") {
        synchronized(lock) {
            if (!stopped) stopReason = reason
            stopped = true
            pending = null
            cancel?.invoke()
        }
    }

    suspend fun delete(bookUrl: String) {
        pause(bookUrl)
        saved.remove(bookUrl)
    }

    internal fun takePending(): WholeBookPreview? = synchronized(lock) {
        pending.also {
            pending = null
            if (it == null && cancel == null) active = null
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
            }
        }
    }

    internal suspend fun execute(preview: WholeBookPreview, progress: (Int, Int) -> Unit): Unit =
        container.translationAdmissions.bulk(preview.bookUrl, preview.identity) {
        var record: KeptTranslation? = null
        var translator: SentenceTranslator? = null
        try {
            initialized.await()
            if (fingerprint(preview.bookUrl) != preview.sourceFingerprint) throw IOException("The book changed after scanning")
            val feature = container.translate as? ServiceTranslate ?: throw TranslationError.NotSetUp()
            val pinned = feature.openPinned(preview.source, preview.target, preview.identity)
            translator = pinned
            container.withTranslationPublication(preview.bookUrl) { publication ->
                if (pinned.answering() != preview.identity) throw TranslationError.SettingsChanged()
                val current = saved.begin(
                    preview.bookUrl, preview.source, preview.target, preview.identity, preview.total,
                    retryFailures = preview.retryFailures, preview = preview,
                )
                record = current
                val requests = SentenceRequests(
                    pinned, TranslatedPages.preceding(publication, preview.source), TranslatedPages.bookFacts(publication),
                )
                WholeBookTranslation(
                    pinned, requests,
                    store(preview.bookUrl, current.generation, preview.sourceFingerprint, preview.total, progress),
                    feature.wholeBookPrompt(preview.source, preview.target),
                ).run(publication.translationSentences(preview.source), preview.identity, preview.total)
            }
            saved.modeFor(preview.bookUrl).remember(PageTranslationModes.Mode(preview.source, preview.target))
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                record?.let { saved.pause(it.bookUrl, it.generation, synchronized(lock) { stopReason }) }
            }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Whole-book translation paused: ${e.javaClass.simpleName}")
            val reason = when (e) {
                is TranslationError.Changed, is TranslationError.SettingsChanged -> "settings_changed"
                is TranslationError.Network -> "network"
                is TranslationError.RateLimited -> "rate_limited"
                is TranslationError -> "service"
                else -> "storage"
            }
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
