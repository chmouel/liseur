package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.LiveIdentity
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.domain.StatsRange
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.WeekFields
import org.json.JSONObject
import org.json.JSONException

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
    /**
     * What the server calls the book, when it says.
     *
     * The only thing needed to list a work this device has no file for
     * (ADR-0021). Empty from a server too old to send it, which is a
     * work that cannot be shown rather than one shown nameless.
     */
    val title: String = "",
    val author: String? = null,
    /**
     * Where the reader is in the book, by the newest position any device
     * sent; null when no position has ever reached the server.
     *
     * Never windowed: a place in a book is true now, whatever span the
     * figures beside it cover. Read for a book this device has no file
     * for and nothing else (ADR-0021): for a book that is here the
     * local position is fresher than anything a server can relay, but
     * for one that is not, this is the only account of it there is.
     */
    val currentProgression: Double? = null,
)

/** One server-timezone calendar day, added up across every device. */
data class InsightDay(val date: LocalDate, val activeMinutes: Double)

/**
 * Every book the server counted for one window, sorted by whether this
 * device has a file for it.
 *
 * [byBookUrl] is what the dashboard can put a cover, a progression and a
 * tap target against. [elsewhere] is the rest: works read on another
 * device, or ones this device has never resolved. They used to be
 * dropped at the mapping, which left the list under the headline smaller
 * than the headline itself and gave no reason for the difference
 * (ADR-0021).
 */
