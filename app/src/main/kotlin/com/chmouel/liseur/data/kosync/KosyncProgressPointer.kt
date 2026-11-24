package com.chmouel.liseur.data.kosync

import android.net.Uri
import android.util.Log
import com.chmouel.liseur.data.bookorbit.BookOrbitEpubPackage
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.library.openableUri
import com.chmouel.liseur.domain.BookFingerprints
import com.chmouel.liseur.reader.ResourceAddress
import com.chmouel.liseur.reader.progress.ExactLocatorAnchor
import com.chmouel.liseur.reader.progress.ViewportTextAnchor
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/**
 * The `progress` string a push can carry instead of a percentage: a CRe
 * XPath, derived from the stored exact anchor in the book's own file
 * (issue #561).
 *
 * Only an exact locator can become one — a progression says how far
 * through the book the reader is, never which words they were on, and
 * turning one into the other would claim a precision nobody measured
 * (liseur-sync ADR-0059). Everything else answers null, which the caller
 * sends as the percentage it always has; a push never fails here.
 *
 * **Only `file://`.** A `content://` book would have to be copied out of
 * its provider to be opened as an archive, which is a whole file per
 * push; those keep the percentage for now.
 *
 * **The file is proved to be the document being pushed.** kosync names a
 * document by KOReader's partial MD5 of the file, so the same partial MD5
 * before and after the read is the same document to every kosync peer. It
 * is also checked against [document], the hash the push itself uses, and
 * the book row is re-read at the end: a pointer is only produced from
 * bytes whose kosync identity is the one uploaded.
 */
class KosyncProgressPointer(
    private val bookDao: BookDao,
    /** The file's own account of itself; swapped in tests that need it to move. */
    private val stamp: (File) -> FileStamp? = ::stampOf,
) : KosyncPointerSource {

    override suspend fun pointer(book: Book, document: String, locatorJson: String?): String? =
        withContext(Dispatchers.IO) {
            try {
                derive(book, document, locatorJson)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.d(TAG, "No CRe XPath for this position: $e")
                null
            }
        }

    private suspend fun derive(book: Book, document: String, locatorJson: String?): String? {
        val locator = locatorJson
            ?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
            ?: return null
        if (!ExactLocatorAnchor.isExact(locator)) return null
        val anchor = ExactLocatorAnchor.anchorIn(locator) ?: return null
        val file = localFile(book) ?: return null
        val href = ResourceAddress.canonicalPath(locator.href.toString()) ?: return null

        val before = stamp(file) ?: return null
        if (BookFingerprints.partialMd5(file) != document) return null
        val xpath = xpathIn(file, href, anchor) ?: return null
        if (BookFingerprints.partialMd5(file) != document) return null
        if (stamp(file) != before) return null
        val current = bookDao.getByUrl(book.url) ?: return null
        if (!current.sameGeneration(book) || current.openableUri() != book.openableUri()) return null
        return xpath
    }

    private fun localFile(book: Book): File? {
        val uri = Uri.parse(book.openableUri() ?: return null)
        if (uri.scheme != "file") return null
        return uri.path?.let(::File)?.takeIf(File::isFile)
    }

    private fun xpathIn(file: File, href: String, anchor: ViewportTextAnchor): String? {
        val spine = BookOrbitEpubPackage.parse(file).spine
        // A DocFragment index counts every itemref, linear="no" ones included.
        val fragment = spine.indexOfFirst { it.href == href } + 1
        if (fragment == 0) return null
        return ZipFile(file).use { zip ->
            chapter(zip, href)?.let { CreXPath.forAnchor(it, fragment, anchor) }
        }
    }

    private fun chapter(zip: ZipFile, href: String): ByteArray? {
        val entry = zip.getEntry(href) ?: return null
        if (entry.isDirectory || entry.size > BookOrbitEpubPackage.MAX_XML_BYTES) return null
        zip.getInputStream(entry).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > BookOrbitEpubPackage.MAX_XML_BYTES) return null
            }
            return out.toByteArray()
        }
    }

    private companion object {
        const val TAG = "kosync"
        const val BUFFER = 8192
    }
}

/** What a file says about itself: enough to notice it being replaced under a read. */
data class FileStamp(val length: Long, val modified: Long)

/** A file's size and modification time; null when it cannot be stat'ed. */
internal fun stampOf(file: File): FileStamp? =
    FileStamp(file.length(), file.lastModified()).takeIf { it.modified != 0L }
