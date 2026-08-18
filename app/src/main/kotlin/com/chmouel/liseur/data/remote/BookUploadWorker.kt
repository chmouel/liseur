package com.chmouel.liseur.data.remote

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.chmouel.liseur.container
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
        val credentials = server.credentials ?: return giveUp("no credentials")
        val uploader = container.remoteRouter.uploaderFor(server.kind)
            ?: return giveUp("${server.kind} does not take uploads")

        val folderId = inputData.getString(BookUploadRepository.KEY_FOLDER_ID)
            ?: runCatching { uploader.targets(server.baseUrl, credentials) }
                .getOrElse { return retryAfter("could not list upload folders: ${it.message}") }
                .firstOrNull()?.folderId
            ?: return giveUp("no folder accepts uploads")

        val (file, temporary) = localFile(book.localUri ?: book.url)
            ?: return giveUp("no readable file for $bookUrl")

        return try {
            when (val result = uploader.upload(
                baseUrl = server.baseUrl,
                credentials = credentials,
                folderId = folderId,
                file = file,
                filename = filenameFor(book.title, book.author),
            )) {
                is ServerUploadResult.Uploaded -> {
                    adopt(container, bookUrl, result.remoteBookId)
                    Result.success()
                }
                // The bytes are safe but the server had not catalogued
                // them when it answered. Coming back is how the id is
                // learnt: the server keys on the book's digest, so the
                // second ask is recognised rather than stored twice.
                ServerUploadResult.Pending -> retryAfter("$bookUrl uploaded, not catalogued yet")
                ServerUploadResult.NotAllowed -> {
                    container.database.remoteServerDao().setCanUpload(false)
                    giveUp("server refused the upload")
                }
                ServerUploadResult.TooLarge -> giveUp("$bookUrl is larger than the server takes")
                ServerUploadResult.Rejected -> giveUp("the server would not read $bookUrl")
                is ServerUploadResult.Failed -> retryAfter("upload failed: ${result.message}")
            }
        } finally {
            if (temporary) file.delete()
        }
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
        bookUrl: String,
        remoteBookId: String,
    ) {
        val dao = container.database.bookDao()
        // A catalog pass that ran between the upload and this line has
        // already introduced the server's copy under its own URL. That
        // row is minutes old and holds no reading, while this one holds
        // all of it, so the catalog's is the one that goes.
        //
        // Looking and linking are one transaction: a pass landing
        // between the two would otherwise insert the row just after it
        // was looked for and leave the duplicate this is here to stop.
        container.database.withTransaction {
            dao.byRemoteUuids(listOf(remoteBookId))
                .map { it.url }
                .filter { it != bookUrl }
                .takeIf { it.isNotEmpty() }
                ?.let { dao.deleteByUrls(it) }
            dao.linkToRemote(
                url = bookUrl,
                remoteUuid = remoteBookId,
                downloadHref = "/v1/books/$remoteBookId/download",
                coverUrl = null,
                remoteUpdatedAt = System.currentTimeMillis(),
            )
        }
        container.remoteCatalog.refreshDetached()
    }

    /**
     * The book as a file OkHttp can stream, and whether it is a copy to
     * clean up afterwards.
     *
     * A book picked with **Open book** lives behind a content URI that
     * only a stream can be had from, so it is spooled into the cache;
     * one found in a library folder is usually a real file already and
     * is sent where it lies.
     */
    private suspend fun localFile(uri: String): Pair<File, Boolean>? = withContext(Dispatchers.IO) {
        val parsed = uri.toUri()
        if (parsed.scheme == "file") {
            val file = parsed.path?.let(::File)
            return@withContext file?.takeIf { it.isFile }?.let { it to false }
        }
        val spool = File.createTempFile("upload", ".epub", applicationContext.cacheDir)
        runCatching {
            applicationContext.contentResolver.openInputStream(parsed).use { input ->
                input ?: error("no stream for $uri")
                spool.outputStream().use(input::copyTo)
            }
        }.fold(
            onSuccess = { spool to true },
            onFailure = {
                Log.w(TAG, "could not read $uri", it)
                spool.delete()
                null
            },
        )
    }

    private fun giveUp(why: String): Result {
        Log.w(TAG, why)
        return Result.failure()
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
