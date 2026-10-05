package com.chmouel.liseur.ui.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.db.ReadingSessionDao
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.db.RemoteStatsDao
import com.chmouel.liseur.data.db.RemoteStatsDay
import com.chmouel.liseur.data.library.openableUri
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.domain.SessionSpan
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.domain.localeWeekStart
import com.chmouel.liseur.reader.ReaderActivity
import java.time.DayOfWeek
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a homescreen widget draws, read once per update.
 *
 * Read from the offline cache: the widget never waits on the network. [stats] is
 * null when the widget did not ask for it.
 */
data class WidgetSnapshot(
    val book: WidgetBook?,
    val stats: WidgetStats?,
)

/**
 * What a widget draws, so a load reads only that: the cover alone needs no
 * session history, and the stats alone need no cover bitmap.
 */
enum class WidgetContent(val cover: Boolean, val stats: Boolean) {
    COVER(cover = true, stats = false),
    STATS(cover = false, stats = true),
}

data class WidgetBook(
    val url: String,
    val title: String,
    val author: String?,
    val progression: Double?,
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
    private val progressDao: ReadingProgressDao,
    private val sessionDao: ReadingSessionDao,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val today: (ZoneId) -> LocalDate = { LocalDate.now(it) },
    private val weekStart: () -> DayOfWeek = { localeWeekStart(Locale.getDefault()) },
    private val decodeCover: (String) -> Bitmap? = ::decodeCoverBitmap,
    private val serverDao: RemoteServerDao? = null,
    private val remoteStatsDao: RemoteStatsDao? = null,
) {
    suspend fun load(
        context: Context,
        content: WidgetContent = WidgetContent.STATS,
    ): WidgetSnapshot = withContext(Dispatchers.IO) {
        val book = bookDao.mostRecentlyOpened()
        val progress = book?.let { progressDao.get(it.url)?.totalProgression }
        WidgetSnapshot(
            book = book?.toWidgetBook(context, progress, withCover = content.cover),
            stats = if (content.stats) loadStats() else null,
        )
    }

    private suspend fun loadStats(): WidgetStats {
        val remote = loadRemote()
        val zone = remote?.zone ?: zone()
        val spans = sessionDao.allOnce().map { session ->
            SessionSpan(
                bookUrl = session.bookUrl,
                startedAt = session.startedAt,
                durationMs = session.durationMs,
                lastReadAt = session.endedAt ?: session.lastCheckpointAt,
                uploaded = session.uploadedAt != null,
                startProgression = session.startProgression,
                endProgression = session.endProgression,
            )
        }
        return widgetStats(
            sessions = spans,
            zone = zone,
            today = today(zone),
            weekStart = weekStart(),
            remote = remote,
        )
    }

    /** Other devices' reading, as the stats screen last proved it; null without a sync account. */
    private suspend fun loadRemote(): WidgetRemote? {
        val stats = remoteStatsDao ?: return null
        val account = serverDao?.get()?.takeIf { it.kind == ServerKind.LISEUR_SYNC } ?: return null
        val key = account.accountKey
        val zone = try {
            stats.zone(key)?.let(ZoneId::of) ?: return null
        } catch (_: DateTimeException) {
            return null
        }
        val days = stats.days(key, zone.id)
        if (days.isEmpty()) return null
        if (serverDao.get()?.accountKey != key) return null
        return widgetRemote(days).copy(zone = zone)
    }

    internal fun Book.toWidgetBook(context: Context, progression: Double?, withCover: Boolean): WidgetBook {
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
            author = displayAuthor,
            progression = progression,
            cover = if (withCover) coverPath?.let(decodeCover) else null,
            initials = coverInitials(displayTitle),
            openIntent = open,
        )
    }
}

/** Maps proven daily residuals; a row that no longer parses is skipped. */
internal fun widgetRemote(
    days: List<RemoteStatsDay>,
): WidgetRemote = WidgetRemote(
    days = days.mapNotNull { row -> row.date.toDateOrNull()?.let { it to row.residualMs } }.toMap(),
    refreshedAtByDay = days.mapNotNull { row -> row.date.toDateOrNull()?.let { it to row.refreshedAt } }.toMap(),
)

private fun String.toDateOrNull(): LocalDate? = try {
    LocalDate.parse(this)
} catch (_: DateTimeParseException) {
    null
}

fun formatCompactDuration(context: Context, millis: Long): String = when (val parts = compactDuration(millis)) {
    CompactDuration.UnderMinute -> context.getString(R.string.widget_duration_under_minute)
    is CompactDuration.Minutes -> context.getString(R.string.widget_duration_minutes, parts.minutes)
    is CompactDuration.Hours -> if (parts.minutes == 0) {
        context.getString(R.string.widget_duration_hours, parts.hours)
    } else {
        context.getString(R.string.widget_duration_hours_minutes, parts.hours, parts.minutes)
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
