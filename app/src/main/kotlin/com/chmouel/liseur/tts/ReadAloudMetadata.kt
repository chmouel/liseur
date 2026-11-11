package com.chmouel.liseur.tts

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.navigator.media.common.MediaMetadataFactory
import org.readium.navigator.media.common.MediaMetadataProvider
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.coverFitting
import java.io.File

/**
 * What the read aloud notification shows: the book's title, author and
 * cover. Every chapter links to one cover file. Readium's default copies
 * the cover image into each chapter's metadata, and Media3 copies it
 * again, so a book with hundreds of chapters ran out of memory before
 * the first sentence was spoken.
 */
internal class ReadAloudMetadata(private val cover: Uri?) : MediaMetadataProvider {

    override fun createMetadataFactory(publication: Publication): MediaMetadataFactory =
        object : MediaMetadataFactory {
            private val title = publication.metadata.title
            private val author = publication.metadata.authors.firstOrNull { it.name.isNotBlank() }?.name

            override suspend fun publicationMetadata(): MediaMetadata =
                base().setTotalTrackCount(publication.readingOrder.size).build()

            override suspend fun resourceMetadata(index: Int): MediaMetadata =
                base().setTrackNumber(index).build()

            private fun base() = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(author)
                .setArtworkUri(cover)
        }

    companion object {
        private const val COVER_PX = 400
        private const val DIRECTORY = "read-aloud-cover"
        private val writing = Mutex()

        /**
         * Writes the cover for a new session and returns where it is, or
         * null when the book has none. Each session gets its own name,
         * because Media3 caches artwork by address and would otherwise
         * keep showing the previous book's cover; the previous file goes.
         * A session that has ended stops before clearing, so it cannot
         * remove the cover its replacement just wrote.
         */
        suspend fun writeCover(context: Context, publication: Publication): Uri? = withContext(Dispatchers.IO) {
            val bitmap = runCatching { publication.coverFitting(Size(COVER_PX, COVER_PX)) }.getOrNull()
                ?: return@withContext null
            writing.withLock {
                ensureActive()
                runCatching {
                    val directory = File(context.cacheDir, DIRECTORY)
                    directory.listFiles()?.forEach(File::delete)
                    directory.mkdirs()
                    val file = File(directory, "${System.nanoTime()}.jpg")
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    Uri.fromFile(file)
                }.getOrNull()
            }
        }
    }
}
