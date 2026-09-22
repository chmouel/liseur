package com.chmouel.liseur.data.calibre

import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * How [BookDownloader] reports a request that failed before it left.
 *
 * A BookOrbit download asks for a token inside an interceptor. When the
 * session cannot be renewed, that refusal must stop the download rather
 * than leave it queued and retrying forever.
 */
class BookDownloaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun failingWith(error: IOException) = BookDownloader(
        RemoteHttp(
            OkHttpClient.Builder()
                .addInterceptor(Interceptor { throw error })
                .build(),
        ),
    )

    private val request = Request.Builder().url("https://books.example/api/v1/books/files/1/download")

    @Test
    fun `an unrenewable session fails the download as authentication`() = runTest {
        val outcome = failingWith(RemoteHttpFailure(SyncFailure.Unauthorised))
            .download(request, folder.root.resolve("book.epub"))

        assertEquals(DownloadOutcome.Failed(DownloadFailure.Authentication), outcome)
    }

    @Test
    fun `any other failure before the request is retried as network`() = runTest {
        val outcome = failingWith(RemoteHttpFailure(SyncFailure.ServerError(503)))
            .download(request, folder.root.resolve("book.epub"))

        assertTrue((outcome as DownloadOutcome.Failed).reason is DownloadFailure.Network)
    }

    @Test
    fun `a dropped connection is retried as network`() = runTest {
        val outcome = failingWith(IOException("reset"))
            .download(request, folder.root.resolve("book.epub"))

        assertEquals(DownloadOutcome.Failed(DownloadFailure.Network("reset")), outcome)
    }
}
