package com.chmouel.liseur.data.calibre

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.chmouel.liseur.data.bookorbit.BookOrbitAdoptedSource
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.remote.BookDeleter
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.library.openableUri
import com.chmouel.liseur.data.remote.ServerDeleteResult
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * How far along a book's download is, as a fraction, or null if unknown.
 *
 * [queued] means WorkManager is holding the work back rather than
 * running it -- no network, a backoff after a failed attempt, or the
 * scheduler being busy. It has to be told apart from a download that is
 * genuinely under way, because the two look identical from the outside
 * and only one of them is going anywhere.
 */
data class DownloadProgress(
    val bookUrl: String,
    val fraction: Float?,
    val queued: Boolean = false,
)

/** What downloaded books are costing in device storage. */
data class StorageUse(val count: Int, val bytes: Long)

/**
 * Limits how many bulk-download transfers may pull bytes over the
 * network at once.
 *
 * WorkManager's own executor is happy to start many [BookDownloadWorker]s
 * in parallel once their constraints are met, which for "download
 * everything" is at once. That is fine for the bookkeeping each does
 * before and after, but every one of them opens its own connection to
 * the same server for the transfer itself, and a modest self-hosted
 * instance answering four or more of those at once is exactly what
 * turns a slow response into a dropped one (#89). A single download the
 * reader asked for by name never touches this: it has no batch to share
 * a server with.
 *
 * Plain Kotlin, not a repository method, so the limit itself can be
 * tested without standing up WorkManager and a database.
 */
class BulkTransferGate(maxConcurrent: Int = DEFAULT_MAX_CONCURRENT) {
    init {
        // Semaphore(0) or a negative count would wait forever rather
        // than throw, and that wait looks identical to a slow server
        // from everywhere else in the app. Caught here, once, rather
        // than as a batch that never starts.
        require(maxConcurrent >= 1) { "maxConcurrent must be at least 1, was $maxConcurrent" }
    }

    private val slots = Semaphore(maxConcurrent)

    /** Runs [block] once a transfer slot is free. */
    suspend fun <T> withSlot(block: suspend () -> T): T = slots.withPermit { block() }

    companion object {
        /**
         * Kept low on purpose: this is a courtesy to whatever the reader
         * is self-hosting, not a limit the device needs. Two lets a
         * finished transfer's teardown overlap with the next one
         * starting, without asking a small server to answer a handful of
         * large requests in parallel.
         */
        const val DEFAULT_MAX_CONCURRENT = 2
    }
}

