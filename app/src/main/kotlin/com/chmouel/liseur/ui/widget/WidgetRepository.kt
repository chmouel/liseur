package com.chmouel.liseur.ui.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.chmouel.liseur.MainActivity
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.db.ReadingSessionDao
import com.chmouel.liseur.data.library.openableUri
import com.chmouel.liseur.domain.SessionSpan
import com.chmouel.liseur.domain.StatsBook
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.domain.localeWeekStart
import com.chmouel.liseur.reader.ReaderActivity
import com.chmouel.liseur.ui.stats.DurationParts
import com.chmouel.liseur.ui.stats.durationParts
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What every homescreen widget needs, read once per update.
 *
 * Device-local only: the widget never waits on the network.
 */
data class WidgetSnapshot(
    val book: WidgetBook?,
    val stats: WidgetStats,
)

data class WidgetBook(
    val url: String,
    val title: String,
    val author: String?,
    val progression: Double?,
    val cover: Bitmap?,
    val initials: String,
    val openIntent: Intent,
)

data class WidgetStats(
    val figures: PeriodStats,
    val totalLabel: String,
    /** The tallest bar's time, drawn as the chart's scale; null when nothing was read. */
    val peakLabel: String?,
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
) {
    /**
     * Every session is read, not just the period's: the streak is counted
     * over the whole history before the window is applied.
     */
    suspend fun load(
        context: Context,
        period: WidgetPeriod = WidgetPeriod.Default,
    ): WidgetSnapshot = withContext(Dispatchers.IO) {
        val zone = zone()
        val today = today(zone)
        val weekStart = weekStart()
        val book = bookDao.mostRecentlyOpened()
        val progress = book?.let { progressDao.get(it.url)?.totalProgression }
        val sessions = sessionDao.allOnce()
        val books = bookDao.allOnce()
        val progressions = progressDao.getAll()
            .associateBy({ it.bookUrl }, { it.totalProgression })
        val statsBooks = books.associate { row ->
            row.url to StatsBook(
                bookUrl = row.url,
                title = row.displayTitle,
                author = row.displayAuthor,
                progression = progressions[row.url],
                finished = row.finished,
                coverPath = row.coverPath,
                coverUrl = row.coverUrl,
            )
        }
        val spans = sessions.map { session ->
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
        val stats = periodStats(
            sessions = spans,
            books = statsBooks,
            zone = zone,
            today = today,
            weekStart = weekStart,
            period = period,
        )
        WidgetSnapshot(
            book = book?.toWidgetBook(context, progress),
            stats = stats.toWidgetStats(context),
        )
    }

    private fun Book.toWidgetBook(context: Context, progression: Double?): WidgetBook {
        val fileUrl = openableUri()
        val open = if (fileUrl != null) {
            ReaderActivity.intent(context, fileUrl, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } else {
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return WidgetBook(
            url = url,
            title = displayTitle,
            author = displayAuthor,
            progression = progression,
            cover = coverPath?.let(decodeCover),
            initials = coverInitials(displayTitle),
            openIntent = open,
        )
    }
}

fun PeriodStats.toWidgetStats(context: Context): WidgetStats = WidgetStats(
    figures = this,
    totalLabel = formatReadingDuration(context, totalMs),
    peakLabel = peakMs.takeIf { it > 0 }?.let { formatCompactDuration(context, it) },
)

fun formatCompactDuration(context: Context, millis: Long): String = when (val parts = compactDuration(millis)) {
    CompactDuration.UnderMinute -> context.getString(R.string.widget_duration_under_minute)
    is CompactDuration.Minutes -> context.getString(R.string.widget_duration_minutes, parts.minutes)
    is CompactDuration.Hours -> if (parts.minutes == 0) {
        context.getString(R.string.widget_duration_hours, parts.hours)
    } else {
        context.getString(R.string.widget_duration_hours_minutes, parts.hours, parts.minutes)
    }
}

fun formatReadingDuration(context: Context, millis: Long): String = when (val parts = durationParts(millis)) {
    DurationParts.None -> context.getString(R.string.duration_none)
    DurationParts.UnderMinute -> context.getString(R.string.duration_under_minute)
    is DurationParts.Minutes -> context.getString(R.string.duration_minutes, parts.minutes)
    is DurationParts.Hours -> if (parts.minutes == 0) {
        context.getString(R.string.duration_hours, parts.hours)
    } else {
        context.getString(R.string.duration_hours_minutes, parts.hours, parts.minutes)
    }
    is DurationParts.Days -> if (parts.hours == 0) {
        context.getString(R.string.duration_days, parts.days)
    } else {
        context.getString(R.string.duration_days_hours, parts.days, parts.hours)
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
