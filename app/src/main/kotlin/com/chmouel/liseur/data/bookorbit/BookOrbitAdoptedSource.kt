package com.chmouel.liseur.data.bookorbit

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.library.openableUri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The local file of a book that was uploaded to BookOrbit rather than
 * downloaded from it, and whether it still holds the bytes that went up.
 *
 * A downloaded book's file is the app's own and is named after the server
 * id; nothing but a download writes it. An uploaded book keeps whatever
 * file it had: one in the app's store, or a document in a folder the
 * reader chose, which anything on the phone can replace. The binding's
 * `local_sha256` says what that file was when the server took it, and
 * this is the one place that compares a file against it.
 *
 * A digest is a full read of the book. A position is pushed on every page
 * turn, so a match is remembered for [VERIFIED_FOR_MS] while the file's
 * size and modification time stay the same; any change to either, or a
 * new process, reads the file again.
 */
class BookOrbitAdoptedSource(
    private val context: Context?,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    /** What can be learnt about a file without reading it. */
    data class Stamp(val size: Long?, val modifiedAt: Long?)

    private data class Verified(val uri: String, val sha256: String, val stamp: Stamp, val at: Long)

    private val verified = ConcurrentHashMap<String, Verified>()

    /** The size and modification time [uri] reports now, or null when it cannot be read. */
    fun stamp(uri: Uri): Stamp? = when (uri.scheme) {
        "file" -> uri.path?.let(::File)?.takeIf { it.isFile }?.let { Stamp(it.length(), it.lastModified()) }
        "content" -> runCatching {
            context?.contentResolver?.query(
                uri,
                arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
                // Zero is how a provider says it does not know.
                val modified = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    .takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)?.takeIf { it > 0 }
                Stamp(size, modified)
            }
        }.getOrNull()
        else -> null
    }

    /** Opens [uri] for reading, or null. */
    fun open(uri: Uri): InputStream? = when (uri.scheme) {
        "file" -> uri.path?.let(::File)?.takeIf { it.isFile }?.inputStream()
        "content" -> runCatching { context?.contentResolver?.openInputStream(uri) }.getOrNull()
        else -> null
    }

    /**
     * Whether [uri] holds the bytes that hash to [expected].
     *
     * Remembered only against the stamp it was read under, and only
     * for a while: a stamp that could not be read, or that changed, is
     * read again in full.
     */
    suspend fun holds(bookUrl: String, uri: Uri, expected: String): Boolean =
        withContext(Dispatchers.IO) {
            val before = stamp(uri) ?: return@withContext false
            val known = verified[bookUrl]
            if (known != null && known.uri == uri.toString() && known.sha256 == expected &&
                known.stamp == before && before.size != null && before.modifiedAt != null &&
                now() - known.at < VERIFIED_FOR_MS
            ) return@withContext true
            val actual = try {
                open(uri)?.use(BookOrbitUploadClient::sha256Of)
            } catch (_: IOException) {
                null
            }
            val matches = actual == expected && stamp(uri) == before
            if (matches) {
                verified[bookUrl] = Verified(uri.toString(), expected, before, now())
            } else {
                verified.remove(bookUrl)
            }
            matches
        }

    /**
     * For a book adopted from an upload, whether its file still holds the
     * bytes the server took. A book downloaded from BookOrbit has no
     * recorded digest and always passes; a missing binding or book does not.
     */
    suspend fun holdsUploaded(database: LiseurDatabase, context: BookOrbitCfiContext): Boolean? {
        val binding = database.bookOrbitBindingDao().get(context.request.accountKey, context.bookUrl)
            ?: return null
        val expected = binding.localSha256 ?: return true
        val book = database.bookDao().getByUrl(context.bookUrl) ?: return null
        val source = book.openableUri()?.toUri() ?: return false
        return holds(context.bookUrl, source, expected)
    }

    /** A private copy of [uri] in [dir], or null. The caller deletes it. */
    fun spool(uri: Uri, dir: File): File? {
        dir.mkdirs()
        val target = File.createTempFile("opened", ".epub", dir)
        val copied = try {
            open(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } != null
        } catch (_: IOException) {
            false
        }
        if (!copied) {
            target.delete()
            return null
        }
        return target
    }

    private companion object {
        const val VERIFIED_FOR_MS = 5 * 60 * 1000L
    }
}
