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
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.db.RemoteStatsDao
import com.chmouel.liseur.data.db.RemoteStatsDay
import com.chmouel.liseur.data.db.RemoteStatsWindow
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.data.library.openableUri
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.domain.SessionSpan
import com.chmouel.liseur.domain.StatsBook
import com.chmouel.liseur.domain.StatsRange
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.domain.localeWeekStart
import com.chmouel.liseur.reader.ReaderActivity
import com.chmouel.liseur.ui.stats.DurationParts
import com.chmouel.liseur.ui.stats.durationParts
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a homescreen widget draws, read once per update.
 *
 * Device-local only: the widget never waits on the network. [stats] is
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
    COVER_AND_STATS(cover = true, stats = true),
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

data class WidgetStats(
    val figures: PeriodStats,
    val totalLabel: String,
    /** The tallest bar's time, drawn as the chart's scale; null when nothing was read. */
    val peakLabel: String?,
    /** What the bars say, read out by TalkBack; null when nothing was read. */
    val chartDescription: String? = null,
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
    private val identityDao: WorkIdentityDao? = null,
) {
    /**
     * Every session is read, not just the period's: the streak is counted
     * over the whole history before the window is applied.
     */
    suspend fun load(
        context: Context,
        period: WidgetPeriod = WidgetPeriod.Default,
        content: WidgetContent = WidgetContent.COVER_AND_STATS,
    ): WidgetSnapshot = withContext(Dispatchers.IO) {
        val book = bookDao.mostRecentlyOpened()
        val progress = book?.let { progressDao.get(it.url)?.totalProgression }
        WidgetSnapshot(
            book = book?.toWidgetBook(context, progress, withCover = content.cover),
            stats = if (content.stats) loadStats(period).toWidgetStats(context) else null,
        )
    }

    private suspend fun loadStats(period: WidgetPeriod): PeriodStats {
        val zone = zone()
        val progressions = progressDao.getAll()
            .associateBy({ it.bookUrl }, { it.totalProgression })
        val statsBooks = bookDao.allOnce().associate { row ->
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
        return periodStats(
            sessions = spans,
            books = statsBooks,
            zone = zone,
            today = today(zone),
            weekStart = weekStart(),
            period = period,
            remote = loadRemote(zone),
        )
    }

    /** Other devices' reading, as the stats screen last proved it; null without a sync account. */
    private suspend fun loadRemote(zone: ZoneId): WidgetRemote? {
        val stats = remoteStatsDao ?: return null
        val account = serverDao?.get()?.takeIf { it.kind == ServerKind.LISEUR_SYNC } ?: return null
        val key = account.accountKey
        val days = stats.days(key, zone.id)
        val windows = stats.windows(key, zone.id)
        if (days.isEmpty() && windows.isEmpty()) return null
        return widgetRemote(days, windows, identityDao?.aliasesFor(key).orEmpty())
    }

    private fun Book.toWidgetBook(context: Context, progression: Double?, withCover: Boolean): WidgetBook {
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
            cover = if (withCover) coverPath?.let(decodeCover) else null,
            initials = coverInitials(displayTitle),
            openIntent = open,
        )
    }
}

/** Maps stored rows to what [periodStats] adds; a row that no longer parses is skipped. */
internal fun widgetRemote(
    days: List<RemoteStatsDay>,
    windows: List<RemoteStatsWindow>,
    aliases: List<WorkAlias>,
): WidgetRemote = WidgetRemote(
    days = days.mapNotNull { row -> row.date.toDateOrNull()?.let { it to row.residualMs } }.toMap(),
    windows = windows.mapNotNull { row ->
        val range = StatsRange.entries.firstOrNull { it.id == row.rangeId } ?: return@mapNotNull null
        WidgetRemoteWindow(
            range = range,
            from = row.fromDate.toDateOrNull() ?: return@mapNotNull null,
            today = row.today.toDateOrNull() ?: return@mapNotNull null,
            sessions = row.residualSessions,
            workIds = row.workIds.split('\n').filter { it.isNotEmpty() }.toSet(),
            combinedStreak = row.combinedStreak,
        )
    },
    workIdByUrl = aliases.filter { it.usable }.associate { it.bookUrl to it.workId },
)

private fun String.toDateOrNull(): LocalDate? = try {
    LocalDate.parse(this)
} catch (_: DateTimeParseException) {
    null
}

fun PeriodStats.toWidgetStats(context: Context): WidgetStats = WidgetStats(
    figures = this,
    totalLabel = formatReadingDuration(context, totalMs),
    peakLabel = peakMs.takeIf { it > 0 }?.let { formatCompactDuration(context, it) },
    chartDescription = chartDescription(context),
)

/** The days read, in the words the in-app chart speaks for each bar. */
private fun PeriodStats.chartDescription(context: Context): String? {
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    return bars.filter { it.totalMs > 0 }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("; ") { bar ->
            context.getString(
                R.string.reading_stats_period_read,
                formatReadingDuration(context, bar.totalMs),
                format.format(bar.date),
            )
        }
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