/** Starts, watches and undoes book downloads. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookDownloadRepository(
    private val context: Context,
    private val bookDao: BookDao,
    private val bookRemoval: BookRemoval,
    private val scope: CoroutineScope,
    private val bulkStore: BulkDownloadStore = BulkDownloadStore(context),
    /**
     * Who the connected account is, asked at the moment work is
     * queued.
     *
     * Read here rather than passed in at each call site, because the
     * stamp is what lets a worker tell its own book from a book the
     * next account happens to give the same URL. A call site that
     * forgot it would look exactly like one that had nothing to say.
     */
    private val accountKey: suspend () -> String? = { null },
    private val bulkTransferGate: BulkTransferGate = BulkTransferGate(),
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Runs [block] once a bulk-download transfer slot is free. */
    suspend fun <T> withBulkTransferSlot(block: suspend () -> T): T =
        bulkTransferGate.withSlot(block)

    val downloaded: Flow<List<Book>> = bookDao.observeDownloaded()

    /** Progress for every download currently running, keyed by book URL. */
    val progress: Flow<Map<String, DownloadProgress>> =
        workManager.getWorkInfosByTagFlow(TAG).map { infos ->
            infos.filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                .mapNotNull { info ->
                    val url = info.progress.getString(KEY_BOOK_URL)
                        ?: info.tags.firstOrNull { it.startsWith(BOOK_TAG_PREFIX) }
                            ?.removePrefix(BOOK_TAG_PREFIX)
                        ?: return@mapNotNull null
                    val fraction = info.progress.getFloat(KEY_FRACTION, -1f).takeIf { it >= 0f }
                    url to DownloadProgress(
                        bookUrl = url,
                        fraction = fraction,
                        queued = info.state == WorkInfo.State.ENQUEUED,
                    )
                }
                .toMap()
        }

    /** How many books are on the device and what they take up, in bytes. */
    val storage: Flow<StorageUse> = downloaded.map { books ->
        val dir = booksDir()
        val bytes = books.sumOf { book ->
            // The file the reader actually has is the one they can open.
            // Naming it from the remote id instead reports zero for a
            // book that outlived the account it came from, which is the
            // ordinary state of a book on a connection with no catalog.
            book.localUri?.let { sizeOf(it) }
                ?: book.remoteUuid?.let { File(dir, "$it.epub").length() }
                ?: 0L
        }
        StorageUse(count = books.size, bytes = bytes)
    }.flowOn(Dispatchers.IO)

    /**
     * What the file behind a [Book.localUri] weighs, or null when it
     * cannot be asked. A restored orphan's copy is a document rather
     * than a file of ours, so it is measured through its provider.
     */
    private fun sizeOf(localUri: String): Long? =
        if (localUri.startsWith("file:")) {
            runCatching { File(URI(localUri)).length() }.getOrNull()
        } else {
            runCatching {
                context.contentResolver.openAssetFileDescriptor(Uri.parse(localUri), "r")
                    ?.use { it.length.takeIf { length -> length >= 0 } }
            }.getOrNull()
        }

    suspend fun enqueue(book: Book) {
        bookDao.setDownloadState(book.url, DownloadState.QUEUED, null)
        workManager.enqueueUniqueWork(
            workName(book.url),
            ExistingWorkPolicy.KEEP,
            request(book.url, accountKey = accountKey(), batchId = null),
        )
    }

    /**
     * Reads what a "download everything" run would cost, without
     * starting one.
     *
     * Free space is measured on the volume the books are actually
     * written to rather than on whatever `getExternalStorageDirectory`
     * happens to be: internal storage and a removable card fill up
     * independently, and only one of them is the one that matters here.
     */
    suspend fun bulkEstimate(): BulkDownloadEstimate = withContext(Dispatchers.IO) {
        val candidates = booksToDownload(bookDao.allRemote())
        estimateBulkDownload(candidates.map { it.sizeBytes }, freeBytes())
            .copy(urls = candidates.map { it.url })
    }

    /**
     * Fetches every catalogued book that is not already here.
     *
     * A batch is a set of ordinary [BookDownloadWorker]s, not one worker
     * looping: that keeps per-book retry, resume across process death,
     * and the `download:$bookUrl` uniqueness that stops a tap on a cover
     * racing the run. What is added is the enumeration and a batch tag
     * to gather them under.
     *
     * Runs to the end once started, whoever asked for it. The enumeration
     * of a large library is thousands of database writes, and the batch
     * record is already open by then with a total that only the whole
     * selection can satisfy: a caller that goes away halfway — a screen
     * left, most likely — would leave a batch that can never reach its
     * own denominator, and so can never settle or be started again.
     *
     * Returns how many were actually accepted, which is not always how
     * many were selected — see [confirmMembership].
     *
     * [only] is the exact set the reader was quoted a price for, in the
     * order it was priced in. The library is read again, because a book
     * may have finished downloading or been archived while the dialog
     * was open, but nothing that was not priced is put in its place:
     * the run is what was agreed to or less, never something else.
     * Null is every book that needs fetching, which is what the action
     * has always meant.
     */
    suspend fun enqueueAll(accountKey: String, only: List<String>? = null): Int = scope.async(Dispatchers.IO) {
        val available = booksToDownload(bookDao.allRemote())
        val candidates = if (only == null) {
            available
        } else {
            val byUrl = available.associateBy { it.url }
            only.mapNotNull(byUrl::get)
        }
        if (candidates.isEmpty()) return@async 0
        val batchId = UUID.randomUUID().toString()
        val batchTag = batchTag(batchId)
        // The record is opened before the first request goes in.
        // WorkManager starts a worker the moment its constraints are
        // met, which here is at once, and a worker that finds no record
        // for the batch it names cannot tell a batch that has not been
        // opened yet from one that is already over — so it stands down,
        // and the run loses every book that got off the mark quickly.
        // The total is provisional until membership is confirmed below.
        bulkStore.start(batchId, candidates.size)
        candidates.chunked(ENQUEUE_CHUNK).forEach { chunk ->
            val enqueued = chunk.map { book ->
                bookDao.setDownloadState(book.url, DownloadState.QUEUED, null)
                workManager.enqueueUniqueWork(
                    workName(book.url),
                    ExistingWorkPolicy.KEEP,
                    request(book.url, accountKey = accountKey, batchId = batchId),
                )
            }
            // Waited for, chunk by chunk. `enqueueUniqueWork` hands the
            // write off to WorkManager's own executor and returns before
            // it has landed, so the tag [confirmMembership] reads by can
            // still be empty when it looks. An undercount would give the
            // batch a denominator smaller than the run it is describing;
            // a count of nothing would clear the record out from under
            // every worker already running under it, and the whole run
            // would quietly stand down.
            enqueued.forEach { it.await() }
            yield()
        }
        val accepted = confirmMembership(batchTag)
        // Everything we asked for was refused, every one of them by a
        // download already in flight. There is no batch here, only a
        // wait, and opening one would leave the action disabled until
        // work that was never ours finished.
        if (accepted == 0) {
            bulkStore.clear()
            return@async 0
        }
        bulkStore.setTotal(batchId, accepted)
        accepted
    }.await()

    /**
     * How many of the requests just made are really in the batch.
     *
     * `enqueueUniqueWork` under `KEEP` throws our request away without
     * complaint when the unique name is already taken — by a single
     * download the reader started by tapping a cover, most likely.
     * Counting the selection instead of the acceptance would give the
     * batch a denominator it could never reach, and would let a bulk
     * cancel reach for work that was never part of it.
     */
    private suspend fun confirmMembership(batchTag: String): Int =
        workManager.getWorkInfosByTagFlow(batchTag).first().size

    /**
     * Stops the current batch and puts the database back.
     *
     * Handed to a worker of its own rather than done here: cancelling
     * work is asynchronous, and resetting rows before the cancellation
     * has landed races a worker that is at that moment writing
     * `DOWNLOADING`, leaving exactly the stale row the reset existed to
     * clear. The cleanup worker carries no batch tag, so it survives the
     * cancellation it sends out and can wait for it.
     */
    suspend fun cancelAll(reason: BulkStopReason = BulkStopReason.CANCELLED) {
        val batch = bulkStore.current()?.takeIf { !it.settled } ?: return
        bulkStore.recordStopReason(batch.id, reason)
        BulkDownloadCleanupWorker.enqueue(context, batch.id)
    }

    /** Forgets the last batch, once its summary has been read.
     *
     * Refuses a batch that has not settled yet. A batch that has been
     * asked to stop is still being taken apart — work still to cancel,
     * rows still to put back — and the record is what the cleanup worker
     * finds its way by: clearing it there would strand every book it had
     * not reached at `QUEUED`, where nothing would ever pick them up
     * again.
     */
    suspend fun dismissBatch() {
        if (bulkStore.current()?.settled != true) return
        bulkStore.clear()
    }

    /**
     * The batch as it stands: counts from the live work while it runs,
     * from the stored record once it has settled and its work rows are
     * on their way to being pruned.
     *
     * A batch that runs to the end without anything stopping it settles
     * here, the moment the last of its work reaches a terminal state.
     * Nothing else would: the cleanup worker only runs when a batch is
     * cut short, and a summary left to be derived from work rows reads
     * "0 of 32" once WorkManager has pruned them. A batch that *was* cut
     * short is left alone, so that `settled` keeps meaning "and taken
     * apart": the cleanup worker settles that one, last, after the rows
     * are back.
     */
    val bulkBatch: Flow<BulkBatch?> = bulkStore.batch.flatMapLatest { batch ->
        if (batch == null || batch.settled) return@flatMapLatest flowOf(batch)
        workManager.getWorkInfosByTagFlow(batchTag(batch.id)).map { infos ->
            val done = countDone(infos)
            val failed = countFailed(infos)
            if (batch.stopReason == null) {
                if (infos.size >= batch.total && infos.all { it.state.isFinished }) {
                    bulkStore.settle(batch.id, done = done, failed = failed)
                }
            } else {
                // Asks again for the teardown of a batch that has been
                // stopped but not yet taken apart. The cleanup worker is
                // the only thing that can end one, and it can be lost:
                // it fails terminally on a device too full to write, or
                // its request never reaches WorkManager because the
                // process died in the moment between. Nothing else would
                // ask again, and the reader would be left looking at a
                // batch that can neither finish nor be dismissed.
                //
                // Free to repeat: the request is unique per batch under
                // `KEEP`, so it is ignored while one is still pending
                // and revives one that has already given up.
                BulkDownloadCleanupWorker.enqueue(context, batch.id)
            }
            batch.copy(done = done, failed = failed)
        }
    }

    /** Free bytes on the volume [booksDir] lives on. */
    fun freeBytes(): Long = runCatching {
        val stat = StatFs(booksDir().path)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    private fun request(bookUrl: String, accountKey: String?, batchId: String?) =
        OneTimeWorkRequestBuilder<BookDownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_BOOK_URL, bookUrl)
                    .apply {
                        accountKey?.let { putString(KEY_ACCOUNT_KEY, it) }
                        batchId?.let { putString(KEY_BATCH_ID, it) }
                    }
                    .build(),
            )
            .addTag(TAG)
            .addTag(BOOK_TAG_PREFIX + bookUrl)
            .apply { batchId?.let { addTag(batchTag(it)) } }
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    // Bulk work only. A single download the reader asked
                    // for by name should still start on a full-ish
                    // device; a hundred of them should not.
                    .apply { if (batchId != null) setRequiresStorageNotLow(true) }
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

    suspend fun cancel(book: Book) {
        workManager.cancelUniqueWork(workName(book.url))
        bookDao.setDownloadState(book.url, DownloadState.REMOTE, null)
    }

    /**
     * Deletes the file but keeps the book in the library, so where you
     * were in it, and anything you marked, is still there if you fetch
     * it again.
     */
    suspend fun removeDownload(book: Book) {
        val uuid = book.remoteUuid ?: return
        fileFor(uuid).delete()
        File(booksDir(), "$uuid.epub.part").delete()
        File(booksDir(), "$uuid.epub.etag").delete()
        deleteOwnedCopy(book)
        bookDao.setDownloadState(book.url, DownloadState.REMOTE, null)
    }

    /**
     * Deletes a book from the server and forgets it here.
     *
     * Everything else in the app only ever removes a copy; this is the one
     * action that reaches the server, so it is the only one that can lose
     * the book for good. Neither server this reaches keeps a trash to
     * undo it from.
     *
     * [forgetReading] asks the server to forget the caller's own reading
     * of the book too, and is the reader's to answer. The local reading
     * goes regardless, below: with the book gone for every device, hours
     * kept here would be an entry with nothing behind it.
     */
    suspend fun deleteFromServer(
        book: Book,
        deleter: BookDeleter,
        server: RemoteServer,
        forgetReading: Boolean = false,
    ): ServerDeleteResult {
        val credentials = server.credentials ?: return ServerDeleteResult.Failed(null)
        val account = server.accountKey
        // What the request is about, taken before it leaves. The answer
        // may come back after the reader has switched account, relinked
        // the entry or replaced its file, and a "deleted" about the book
        // as it was is no licence to remove the one that is there now.
        val sent = bookDao.getByUrl(book.url)?.takeIf { it.remoteUuid == book.remoteUuid }
            ?: return ServerDeleteResult.Failed(null)
        val files = ownedFilesOf(sent)
        val stamp = stampOf(files) to sourceStampOf(sent)
        val verifiable = stamp.second != UNVERIFIABLE
        val result = deleter.delete(server.baseUrl, credentials, sent, forgetReading)
        if (result !is ServerDeleteResult.Deleted) return result
        var removed = false
        inTransaction {
            val now = bookDao.getByUrl(book.url) ?: return@inTransaction
            if (accountKey() != account) return@inTransaction
            if (now.remoteUuid != sent.remoteUuid || now.localUri != sent.localUri) {
                return@inTransaction
            }
            if (!verifiable || stampOf(ownedFilesOf(now)) to sourceStampOf(now) != stamp) return@inTransaction
            deleter.forgetDeleted(book.url, account)
            // The book is gone from the server too, so this is not a
            // copy being freed up: nothing is coming back, and the
            // hours are no longer about anything.
            bookRemoval.deleteByUrls(listOf(book.url))
            removed = true
        }
        // Files after the rows: a file deleted for a transaction that
        // then did not commit is a book whose entry points at nothing.
        if (removed) {
            files.forEach { it.delete() }
        } else {
            Log.w(TAG, "${book.url} changed while it was deleted from the server; kept here")
        }
        return result
    }

    /**
     * The files deleting this book here would remove: the download named
     * after its server id, and a private copy in the app's own store.
     */
    private fun ownedFilesOf(book: Book): List<File> = buildList {
        book.remoteUuid?.let { add(fileFor(it)) }
        ownedCopyOf(book)?.let(::add)
    }.distinct()

    private suspend fun stampOf(files: List<File>): List<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        files.map { it.length() to it.lastModified() }
    }

    /**
     * The size and modification time of the file the entry opens, which
     * for a book in a watched folder is a document the app does not own
     * and anything on the phone can replace.
     */
    private suspend fun sourceStampOf(book: Book): BookOrbitAdoptedSource.Stamp? = withContext(Dispatchers.IO) {
        val uri = book.openableUri()?.toUri() ?: return@withContext null
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File) ?: return@withContext UNVERIFIABLE
            // A file that is not there is a fact, and one a replacement
            // would change.
            return@withContext if (file.isFile) {
                BookOrbitAdoptedSource.Stamp(file.length(), file.lastModified())
            } else {
                MISSING
            }
        }
        // A document whose provider will not say its size and time could
        // be replaced without this noticing, so it is never proved
        // unchanged.
        BookOrbitAdoptedSource(context).stamp(uri)
            ?.takeIf { it.size != null && it.modifiedAt != null }
            ?: UNVERIFIABLE
    }

    /**
     * Removes a book that came from a folder or a single import.
     *
     * The library row only goes if the file really went. A folder scan
     * indexes whatever is on disk, so dropping the row while the file
     * survives makes the book reappear at the next scan, which looks like
     * the app ignoring the request. Returns false when the file stayed;
     * usually that means Liseur was only ever granted read access to the
     * folder, and it has to be added again.
     */
    suspend fun deleteLocalBook(book: Book): Boolean = withContext(Dispatchers.IO) {
        val uri = book.openableUri()?.toUri() ?: return@withContext false
        if (!deleteFile(uri)) return@withContext false
        bookRemoval.deleteByUrls(listOf(book.url))
        true
    }

    /** True once the file is not there any more, however that came about. */
    private fun deleteFile(uri: Uri): Boolean = when (uri.scheme) {
        "file" -> {
            val file = uri.path?.let(::File)
            file != null && (!file.exists() || file.delete())
        }
        else -> runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }.onFailure {
            Log.w(TAG, "Not allowed to delete $uri", it)
        }.getOrDefault(false)
    }

    fun booksDir(): File = File(context.filesDir, "books").apply { mkdirs() }

    /**
     * Deletes a [Book.localUri] copy that lives in the app's own books
     * directory, and only that. A restored orphan whose grant would not
     * persist got such a copy under a content-derived name rather than
     * the remote id, so the id-named deletes above never reach it. A
     * document in the reader's own folder is not ours to delete and is
     * left alone.
     */
    private fun deleteOwnedCopy(book: Book) {
        ownedCopyOf(book)?.delete()
    }

    private fun ownedCopyOf(book: Book): File? {
        val local = book.localUri?.takeIf { it.startsWith("file:") } ?: return null
        val file = runCatching { File(URI(local)).canonicalFile }.getOrNull() ?: return null
        val owned = runCatching { booksDir().canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.parentFile == owned }
    }

    fun fileFor(uuid: String): File = File(booksDir(), "$uuid.epub")

    fun localUriFor(uuid: String): String = Uri.fromFile(fileFor(uuid)).toString()

    companion object {
        const val TAG = "book-download"
        const val KEY_BOOK_URL = "book_url"
        const val KEY_FRACTION = "fraction"
        const val KEY_ACCOUNT_KEY = "account_key"
        const val KEY_BATCH_ID = "batch_id"
        const val KEY_STOOD_DOWN = "stood_down"
        const val BOOK_TAG_PREFIX = "book:"
        private const val BATCH_TAG_PREFIX = "batch:"

        /** What a source stamp says when there is no file, or no way to tell. */
        private val MISSING = BookOrbitAdoptedSource.Stamp(-1, -1)
        private val UNVERIFIABLE = BookOrbitAdoptedSource.Stamp(null, null)

        /**
         * How many requests to send before letting the thread breathe.
         *
         * `enqueueUniqueWork` is one binder round trip and one database
         * write each, and a large calibre library is thousands of books.
         */
        private const val ENQUEUE_CHUNK = 50

        fun workName(bookUrl: String) = "download:$bookUrl"

        fun batchTag(batchId: String) = BATCH_TAG_PREFIX + batchId

        /**
         * Books whose bytes arrived.
         *
         * A worker that stood down — its batch was over, or the account
         * had changed under it — also reports success, because it is not
         * a failure and must not be shown as one. It says as much in its
         * output, and that is what is subtracted here.
         */
        fun countDone(infos: List<WorkInfo>): Int = infos.count {
            it.state == WorkInfo.State.SUCCEEDED &&
                !it.outputData.getBoolean(KEY_STOOD_DOWN, false)
        }

        /**
         * Books that genuinely could not be fetched.
         *
         * Cancelled work is not counted: after a batch is stopped every
         * member that had not started is `CANCELLED`, and reading that
         * back as "couldn't be downloaded" would tell the reader their
         * library had failed when all they did was tap Stop.
         */
        fun countFailed(infos: List<WorkInfo>): Int =
            infos.count { it.state == WorkInfo.State.FAILED }
    }
}
