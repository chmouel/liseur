package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.domain.StatsRange
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.WeekFields
import org.json.JSONObject

/**
 * Reading added up across every device, as the server sees it.
 *
 * There is no range here to be wrong about: an answer is only built
 * when the server named back the exact span it was asked for, so this
 * always describes the window the screen is showing.
 */
data class InsightsSummary(
    val activeMinutes: Double,
    val sessions: Int,
    val streakDays: Int,
    /**
     * Fraction of a book got through per hour, or null when the server
     * has no forward movement to divide by.
     */
    val progressionPerHour: Double? = null,
)

/** One book, added up across every device. */
data class WorkInsights(
    val sessions: Int,
    val activeMinutes: Double,
    /** Null when the server has no speed history to divide by. */
    val etaSeconds: Double?,
    /** The newest session end, across devices. */
    val lastReadAt: Long? = null,
    /**
     * The server's name for the book.
     *
     * Carried because one work can answer for more than one local book
     * URL — a file moved keeps its reading under a new URL — and adding
     * those rows up without knowing they are the same work would charge
     * the reader twice for one evening.
     */
    val workId: String = "",
)

/** One server-timezone calendar day, added up across every device. */
data class InsightDay(val date: LocalDate, val activeMinutes: Double)

/**
 * Statistics from a liseur-sync server, when there are any.
 *
 * Every answer here is decoration. The dashboard is built from this
 * device's own sessions and stands on its own; what this adds is the
 * reading done on the reader's other devices, which no amount of local
 * arithmetic can produce. So every failure is null and silent: a
 * statistics screen is not worth an error banner, and a reader offline
 * on a train should see their own figures rather than a complaint.
 *
 * The token needs the `read-insights` scope; one minted without it is
 * refused, which lands in the same silent null as being offline.
 */
