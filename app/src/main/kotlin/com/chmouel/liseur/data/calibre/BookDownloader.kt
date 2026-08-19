package com.chmouel.liseur.data.calibre

import com.chmouel.liseur.data.remote.RemoteHttp
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import kotlin.coroutines.coroutineContext

/** Why a download did not finish, in terms the library can explain. */
sealed interface DownloadFailure {
    /** The account is not allowed to download books from this server. */
    data object NotAllowed : DownloadFailure

    /** The book is no longer downloadable from the catalog. */
    data object Gone : DownloadFailure

    /** There is no room left on the device for the rest of the file. */
    data object OutOfSpace : DownloadFailure

    data class Network(val message: String) : DownloadFailure
}

sealed interface DownloadOutcome {
    data class Done(val file: File) : DownloadOutcome
    data class Failed(val reason: DownloadFailure) : DownloadOutcome
}

/**
 * Fetches a book file from a server.
 *
 * The bytes go to a `.part` file first and are only moved into place once
 * the server says the transfer is complete, so a download killed halfway
 * can never leave something that looks like a readable book. A partial
 * file is kept and resumed with a `Range` request, guarded by the ETag
 * so a book edited on the server is fetched again rather than stitched
 * together from two different versions.
 */
class BookDownloader(
    private val http: RemoteHttp = RemoteHttp(),
    /**
     * How many bytes are still free where the file is being written, or
     * null to write without looking.
     *
     * Checked as the bytes go down rather than once before they start,
     * because several downloads can run at once and a check made before
     * writing is exactly the check they all pass together. Nothing needs
     * to be coordinated between them: this is not a prediction but a
     * reading, and every sibling's part-file has already been taken out
     * of it by the time it is read.
     */
    private val freeSpace: (() -> Long)? = null,
) {

    suspend fun download(
        request: Request.Builder,
        target: File,
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val partial = File(target.parentFile, target.name + ".part")
        val etagFile = File(target.parentFile, target.name + ".etag")
        target.parentFile?.mkdirs()

        val resumeFrom = if (partial.exists() && etagFile.exists()) partial.length() else 0L
        if (resumeFrom == 0L) {
            partial.delete()
            etagFile.delete()
        }

        val call = request
            .apply {
                if (resumeFrom > 0) {
                    header("Range", "bytes=$resumeFrom-")
                    header("If-Range", etagFile.readText())
                }
            }
            .build()

        try {
            http.client.newCall(call).execute().use { response ->
                when (response.code) {
                    401, 403 -> return@withContext DownloadOutcome.Failed(DownloadFailure.NotAllowed)
                    404, 410 -> return@withContext DownloadOutcome.Failed(DownloadFailure.Gone)
                    // The book's bytes changed on the server since they
                    // were catalogued (liseur-sync in-place storage): the
                    // file this row describes is gone, not unreachable.
                    409 -> return@withContext DownloadOutcome.Failed(DownloadFailure.Gone)
                }
                if (!response.isSuccessful) {
                    return@withContext DownloadOutcome.Failed(
                        DownloadFailure.Network("Server answered ${response.code}"),
                    )
                }

                // 206 continues the partial file; 200 means the server sent
                // the whole book again, so anything kept must be thrown away.
                val appending = response.code == 206
                if (!appending) partial.delete()

                response.header("ETag")?.let { etagFile.writeText(it) }

                val body = response.body
                    ?: return@withContext DownloadOutcome.Failed(
                        DownloadFailure.Network("The server sent no book"),
                    )
                val alreadyHave = if (appending) resumeFrom else 0L
                val total = body.contentLength().takeIf { it > 0 }?.plus(alreadyHave)

                body.byteStream().use { input ->
                    java.io.FileOutputStream(partial, appending).use { output ->
                        val buffer = ByteArray(BUFFER)
                        var written = alreadyHave
                        var sinceReport = 0L
                        var sinceSpaceCheck = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            written += read
                            sinceReport += read
                            sinceSpaceCheck += read
                            if (sinceSpaceCheck >= CHECK_SPACE_EVERY) {
                                sinceSpaceCheck = 0
                                if (outOfSpace()) {
                                    return@withContext DownloadOutcome.Failed(
                                        DownloadFailure.OutOfSpace,
                                    )
                                }
                            }
                            if (sinceReport >= REPORT_EVERY) {
                                onProgress(written, total)
                                sinceReport = 0
                            }
                        }
                        output.flush()
                        onProgress(written, total)
                    }
                }
            }

            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                return@withContext DownloadOutcome.Failed(
                    DownloadFailure.Network("Could not save the book"),
                )
            }
            etagFile.delete()
            DownloadOutcome.Done(target)
        } catch (e: IOException) {
            // A periodic check can be overtaken between two readings,
            // and the filesystem gets the last word either way. Telling
            // this apart from a dropped connection matters: one is worth
            // retrying and the other is worth stopping for.
            if (isOutOfSpace(e)) {
                DownloadOutcome.Failed(DownloadFailure.OutOfSpace)
            } else {
                DownloadOutcome.Failed(DownloadFailure.Network(e.message ?: "The download stopped"))
            }
        }
    }

    private fun outOfSpace(): Boolean {
        val free = freeSpace?.invoke() ?: return false
        return free < BULK_DOWNLOAD_RESERVE_BYTES
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val REPORT_EVERY = 256 * 1024
        const val CHECK_SPACE_EVERY = 4L * 1024 * 1024
    }
}

/**
 * Whether the filesystem, rather than the network, is what stopped a
 * write.
 *
 * There is no typed exception for it: `ENOSPC` surfaces as a plain
 * [IOException] whose message the C library wrote, so the message is all
 * there is to go on. Matched loosely and case-insensitively, and read as
 * a hint — a miss falls back to treating it as a network failure, which
 * is retried, and a device that is genuinely full will simply say so
 * again.
 */
internal fun isOutOfSpace(e: IOException): Boolean {
    val message = generateSequence(e as Throwable) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" ")
        .lowercase()
    return "enospc" in message ||
        "no space left" in message ||
        "not enough space" in message ||
        "disk full" in message
}
