package com.chmouel.liseur.translate

import com.chmouel.liseur.AppContainer
import android.content.Context
import com.chmouel.liseur.data.library.openableUri
import com.chmouel.liseur.data.library.permissionSafeAssetRetriever
import com.chmouel.liseur.reader.PublicationContext
import androidx.core.net.toUri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.use
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.ContainerAsset
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/** No network-capable resource factory is reachable from translation's parser or content services. */
internal class TranslationPublicationOpener(context: Context) {
    private val localOnly = object : HttpClient {
        override suspend fun stream(request: HttpRequest): Try<HttpStreamResponse, HttpError> =
            Try.failure(HttpError.IO(IOException("External EPUB resources are not allowed")))
    }
    private val retriever = permissionSafeAssetRetriever(context.contentResolver, localOnly)
    private val opener = PublicationOpener(DefaultPublicationParser(context, localOnly, retriever, pdfFactory = null))

    suspend fun <T> withPublication(url: AbsoluteUrl, block: suspend (Publication) -> T): T {
        if (url.toString().toUri().scheme !in setOf("file", "content")) throw IOException("Book is not local")
        val asset = retriever.retrieve(url).getOrElse { throw IOException("Cannot open the book") }
        try {
            if (asset !is ContainerAsset || asset.format.mediaType.toString() != "application/epub+zip") {
                throw TranslationError.Unsupported()
            }
            val publication = opener.open(asset, allowUserInteraction = false).getOrElse { throw IOException("Cannot read the EPUB") }
            try {
                if (publication.metadata.layout == Layout.FIXED || publication.readingOrder.isEmpty()) {
                    throw TranslationError.Unsupported()
                }
                val entries = asset.container.entries
                for (link in publication.readingOrder) {
                    val href = link.href.resolve().removeFragment()
                    if (href is AbsoluteUrl || href.toString().startsWith("//") || href !in entries) {
                        throw IOException("An EPUB chapter is not embedded in the archive")
                    }
                }
                return block(publication)
            } finally {
                publication.close()
            }
        } finally {
            asset.close()
        }
    }
}

internal suspend fun <T> AppContainer.withTranslationPublication(
    bookUrl: String,
    block: suspend (Publication) -> T,
): T = withContext(Dispatchers.IO) {
    val book = database.bookDao().getByUrl(bookUrl) ?: throw IOException("Book is not in the library")
    val url = book.openableUri()?.toUri()?.toAbsoluteUrl() ?: throw IOException("Book is not downloaded")
    try {
        translationPublicationOpener.withPublication(url, block)
    } catch (e: Exception) {
        // Readium reports a cancelled read as a read error; the cancellation must win.
        ensureActive()
        throw e
    }
}

internal suspend fun Publication.translationSentences(
    source: String?,
    progress: (completed: Int, total: Int) -> Unit = { _, _ -> },
): PageSentences {
    // Readium may skip a broken resource instead of failing its content iterator.
    progress(0, readingOrder.size)
    for ((index, link) in readingOrder.withIndex()) {
        currentCoroutineContext().ensureActive()
        val readable = get(link)?.use { it.read().getOrNull() != null } ?: false
        if (!readable) throw IOException("An EPUB resource cannot be read")
        progress(index + 1, readingOrder.size)
    }
    val first = readingOrder.firstOrNull()?.let(::locatorFromLink) ?: throw IOException("The EPUB has no starting point")
    return PublicationContext.sentences(this, first, source)
}
