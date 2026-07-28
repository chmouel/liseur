package com.chmouel.liseur.data.calibre

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Why a download did not finish, in terms the library can explain. */
sealed interface DownloadFailure {
    /** The account is not allowed to download books from this server. */
    data object NotAllowed : DownloadFailure

    /** The book is no longer downloadable from the catalog. */
    data object Gone : DownloadFailure

    data class Network(val message: String) : DownloadFailure
}

sealed interface DownloadOutcome {
    data class Done(val file: File) : DownloadOutcome
    data class Failed(val reason: DownloadFailure) : DownloadOutcome
}

/**
 * Fetches a book file from calibre-web.
 *
 * The bytes go to a `.part` file first and are only moved into place once
 * the server says the transfer is complete, so a download killed halfway
 * can never leave something that looks like a readable book. A partial
 * file is kept and resumed with a `Range` request, guarded by the ETag
 * so a book edited on the server is fetched again rather than stitched
 * together from two different versions.
 */
class BookDownloader(private val http: CalibreHttp = CalibreHttp()) {

    suspend fun download(
        url: String,
        credentials: CalibreCredentials,
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

        val request = http.request(url, credentials)
            .apply {
                if (resumeFrom > 0) {
                    header("Range", "bytes=$resumeFrom-")
                    header("If-Range", etagFile.readText())
                }
            }
            .build()

        try {
            http.client.newCall(request).execute().use { response ->
                when (response.code) {
                    401, 403 -> return@withContext DownloadOutcome.Failed(DownloadFailure.NotAllowed)
                    404, 410 -> return@withContext DownloadOutcome.Failed(DownloadFailure.Gone)
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
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            written += read
                            sinceReport += read
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
            DownloadOutcome.Failed(DownloadFailure.Network(e.message ?: "The download stopped"))
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val REPORT_EVERY = 256 * 1024
    }
}
