package com.chmouel.liseur.data.calibre

import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.isHtmlBookResponse
import java.util.UUID
import com.chmouel.liseur.data.opds.OpdsScope
import com.chmouel.liseur.data.opds.ScopedRedirects
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.MediaType.Companion.toMediaType
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
    fun `a direct EPUB is saved even with the wrong response media type`() = runTest {
        val book = epub()
        val target = folder.root.resolve("book.epub")
        val downloader = opds { response(it, 200, book, "text/html") }
        assertEquals(DownloadOutcome.Done(target), downloader.download(scopedRequest(), target))
        assertTrue(book.contentEquals(target.readBytes()))
    }

    @Test
    fun `an OPDS redirect to an EPUB still downloads`() = runTest {
        val book = epub()
        val target = folder.root.resolve("book.epub")
        val downloader = opds {
            if (it.url.encodedPath == "/opds") {
                response(it, 302).newBuilder().header("Location", "/book.epub").build()
            } else {
                response(it, 200, book)
            }
        }
        assertEquals(DownloadOutcome.Done(target), downloader.download(scopedRequest(), target))
        assertTrue(book.contentEquals(target.readBytes()))
    }

    @Test
    fun `an OPDS redirect to HTML offers a browser without saving a book`() = runTest {
        val target = folder.root.resolve("book.epub")
        val downloader = opds {
            if (it.url.encodedPath == "/opds") {
                response(it, 301).newBuilder().header("Location", "/download.php?book=42&token=example").build()
            } else {
                response(it, 200, "\uFEFF<!-- page --> <!DOCTYPE HTML><html>".toByteArray())
            }
        }
        assertEquals(
            DownloadOutcome.Failed(DownloadFailure.BrowserRequired("https://books.example/download.php?book=42")),
            downloader.download(scopedRequest(), target),
        )
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a truncated EPUB cannot replace an existing book`() = runTest {
        val target = folder.root.resolve("book.epub").apply { writeText("old file") }
        val downloader = opds { response(it, 200, epub().dropLast(22).toByteArray()) }
        assertEquals(DownloadOutcome.Failed(DownloadFailure.InvalidEpub), downloader.download(scopedRequest(), target))
        assertEquals("old file", target.readText())
        assertTrue(!folder.root.resolve("book.epub.part").exists())
    }

    @Test
    fun `a resumed EPUB is validated as a whole`() = runTest {
        val book = epub()
        val cut = book.size / 2
        val request = scopedRequest()
        val target = folder.root.resolve("book.epub")
        folder.root.resolve("book.epub.part").writeBytes(book.copyOfRange(0, cut))
        folder.root.resolve("book.epub.etag").writeText(sourceOf(request.build().url.toString()) + "\noriginal")
        val downloader = opds {
            assertEquals("bytes=$cut-", it.header("Range"))
            assertEquals("original", it.header("If-Range"))
            response(it, 206, book.copyOfRange(cut, book.size))
        }
        assertEquals(DownloadOutcome.Done(target), downloader.download(request, target))
        assertTrue(book.contentEquals(target.readBytes()))
    }

    @Test
    fun `HTML resumed from an old partial download still offers the browser`() = runTest {
        val request = scopedRequest()
        val target = folder.root.resolve("book.epub")
        folder.root.resolve("book.epub.part").writeText("<!doc")
        folder.root.resolve("book.epub.etag").writeText(sourceOf(request.build().url.toString()) + "\noriginal")
        val downloader = opds {
            assertEquals("bytes=5-", it.header("Range"))
            response(it, 206, "type html><html>".toByteArray())
        }
        assertEquals(
            DownloadOutcome.Failed(DownloadFailure.BrowserRequired("https://books.example/opds")),
            downloader.download(request, target),
        )
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `detects a picked HTML page encoded as UTF-16`() {
        val html = "\uFEFF<!doctype html><html>"
        assertTrue(isHtmlBookResponse(html.toByteArray(Charsets.UTF_16LE)))
        assertTrue(isHtmlBookResponse(html.toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun `resolve work kept by WorkManager even when it already finished`() {
        val requested = UUID.randomUUID()
        val old = UUID.randomUUID()
        val kept = UUID.randomUUID()
        assertEquals(kept, resolveWorkId(requested, setOf(old), listOf(old, kept)))
        assertNull(resolveWorkId(requested, setOf(old), listOf(old)))
    }

    private fun opds(answer: (Request) -> Response) = BookDownloader(
        http = RemoteHttp(
            OkHttpClient.Builder().addInterceptor(ScopedRedirects())
                .addInterceptor { answer(it.request()) }.build(),
        ),
        validateOpdsEpub = true,
    )

    private fun scopedRequest(): Request.Builder {
        val url = "https://books.example/opds"
        return Request.Builder().url(url).tag(OpdsScope::class.java, checkNotNull(OpdsScope.of(url)))
    }

    private fun response(
        request: Request,
        code: Int,
        bytes: ByteArray = byteArrayOf(),
        type: String = "application/epub+zip",
    ): Response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(code).message("test").body(bytes.toResponseBody(type.toMediaType())).build()

    private fun epub(): ByteArray = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip ->
            for ((name, content) in mapOf("mimetype" to "application/epub+zip", "META-INF/container.xml" to "<container/>")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()

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