data class WorkTotals(
    val byBookUrl: Map<String, WorkInsights>,
    val elsewhere: List<WorkInsights>,
) {
    companion object {
        val Empty = WorkTotals(emptyMap(), emptyList())
    }
}

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
 * refused, which lands in the same silent null as being offline — but
 * the refusal is written down on the account, so the reader can be told
 * where it can still be fixed (ADR-0021).
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
    ): InsightsSummary? = summary(range.startDate(today, weekStart), today)

    /**
     * The same aggregate, over a span named outright.
     *
     * The comparison's baseline ends before today, so it cannot be
     * described by a range and a date. It goes through the same
     * [covers] check as everything else: a server that ignored the
     * bounds answers about some other stretch of the reader's life, and
     * "12% less than last month" over that is worse than no comparison.
     */
    suspend fun summary(from: LocalDate?, to: LocalDate): InsightsSummary? {
        val account = account() ?: return null
        val credentials = account.credentials ?: return null
        val answer = ask("No statistics from the server this time") {
            fetch(account, credentials, LiseurSyncApi.insightsSummary(account.baseUrl, from, to))
        } ?: return null
        if (!answer.covers(from, to)) return null
        val minutes = answer.optDouble("total_active_minutes", 0.0)
        val sessions = answer.nonnegativeCount("sessions") ?: return null
        val streak = answer.nonnegativeCount("streak_days") ?: return null
        if (!minutes.validMinutes()) return null
        return InsightsSummary(
            activeMinutes = minutes,
            sessions = sessions,
            streakDays = streak,
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
        val answer = ask("No statistics for this book") {
            fetch(account, credentials, LiseurSyncApi.workInsights(account.baseUrl, alias.workId))
        } ?: return null
        return parseWorkInsights(answer)?.takeIf { it.sessions > 0 || it.etaSeconds != null }
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
        val answers = mutableListOf<JSONObject>()
        ask("No reading calendar from the server this time") {
            val bounded = fetch(
                account,
                credentials,
                LiseurSyncApi.insightsCalendar(account.baseUrl, from, to),
            )
            if (bounded.covers(from, to)) {
                answers += bounded
            } else {
                for (year in from.year..to.year) {
                    answers += fetch(
                        account,
                        credentials,
                        LiseurSyncApi.insightsCalendar(account.baseUrl, year),
                    )
                }
            }
            bounded
        } ?: return null
        return runCatching {
            answers.flatMap { answer ->
                val days = answer.getJSONArray("days")
                (0 until days.length()).mapNotNull { index ->
                    val day = days.getJSONObject(index)
                    val date = LocalDate.parse(day.getString("date"))
                    if (date.isBefore(from) || date.isAfter(to)) return@mapNotNull null
                    InsightDay(
                        date = date,
                        activeMinutes = day.optDouble("minutes", 0.0)
                            .takeIf { it.validMinutes() }
                            ?: throw JSONException("Invalid calendar minutes"),
                    )
                }
            }
        }.getOrElse {
            Log.i(TAG, "The server returned a malformed reading calendar", it)
            null
        }
    }

    /**
     * Per-book aggregates for [range]: those this device has a book for,
     * keyed by its permanent book URL, and those it has not.
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
     * until the reader confirms them, just like position sync — and a
     * work excluded that way is a work read elsewhere as far as this is
     * concerned, which is the honest answer: its time is in the total
     * either way, and this device cannot say which of its files it is.
     *
     * A work with no name is dropped rather than listed blank. The
     * figures are already counted in the headline, so what a nameless
     * row would add is a line the reader cannot identify.
     */
    suspend fun allBooks(
        range: StatsRange = StatsRange.Default,
        today: LocalDate,
        weekStart: DayOfWeek = WeekFields.ISO.firstDayOfWeek,
    ): WorkTotals? {
        val account = account() ?: return null
        val credentials = account.credentials ?: return null
        val aliases = identityDao.aliasesFor(account.accountKey).filter { it.usable }
        val from = range.startDate(today, weekStart)
        val answer = ask("No per-book statistics from the server this time") {
            fetch(account, credentials, LiseurSyncApi.allWorkInsights(account.baseUrl, from, today))
        } ?: return null
        if (!answer.covers(from, today)) return null
        return runCatching {
            val byWork = aliases.groupBy { it.workId }
            val works = answer.getJSONArray("works")
            val known = mutableMapOf<String, WorkInsights>()
            val elsewhere = mutableListOf<WorkInsights>()
            for (index in 0 until works.length()) {
                val item = works.getJSONObject(index)
                val workId = item.getString("work_id")
                val insight = parseWorkInsights(item)?.copy(workId = workId)
                    ?: throw JSONException("Invalid work statistics")
                val here = byWork[workId].orEmpty()
                if (here.isEmpty()) {
                    if (insight.title.isNotEmpty()) elsewhere += insight
                    continue
                }
                for (alias in here) known[alias.bookUrl] = insight
            }
            WorkTotals(byBookUrl = known, elsewhere = elsewhere)
        }.getOrElse {
            Log.i(TAG, "The server returned malformed per-book statistics", it)
            null
        }
    }

    /**
     * Runs one request and writes down what its answer proved about the
     * token.
     *
     * `can_read_insights` is read off the token's scopes at connect and
     * nowhere else, so an account paired before the column existed
     * would carry its pessimistic default for good, and the account
     * screen would go on saying statistics are refused to a reader
     * whose statistics work. The answers settle it: a body is proof the
     * token may ask, a 403 is proof it may not, and either is written
     * down so the screen says what is true. Any other failure — offline,
     * a server too old, a malformed body — says nothing about the token
     * and changes nothing (ADR-0021).
     *
     * Conditional on the account, as every write from a background
     * answer is: a reply to a question asked of an account the reader
     * has since left must not describe the one they are on.
     */
    private suspend fun ask(
        whenRefused: String,
        request: suspend () -> JSONObject,
    ): JSONObject? = try {
        request()
    } catch (e: RemoteHttpFailure) {
        Log.i(TAG, whenRefused, e)
        null
    } catch (e: IOException) {
        Log.i(TAG, whenRefused, e)
        null
    }

    /**
     * One HTTP call, with the capability column corrected from its own
     * outcome alone.
     *
     * A multi-request caller such as [calendar] can have an early call
     * prove the token capable and a later one fail outright; recording
     * only once the whole caller returns would lose that proof the
     * moment anything after it throws. Wrapping each call here instead
     * means a body updates the column the instant it arrives, no matter
     * what any later call in the same caller does.
     */
    private suspend fun fetch(
        account: RemoteServer,
        credentials: RemoteCredentials,
        url: String,
    ): JSONObject {
        try {
            if (serverDao.get()?.let(LiveIdentity::from) != LiveIdentity.from(account)) {
                throw IOException("Statistics account changed")
            }
            val answer = http.get(url, credentials)
            if (serverDao.get()?.let(LiveIdentity::from) != LiveIdentity.from(account)) {
                throw IOException("Statistics account changed")
            }
            if (!account.canReadInsights) record(account, allowed = true)
            return answer
        } catch (e: RemoteHttpFailure) {
            if (e.reason == SyncFailure.Forbidden && account.canReadInsights) {
                record(account, allowed = false)
            }
            throw e
        }
    }

    /**
     * Writes [allowed] for the token that made the request, and only
     * that token.
     *
     * [RemoteServer.accountKey] deliberately survives a token rotation
     * (a re-pasted token for the same account keeps it), so it cannot
     * guard this: the permission belongs to the token, not the account.
     * Keying the update on the token cipher instead — and folding the
     * check into the `WHERE` clause of that one statement — closes the
     * gap a separate read-then-write would leave open, where a
     * reconnect replaces the row between the request going out and its
     * answer coming back, and the late answer for the old token would
     * otherwise overwrite the new one's permission.
     */
    private suspend fun record(account: RemoteServer, allowed: Boolean) {
        serverDao.setCanReadInsights(allowed, account.liseurTokenCipher)
    }

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

internal fun Double.validMinutes(): Boolean =
    isFinite() && this >= 0 && this < Long.MAX_VALUE.toDouble() / 60_000.0

internal fun JSONObject.nonnegativeCount(name: String): Int? {
    val value = optDouble(name, 0.0)
    return value.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }
        ?.toInt()
}

internal fun parseWorkInsights(answer: JSONObject): WorkInsights? = try {
    WorkInsights(
        sessions = answer.nonnegativeCount("sessions") ?: throw JSONException("Invalid session count"),
        activeMinutes = answer.optDouble("total_active_minutes", 0.0)
            .takeIf { it.validMinutes() } ?: throw JSONException("Invalid work minutes"),
        etaSeconds = answer.optDouble("eta_seconds").takeIf { it.isFinite() && it > 0 },
        lastReadAt = answer.optString("last_read_at").takeIf { it.isNotEmpty() }
            ?.let { Instant.parse(it).toEpochMilli() },
        title = answer.optString("title"),
        author = answer.optString("author").takeIf { it.isNotEmpty() },
        currentProgression = answer.optDouble("current_progression")
            .takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(1.0),
    )
} catch (_: JSONException) {
    null
} catch (_: java.time.DateTimeException) {
    null
} catch (_: ArithmeticException) {
    null
}
