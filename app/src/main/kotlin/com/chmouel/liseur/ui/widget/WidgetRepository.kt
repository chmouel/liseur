package com.chmouel.liseur.ui.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.library.openableUri
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.reader.ReaderActivity
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the homescreen cover widget draws, read once per update from the local library. */
data class WidgetSnapshot(
    val book: WidgetBook?,
)

/**
 * What the library widget draws: the shelf size, and the books at the
 * indices [anchor] can reach at any widget size.
 */
data class LibrarySnapshot(
    val anchor: Int,
    val count: Int,
    val books: Map<Int, WidgetBook>,
) {
    fun page(start: Int, capacity: Int): List<WidgetBook> =
        (start until minOf(start + capacity, count)).mapNotNull(books::get)
}

data class WidgetBook(
    val url: String,
    val title: String,
    val cover: Bitmap?,
    val initials: String,
    val openIntent: Intent,
)

/**
 * Builds [WidgetSnapshot] from Room. Pure mapping lives here so the
 * Glance receivers stay about layout.
 */
class WidgetRepository(
    private val bookDao: BookDao,
    private val decodeCover: (String) -> Bitmap? = ::decodeCoverBitmap,
    private val decodeShelfCover: (String) -> Bitmap? = { decodeCoverBitmap(it, maxEdge = SHELF_COVER_MAX_EDGE) },
) {
    suspend fun load(context: Context): WidgetSnapshot = withContext(Dispatchers.IO) {
        WidgetSnapshot(
            book = bookDao.mostRecentlyOpened()?.toWidgetBook(context),
        )
    }

    /** The shelf around [anchor], with every cover a widget size could show already decoded. */
    suspend fun loadLibrary(context: Context, anchor: Int): LibrarySnapshot = withContext(Dispatchers.IO) {
        val books = libraryBooks(bookDao.allOnce(), bookDao.mostRecentlyOpened())
        val featuredIndices = LIBRARY_FEATURED_CAPACITIES.mapTo(hashSetOf()) { capacity ->
            libraryPageStart(anchor, books.size, capacity)
        }
        LibrarySnapshot(
            anchor = anchor,
            count = books.size,
            books = libraryWindow(anchor, books.size).associateWith { index ->
                val decode = if (index in featuredIndices) decodeCover else decodeShelfCover
                books[index].toWidgetBook(context, decode)
            },
        )
    }

    internal fun Book.toWidgetBook(
        context: Context,
        decode: (String) -> Bitmap? = decodeCover,
    ): WidgetBook {
        val fileUrl = openableUri()
        val open = if (fileUrl != null) {
            ReaderActivity.intent(context, fileUrl, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } else {
            WidgetLaunchActivity.intent(context, bookUrl = url)
        }
        return WidgetBook(
            url = url,
            title = displayTitle,
            cover = coverPath?.let(decode),
            initials = coverInitials(displayTitle),
            openIntent = open,
        )
    }
}

/**
 * Two letters for a placeholder cover: first initials of the first two
 * words, or the first two characters of a single word.
 */
fun coverInitials(title: String): String {
    val words = title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase(Locale.getDefault())
        else -> buildString {
            append(words[0].first().uppercaseChar())
            append(words[1].first().uppercaseChar())
        }
    }
}

/**
 * Decodes a local cover JPEG, scaled to stay under the RemoteViews binder
 * budget. Remote [Book.coverUrl] is ignored: the widget process must not
 * hang on the network.
 */
fun decodeCoverBitmap(path: String, maxEdge: Int = COVER_MAX_EDGE): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    val decoded = BitmapFactory.decodeFile(path, options) ?: return null
    val longest = maxOf(decoded.width, decoded.height)
    if (longest <= maxEdge) return decoded
    val scale = maxEdge.toFloat() / longest
    val scaled = decoded.scale(
        (decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1),
    )
    if (scaled !== decoded) decoded.recycle()
    return scaled
}

private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
    var sample = 1
    var w = width
    var h = height
    while (w / 2 >= maxEdge || h / 2 >= maxEdge) {
        sample *= 2
        w /= 2
        h /= 2
    }
    return sample
}

private const val COVER_MAX_EDGE = 256

/** Up to five shelf covers per drawn size share the RemoteViews budget. */
private const val SHELF_COVER_MAX_EDGE = 160