class LiseurSyncInsights(
    private val serverDao: RemoteServerDao,
    private val identityDao: WorkIdentityDao,
    private val http: LiseurSyncHttp = LiseurSyncHttp(),
) {

    suspend fun summary(
        range: StatsRange = StatsRange.Default,
        today: LocalDate,
        weekStart: DayOfWeek = WeekFields.ISO.firstDayOfWeek,
    ): InsightsSummary? {
        val account = account() ?: return null
        val credentials = account.credentials ?: return null
        val from = range.startDate(today, weekStart)
        val answer = try {
            http.get(
                LiseurSyncApi.insightsSummary(account.baseUrl, from, today),
                credentials,
            )
        } catch (e: IOException) {
            Log.i(TAG, "No statistics from the server this time", e)
            return null
        }
        if (!answer.covers(from, today)) return null
        return InsightsSummary(
            activeMinutes = answer.optDouble("total_active_minutes", 0.0),
            sessions = answer.optInt("sessions"),
            streakDays = answer.optInt("streak_days"),
            progressionPerHour = answer.optDouble("speed_prog_per_hour")
                .takeIf { it.isFinite() && it > 0 },
        ).takeIf { it.sessions > 0 || it.activeMinutes > 0 }
    }

    /**
     * One book, if this server has a name for it and something to say.
     *
     * A book nobody has finished reading anywhere has no speed history
     * to divide the remainder by, and the server says so with a null
     * rather than a guess. That null is carried through untouched: an
     * invented "about four hours left" is worse than no estimate.
     */
    suspend fun forBook(bookUrl: String): WorkInsights? {
        val account = account() ?: return null
        val credentials = account.credentials ?: return null
        val alias = identityDao.alias(bookUrl, account.accountKey)?.takeIf { it.usable } ?: return null
        val answer = try {
            http.get(LiseurSyncApi.workInsights(account.baseUrl, alias.workId), credentials)
        } catch (e: IOException) {
            Log.i(TAG, "No statistics for this book", e)
            return null
        }
        return parseWork(answer)?.takeIf { it.sessions > 0 || it.etaSeconds != null }
    }

    /**
     * The requested calendar span, with absent days filled by the caller.
     *
     * Asked for as a bounded span first, which costs one request however
     * many calendar years it straddles. A server that predates those
     * parameters ignores them and answers with the current year, so the
     * echoed bounds are what distinguish an obeyed request from a
     * misunderstood one; without them, the older year-by-year form is
     * used instead. Nothing here needs to know a server version.
     */
    suspend fun calendar(from: LocalDate, to: LocalDate): List<InsightDay>? {
        val account = account() ?: return null
        val credentials = account.credentials ?: return null
        val answers = try {
            val bounded = http.get(
                LiseurSyncApi.insightsCalendar(account.baseUrl, from, to),
                credentials,
            )
            if (bounded.covers(from, to)) {
                listOf(bounded)
            } else {
                (from.year..to.year).map { year ->
                    http.get(LiseurSyncApi.insightsCalendar(account.baseUrl, year), credentials)
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "No reading calendar from the server this time", e)
            return null
        }
        return runCatching {
            answers.flatMap { answer ->
                val days = answer.getJSONArray("days")
                (0 until days.length()).mapNotNull { index ->
                    val day = days.optJSONObject(index) ?: return@mapNotNull null
                    val date = LocalDate.parse(day.getString("date"))
                    if (date.isBefore(from) || date.isAfter(to)) return@mapNotNull null
                    InsightDay(
                        date = date,
                        activeMinutes = day.optDouble("minutes", 0.0)
                            .takeIf { it.isFinite() && it >= 0 } ?: 0.0,
                    )
                }
            }
        }.getOrElse {
            Log.i(TAG, "The server returned a malformed reading calendar", it)
            null
        }
    }

    /**
     * Per-book aggregates for [range], keyed by this device's permanent
     * book URL.
     *
     * The same window the dashboard's headline uses, so the rows below it
     * add up to the figure above them — and the same check, so a server
     * that ignored the span answers nothing rather than lifetime totals
     * wearing this month's label. From the aggregates alone the two are
     * indistinguishable, which is the whole reason the span is echoed.
     *
     * One server work may have more than one local URL (for example after
     * a file move); each usable alias receives the same aggregate, and
     * carries the work id so that a caller adding rows up can tell that
     * it is looking at one book twice. Doubtful aliases stay excluded
     * until the reader confirms them, just like position sync.
     */
    suspend fun allBooks(
        range: StatsRange = StatsRange.Default,
        today: LocalDate,
        weekStart: DayOfWeek = WeekFields.ISO.firstDayOfWeek,
    ): Map<String, WorkInsights>? {
        val account = account() ?: return null
        val credentials = account.credentials ?: return null
        val aliases = identityDao.aliasesFor(account.accountKey).filter { it.usable }
        if (aliases.isEmpty()) return emptyMap()
        val from = range.startDate(today, weekStart)
        val answer = try {
            http.get(
                LiseurSyncApi.allWorkInsights(account.baseUrl, from, today),
                credentials,
            )
        } catch (e: IOException) {
            Log.i(TAG, "No per-book statistics from the server this time", e)
            return null
        }
        if (!answer.covers(from, today)) return null
        return runCatching {
            val byWork = aliases.groupBy { it.workId }
            val works = answer.getJSONArray("works")
            buildMap {
                for (index in 0 until works.length()) {
                    val item = works.optJSONObject(index) ?: continue
                    val workId = item.getString("work_id")
                    val insight = parseWork(item)?.copy(workId = workId) ?: continue
                    for (alias in byWork[workId].orEmpty()) {
                        put(alias.bookUrl, insight)
                    }
                }
            }
        }.getOrElse {
            Log.i(TAG, "The server returned malformed per-book statistics", it)
            null
        }
    }

    private fun parseWork(answer: JSONObject): WorkInsights? = runCatching {        WorkInsights(
            sessions = answer.optInt("sessions"),
            activeMinutes = answer.optDouble("total_active_minutes", 0.0)
                .takeIf { it.isFinite() && it >= 0 } ?: 0.0,
            etaSeconds = if (answer.isNull("eta_seconds")) {
                null
            } else {
                answer.optDouble("eta_seconds").takeIf { it.isFinite() && it > 0 }
            },
            lastReadAt = answer.optString("last_read_at")
                .takeIf { it.isNotEmpty() }
                ?.let { Instant.parse(it).toEpochMilli() },
        )
    }.getOrNull()

    /**
     * The connected liseur-sync account, or null when the server is of
     * another kind — statistics from the catalog server are this
     * provider's answer, and any other kind's are their own business.
     */
    private suspend fun account() = serverDao.get()
        ?.takeIf { it.kind == ServerKind.LISEUR_SYNC }
        ?.takeIf { it.credentials != null }

    private companion object {
        const val TAG = "liseur-sync-insights"
    }
}

/**
 * Whether a server answered about the span it was asked about.
 *
 * A server too old to understand a span ignores it and answers about
 * some other one — a lifetime, this calendar year, or its own default
 * fortnight — and the totals give no sign of it: thirty days of reading
 * and ten years of it are both just a number of minutes. So every
 * answer has to say what it counted, and one that does not is discarded
 * rather than shown under the wrong caption.
 *
 * A span with no beginning is checked too, and by `range_days` rather
 * than by bounds it does not have. Nought means the server counted
 * everything; an older one asked for the same thing reports the ten
 * year horizon it used instead, and an older one still does not answer
 * the question at all.
 */
private fun JSONObject.covers(from: LocalDate?, to: LocalDate): Boolean =
    if (from == null) {
        optInt("range_days", -1) == 0
    } else {
        optString("from") == from.toString() && optString("to") == to.toString()
    }
