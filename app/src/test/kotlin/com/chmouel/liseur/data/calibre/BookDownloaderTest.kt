package com.chmouel.liseur.data.calibre

import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `a redirect the catalog refuses fails the download for good`() = runTest {
        val outcome = failingWith(RemoteHttpFailure(SyncFailure.InsecureTransport))
            .download(request, folder.root.resolve("book.epub"))

        assertEquals(DownloadOutcome.Failed(DownloadFailure.Gone), outcome)
    }

    @Test
    fun `a dropped connection is retried as network`() = runTest {
        val outcome = failingWith(IOException("reset"))
            .download(request, folder.root.resolve("book.epub"))

        assertEquals(DownloadOutcome.Failed(DownloadFailure.Network("reset")), outcome)
    }

    @Test
    fun `a partial file from another address is not continued`() = runTest {
        val sent = mutableListOf<Request>()
        val downloader = BookDownloader(
            RemoteHttp(
                OkHttpClient.Builder()
                    .addInterceptor(
                        Interceptor { chain ->
                            sent += chain.request()
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .header("ETag", "\"same\"")
                                .body("whole".toResponseBody())
                                .build()
                        },
                    )
                    .build(),
            ),
        )
        val target = folder.root.resolve("book.epub")
        val other = Request.Builder().url("https://books.example/advanced.epub")
        folder.root.resolve("book.epub.part").writeText("compat")
        folder.root.resolve("book.epub.etag").writeText(
            sourceOf("https://books.example/compatible.epub") + "\n\"same\"",
        )

        downloader.download(other, target)

        assertNull(sent.single().header("Range"))
        assertEquals("whole", target.readText())
    }
}
