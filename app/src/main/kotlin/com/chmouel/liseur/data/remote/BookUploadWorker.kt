package com.chmouel.liseur.data.remote

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.chmouel.liseur.container
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookFingerprintRow
import com.chmouel.liseur.data.db.UploadRefusal
import com.chmouel.liseur.domain.BookFingerprint
import com.chmouel.liseur.domain.BookFingerprints
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends one book up to the server in the background.
 *
 * A failure here never touches the book: it stays on the device, in the
 * library, readable, and the worker can be asked again.
 */
class BookUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.container
        val bookUrl = inputData.getString(BookUploadRepository.KEY_BOOK_URL)
            ?: return giveUp("no book url")

        val bookDao = container.database.bookDao()
        val book = bookDao.getByUrl(bookUrl) ?: return giveUp("unknown book $bookUrl")
        if (book.remoteUuid != null) return Result.success()

        val server = container.remoteAccount.current() ?: return giveUp("no server")
        // Queued for one account, woken on another: WorkManager keeps a
        // job across a disconnect and a sign-in, and the folder id in
        // the input data belonged to whoever asked for it. Uploading
        // somebody's book to a stranger's shelf is not a thing to leave
        // to timing.
        val account = server.accountKey
        val expected = inputData.getString(BookUploadRepository.KEY_ACCOUNT_KEY)
        if (expected != null && expected != account) {
            return giveUp("$bookUrl was queued for another account")
        }
        val credentials = server.credentials ?: return giveUp("no credentials")
        val uploader = container.remoteRouter.uploaderFor(server.kind)
            ?: return giveUp("${server.kind} does not take uploads")

        val folderId = inputData.getString(BookUploadRepository.KEY_FOLDER_ID)
            ?: runCatching { uploader.targets(server.baseUrl, credentials) }
                .getOrElse { return retryAfter("could not list upload folders: ${it.message}") }
                .firstOrNull()?.folderId
            ?: return refuse(container, account, "no folder accepts uploads")

        val snapshot = snapshot(book)
            ?: return record(
                container, account, book,
                UploadRefusal.FILE_UNREADABLE, null, null,
                "no readable file for $bookUrl",
            )

        // The account is checked again here rather than only at the top:
        // the request *is* the side effect, and everything above it —
        // listing folders, hashing tens of megabytes — takes long enough
        // for a reader to have disconnected in the middle of it.
        if (container.remoteAccount.current()?.accountKey != account) {
            return giveUp("$bookUrl: the account changed before it was sent")
        }

        return try {
            when (val result = uploader.upload(
                baseUrl = server.baseUrl,
                credentials = credentials,
                folderId = folderId,
                file = snapshot.file,
                filename = filenameFor(book.title, book.author),
            )) {
                is ServerUploadResult.Uploaded ->
                    adopt(container, account, book, snapshot, result.remoteBookId)
                // The bytes are safe but the server had not catalogued
                // them when it answered. Coming back is how the id is
                // learnt: the server keys on the book's digest, so the
                // second ask is recognised rather than stored twice.
                ServerUploadResult.Pending -> retryAfter("$bookUrl uploaded, not catalogued yet")
                ServerUploadResult.NotAllowed ->
                    refuse(container, account, "server refused the upload")
                ServerUploadResult.TooLarge -> record(
                    container, account, book,
                    UploadRefusal.TOO_LARGE, null, snapshot,
                    "$bookUrl is larger than the server takes",
                )
                is ServerUploadResult.Rejected -> record(
                    container, account, book,
                    UploadRefusal.SERVER_REFUSED, result.reason, snapshot,
                    "the server would not read $bookUrl: ${result.reason ?: "no reason given"}",
                )
                is ServerUploadResult.Failed -> retryAfter("upload failed: ${result.message}")
            }
        } finally {
            snapshot.cleanup()
        }
    }

    /**
     * Writes down that this account will not take this book, and stops.
     *
     * The whole point of the row: before it existed a permanent refusal
     * returned a bare failure, WorkManager forgot the job, and the
     * library — which builds its offer from "books that live only on
     * this device" — asked the reader to send the same book again on the
     * next launch. Silently, forever.
     *
     * Recorded against the digest of what was actually sent, so a reader
     * who replaces the file with a copy the server does like is offered
     * it again without anything having to remember to clear this.
     */
    private suspend fun record(
        container: com.chmouel.liseur.AppContainer,
        account: String,
        book: Book,
        kind: String,
        reason: String?,
        snapshot: Snapshot?,
        why: String,
    ): Result {
        container.database.withTransaction {
            // Re-read inside the transaction: an account switch or a
            // book removed while the request was in the air must not be
            // overwritten by an answer about the world as it was.
            if (container.remoteAccount.current()?.accountKey != account) return@withTransaction
            val current = container.database.bookDao().getByUrl(book.url) ?: return@withTransaction
            // The digest is what makes this refusal expire on its own,
            // so it has to be one the library can compare against later
            // — and the only digest anybody has just computed is this
            // one. Stored under the modification time the file had when
            // it was read, exactly as the fingerprint store would.
            if (snapshot != null) {
                container.database.workIdentityDao().upsert(
                    BookFingerprintRow(
                        bookUrl = current.url,
                        sha256 = snapshot.fingerprint.sha256,
                        partialMd5 = snapshot.fingerprint.partialMd5,
                        fileSize = snapshot.fingerprint.size,
                        fileModifiedAt = current.fileModifiedAt,
                        computedAt = System.currentTimeMillis(),
                    ),
                )
            }
            container.database.uploadRefusalDao().upsert(
                UploadRefusal(
                    bookUrl = current.url,
                    accountKey = account,
                    refusedAt = System.currentTimeMillis(),
                    kind = kind,
                    reason = reason,
                    contentSha256 = snapshot?.fingerprint?.sha256,
                ),
            )
        }
        return giveUp(why)
    }

    /**
     * Ties the local book to the server's copy.
     *
     * The row keeps its own URL, so reading positions, annotations and
     * sessions stay attached to it untouched; only the link is written.
     * The next catalog pass matches on the remote id and fills in the
     * rest, rather than introducing the book a second time.
     */
    private suspend fun adopt(
        container: com.chmouel.liseur.AppContainer,
        account: String,
        book: Book,
        snapshot: Snapshot,
        remoteBookId: String,
    ): Result {
        // The bytes the server answered about. If the file underneath
        // has moved on since — a library folder is somebody else's to
        // write into — then this id names a book that is no longer the
        // one on this device, and pinning it here would attach the
        // reader's place to the wrong copy.
        if (!snapshot.stillDescribes(book)) {
            return giveUp("${book.url} changed while it was being sent")
        }
        val dao = container.database.bookDao()
        var linked = false
        // A catalog pass that ran between the upload and this line has
        // already introduced the server's copy under its own URL. That
        // row is minutes old and holds no reading, while this one holds
        // all of it, so the catalog's is the one that goes.
        //
        // Looking and linking are one transaction: a pass landing
        // between the two would otherwise insert the row just after it
        // was looked for and leave the duplicate this is here to stop.
        container.database.withTransaction {
            // Everything below writes, so everything below is
            // conditional: an answer for an account the reader has left
            // must not delete its rows, link its books or turn its
            // capabilities on and off.
            if (container.remoteAccount.current()?.accountKey != account) return@withTransaction
            if (dao.getByUrl(book.url) == null) return@withTransaction
            dao.byRemoteUuids(listOf(remoteBookId))
                .map { it.url }
                .filter { it != book.url }
                .takeIf { it.isNotEmpty() }
                ?.let { dao.deleteByUrls(it) }
            dao.linkToRemote(
                url = book.url,
                remoteUuid = remoteBookId,
                downloadHref = "/v1/books/$remoteBookId/download",
                coverUrl = null,
                remoteUpdatedAt = System.currentTimeMillis(),
            )
            // A book that went up is one this account has: whatever it
            // once refused about these bytes is spent.
            container.database.uploadRefusalDao().clear(book.url, account)
            linked = true
        }
        if (!linked) return giveUp("${book.url}: nothing left to link it to")
        container.remoteCatalog.refreshDetached()
        return Result.success()
    }

    /**
     * The exact bytes to send, and what they hash to.
     *
     * Identity has to be the bytes that went up, not the row they came
     * from: a book in a library folder is a file in somebody's Documents
     * that can be replaced while this request is in flight, and a
     * refusal or an adoption recorded against a path would then describe
     * a file nobody sent.
     *
     * One source is immutable by construction — the app's own store,
     * where the file *is* named after its digest — and is sent where it
     * lies. Everything else is copied into the cache first and hashed on
     * the way through, which is what the content-URI path already did
     * for its own reasons.
     */
    private suspend fun snapshot(
        book: Book,
    ): Snapshot? = withContext(Dispatchers.IO) {
        val source = (book.localUri ?: book.url).toUri()
        val own = source.takeIf { it.scheme == "file" }?.path?.let(::File)
            ?.takeIf { it.isFile && it.parentFile == ownStore() }
        if (own != null) {
            val fingerprint = runCatching { own.inputStream().use(BookFingerprints::of) }
                .getOrElse {
                    Log.w(TAG, "could not hash ${own.name}", it)
                    return@withContext null
                }
            return@withContext Snapshot(own, fingerprint, temporary = false)
        }

        val spool = File.createTempFile("upload", ".epub", applicationContext.cacheDir)
        val fingerprint = runCatching {
            applicationContext.contentResolver.openInputStream(source).use { input ->
                input ?: error("no stream for $source")
                spool.outputStream().use(input::copyTo)
            }
            spool.inputStream().use(BookFingerprints::of)
        }.getOrElse {
            Log.w(TAG, "could not read $source", it)
            spool.delete()
            return@withContext null
        }
        Snapshot(spool, fingerprint, temporary = true)
    }

    private fun ownStore() = File(applicationContext.filesDir, "books")

    /** The file that was sent, and the digest of it. */
    private inner class Snapshot(
        val file: File,
        val fingerprint: BookFingerprint,
        private val temporary: Boolean,
    ) {
        /**
         * Whether the book's source still holds these bytes.
         *
         * Cheap first, then certain: a size that no longer matches is
         * enough on its own, and only a size that does match is worth
         * reading tens of megabytes to be sure about. A source that
         * cannot be read at all is not a match — something is wrong, and
         * guessing in favour of writing is the wrong way to guess.
         */
        suspend fun stillDescribes(
            book: Book,
        ): Boolean = withContext(Dispatchers.IO) {
            if (!temporary) return@withContext file.isFile && file.length() == fingerprint.size
            val source = (book.localUri ?: book.url).toUri()
            runCatching {
                applicationContext.contentResolver.openInputStream(source).use { input ->
                    input ?: return@runCatching false
                    BookFingerprints.of(input).sha256 == fingerprint.sha256
                }
            }.getOrDefault(false)
        }

        fun cleanup() {
            if (temporary) file.delete()
        }
    }

    private fun giveUp(why: String): Result {
        Log.w(TAG, why)
        return Result.failure()
    }

    /**
     * Gives up, and stops the app offering to send this server a book.
     *
     * A token that may upload and a server where no folder will have
     * one are the same answer to a reader. The capability is what every
     * entry point is drawn from, so leaving it standing offers an
     * action that can only fail — silently, once per book, for as long
     * as they keep asking. Signing in again is what reads the server's
     * mind afresh, exactly as it is for a refusal on the wire.
     *
     * Conditional on the account, because this is the widest write in
     * the worker: a job left over from an account the reader has since
     * left would otherwise turn uploading off for the one they are on.
     */
    private suspend fun refuse(
        container: com.chmouel.liseur.AppContainer,
        account: String,
        why: String,
    ): Result {
        container.database.withTransaction {
            if (container.remoteAccount.current()?.accountKey != account) return@withTransaction
            container.database.remoteServerDao().setCanUpload(false)
        }
        return giveUp(why)
    }

    private fun retryAfter(why: String): Result {
        Log.w(TAG, why)
        return Result.retry()
    }

    companion object {
        private const val TAG = "book-upload"

        /**
         * A name for the file on the server, from what the library knows.
         *
         * The server derives its own placement from the EPUB, so this is
         * only what shows up in a log or an error; it still avoids path
         * separators, because a filename is not a place to put one.
         */
        fun filenameFor(title: String, author: String?): String {
            val stem = listOfNotNull(title.ifBlank { null }, author?.ifBlank { null })
                .joinToString(" - ")
                .ifBlank { "book" }
            return stem.map { if (it == '/' || it == '\\' || it.code < 0x20) '_' else it }
                .joinToString("")
                .take(120)
                .trim()
                .plus(".epub")
        }
    }
}
