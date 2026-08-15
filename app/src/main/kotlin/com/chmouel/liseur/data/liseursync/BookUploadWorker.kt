package com.chmouel.liseur.data.liseursync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.chmouel.liseur.container
import com.chmouel.liseur.data.remote.RemoteUrl
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncFailure
import java.util.UUID
import kotlinx.coroutines.delay

/**
 * Uploads one local EPUB to the connected liseur-sync server, then waits
 * for the server to catalogue it.
 *
 * Everything about the run is derivable, which is what makes retrying
 * it safe: the idempotency key is the book's digest, so a rescheduled
 * attempt replays to the same ingest job however far the last one got —
 * and a lost answer mid-poll costs nothing but asking again. The local
 * file is never touched, whatever the outcome.
 */
class BookUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.container
        val bookUrl = inputData.getString(BookUploadRepository.KEY_BOOK_URL)
            ?: return giveUp("no book url")
        val book = container.database.bookDao().getByUrl(bookUrl)
            ?: return giveUp("unknown book $bookUrl")
        val file = book.openableUrl?.let { android.net.Uri.parse(it) }
            ?: return giveUp("book has no file on this device")

        val server = container.remoteAccount.current()
            ?.takeIf { it.kind == ServerKind.LISEUR_SYNC }
            ?: return giveUp("the connected server is not liseur-sync")
        if (!server.canUpload) return giveUp("this token may not upload")
        val credentials = server.credentials ?: return giveUp("no credentials")

        val client = LiseurSyncUploadClient()
        val fingerprint = container.bookFingerprints.of(book)
            ?: return giveUp("the file could not be fingerprinted")

        // The key is derived, never drawn: the same file on the same
        // device names the same job, so WorkManager retrying this work
        // — or the reader asking twice — continues the one upload.
        val idempotencyKey = UUID.nameUUIDFromBytes(
            "${container.deviceIdentity.current().id}|${fingerprint.sha256}".toByteArray(),
        ).toString()

        val library = try {
            client.manageableLibraries(server.baseUrl, credentials).firstOrNull()?.first
        } catch (e: UploadException) {
            return fail(e)
        } catch (e: Exception) {
            return fail(e)
        } ?: return fail(SyncFailure.Forbidden)

        val filename = book.title
            .replace(Regex("[^\\p{L}\\p{N} ._-]+"), "")
            .trim()
            .ifBlank { "book" } + ".epub"

        val jobId = try {
            client.upload(
                baseUrl = server.baseUrl,
                credentials = credentials,
                resolver = applicationContext.contentResolver,
                library = library,
                file = file,
                filename = filename,
                idempotencyKey = idempotencyKey,
            )
        } catch (e: UploadException) {
            return fail(e)
        }

        // Poll until the ingest passes settle. A run that dies here —
        // process killed, retries exhausted — leaves the job on the
        // server, where the next attempt's replayed key finds it again.
        var attempts = 0
        while (attempts++ < MAX_POLLS) {
            val outcome = try {
                client.job(server.baseUrl, credentials, jobId)
            } catch (e: UploadException) {
                return fail(e)
            }
            when (outcome) {
                is IngestOutcome.Promoted -> {
                    link(book.url, outcome.bookId, server.baseUrl)
                    container.bookUploads.report(bookUrl, UploadOutcome.Done)
                    return Result.success()
                }
                is IngestOutcome.Refused -> {
                    Log.i(TAG, "The server refused the upload: ${outcome.reason}")
                    container.bookUploads.report(bookUrl, UploadOutcome.Refused(outcome.reason))
                    return Result.failure()
                }
                null -> delay(POLL_DELAY_MS)
            }
        }
        // Ingest is normally seconds; a server that has not answered in
        // minutes is busy or wedged, and the work is retried rather than
        // declared lost.
        return Result.retry()
    }

    /**
     * Points the book's own row at the catalog entry the upload made.
     *
     * The row keeps its URL — reading positions and annotations hang off
     * it — and gains the server's identity for the book instead, which
     * is what stops the next catalog refresh adding a second row for
     * the same file. The catalog's fields (cover, series) are filled in
     * by that refresh.
     */
    private suspend fun link(bookUrl: String, bookId: String, baseUrl: String) {
        applicationContext.container.database.bookDao().linkToRemote(
            url = bookUrl,
            remoteUuid = bookId,
            downloadHref = "/v1/books/$bookId/download",
            coverUrl = RemoteUrl.resolve(baseUrl, "/v1/books/$bookId/cover"),
            remoteUpdatedAt = System.currentTimeMillis(),
        )
        // Name the book to the sync side right away, so the position
        // already on this device starts moving on the next run rather
        // than waiting for the resolve budget to reach it.
        applicationContext.container.let { container ->
            val server = container.remoteAccount.current() ?: return
            val credentials = server.credentials ?: return
            val book = container.database.bookDao().getByUrl(bookUrl) ?: return
            runCatching {
                container.workResolver.resolve(book, server.accountKey, server.baseUrl, credentials)
            }
        }
    }

    private fun giveUp(why: String): Result {
        Log.w(TAG, "upload not started: $why")
        return Result.failure()
    }

    private fun fail(error: UploadException): Result = fail(error.reason)

    private fun fail(reason: SyncFailure): Result {
        Log.w(TAG, "upload failed: $reason")
        return when (reason) {
            // The network is the ordinary case, and worth another go.
            SyncFailure.Offline, SyncFailure.Timeout -> Result.retry()
            else -> Result.failure()
        }
    }

    private fun fail(error: Exception): Result {
        Log.w(TAG, "upload failed", error)
        return Result.failure()
    }

    private companion object {
        const val TAG = "LiseurUpload"
        const val POLL_DELAY_MS = 2_000L

        /** Two minutes of polling; longer than any healthy ingest takes. */
        const val MAX_POLLS = 60
    }
}
