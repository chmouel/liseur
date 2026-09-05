package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.db.ReadingSessionDao
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.db.SessionTransmissionDao
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.data.remote.LiveIdentity
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.domain.StatsRange
import com.chmouel.liseur.domain.calendarChunks
import java.io.IOException
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class StatisticsContext(val account: RemoteServer, val capabilities: StatisticsCapabilities)

internal data class CompleteStatsSnapshot(
    val context: StatisticsContext,
    val snapshotId: String,
    val revision: String,
    val today: LocalDate,
    val captured: CapturedStatsSessions,
    val aliases: List<StatsAlias>,
    val totals: SnapshotTotals,
) {
    val zone: ZoneId get() = context.capabilities.timezone
    val peer: String get() = context.account.accountKey
}

/** A failed proof supplies no server decoration, including partially fetched calendars. */
class LiseurSyncSnapshots(
    private val serverDao: RemoteServerDao,
    private val sessionDao: ReadingSessionDao,
    private val transmissionDao: SessionTransmissionDao,
    private val identityDao: WorkIdentityDao,
    private val http: LiseurSyncHttp = LiseurSyncHttp(),
) {
    /** Duration support is available to sync-only tokens, unlike dashboard insights. */
    suspend fun supportsMeasuredSessions(): Boolean = optional {
        val account = serverDao.get()?.takeIf { it.kind == ServerKind.LISEUR_SYNC } ?: return@optional false
        val credentials = account.credentials ?: return@optional false
        if (!sameAccount(account)) return@optional false
        val token = http.get(LiseurSyncApi.url(account.baseUrl, LiseurSyncApi.TOKEN), credentials)
        if (!sameAccount(account)) return@optional false
        if (account.liseurAccountId != null && token.opt("account_id") != account.liseurAccountId) return@optional false
        if (account.accountId != null && token.opt("device_id") != account.accountId) return@optional false
        token.opt("session_active_ms") == true
    } == true

    suspend fun discover(): StatisticsContext? = optional {
        val account = serverDao.get()?.takeIf { it.kind == ServerKind.LISEUR_SYNC } ?: return@optional null
        val credentials = account.credentials ?: return@optional null
        if (!sameAccount(account)) return@optional null
        val json = try {
            http.get(LiseurSyncApi.url(account.baseUrl, CAPABILITIES), credentials)
        } catch (e: RemoteHttpFailure) {
            if (e.reason == SyncFailure.Forbidden) serverDao.setCanReadInsights(false, account.liseurTokenCipher)
            throw e
        }
        if (!sameAccount(account)) return@optional null
        if (!account.canReadInsights) serverDao.setCanReadInsights(true, account.liseurTokenCipher)
        val capabilities = StatisticsCapabilities.parse(json) ?: return@optional null
        if (capabilities.accountId != null && account.liseurAccountId != null &&
            account.liseurAccountId != capabilities.accountId
        ) return@optional null
        StatisticsContext(account, capabilities)
    }

    internal suspend fun read(
        context: StatisticsContext,
        sessions: List<ReadingSession>,
        range: StatsRange,
        today: LocalDate,
        weekStart: DayOfWeek,
    ): CompleteStatsSnapshot? = withContext(Dispatchers.Default) {
        optional {
            val account = context.account
            val capabilities = context.capabilities
            if (!sameAccount(account)) return@optional null
            if ((capabilities.accountId ?: account.liseurAccountId).isNullOrBlank()) return@optional null
            if (range == StatsRange.ALL_TIME && !capabilities.allTime) return@optional null
            val from = range.startDate(today, weekStart)
            val recorded = statsSessions(sessions)
            val evidence = transmissionDao.forPeer(account.accountKey)
            val aliases = statsAliases(identityDao.aliasesFor(account.accountKey))
            val bySession = evidence.associateBy { it.sessionId }
            val candidates = JSONArray()
            val candidateWorks = mutableSetOf<String>()
            val contributingIds = mutableSetOf<Long>()
            var candidateBytes = 0L
            val days = sortedSetOf<LocalDate>()
            val workByUrl = aliases.associate { it.bookUrl to it.workId }
            for (session in recorded) {
                if (session.durationMs <= 0) continue
                val day = Instant.ofEpochMilli(session.endedAt ?: session.lastCheckpointAt)
                    .atZone(capabilities.timezone).toLocalDate()
                if (day <= today) days += day
                if (days.size > capabilities.maxLocalActiveDays) return@optional null
                if (day > today || (from != null && day < from)) continue
                if (session.endedAt == null || session.startProgression == null || session.endProgression == null) continue
                contributingIds += session.id
                if (session.legacyEvidenceUnknown) return@optional null
                val transmission = bySession[session.id] ?: continue
                if (transmission.deviceId.isEmpty()) return@optional null
                candidateBytes += transmission.payload.toByteArray(Charsets.UTF_8).size
                if (candidates.length() >= capabilities.maxCandidates || candidateBytes > capabilities.maxBodyBytes) return@optional null
                val payload = JSONObject(transmission.payload)
                val work = payload.getString("work_id")
                if (workByUrl[session.bookUrl] != work) return@optional null
                candidateWorks += work
                candidates.put(payload.put("device_id", transmission.deviceId))
            }
            if (candidates.length() > capabilities.maxCandidates) return@optional null
            val captured = CapturedStatsSessions(
                recorded, evidence.filter { it.sessionId in contributingIds }, contributingIds.toSet(),
            )
            val firstLocalDay = days.firstOrNull()
            val initialFrom = maxOf(
                from ?: firstLocalDay ?: today,
                today.minusDays(capabilities.maxCalendarDays.toLong() - 1),
            )
            val id = UUID.randomUUID().toString()
            val body = JSONObject().apply {
                put("snapshot_id", id)
                put("timezone", capabilities.timezone.id)
                if (from == null) put("range", "all") else {
                    put("from", from.toString())
                    put("to", today.toString())
                }
                put("candidates", candidates)
                put("local_active_days", JSONArray(days.map(LocalDate::toString)))
            }
            suspend fun page(start: LocalDate, end: LocalDate): Page? {
                body.put("calendar_from", start.toString()).put("calendar_to", end.toString())
                val raw = body.toString()
                // Refuse the complete proof rather than silently dropping candidates or days.
                if (raw.toByteArray(Charsets.UTF_8).size > capabilities.maxBodyBytes) return null
                if (!sameAccount(account)) return null
                val response = try {
                    http.postRaw(
                        LiseurSyncApi.url(account.baseUrl, SNAPSHOT), account.credentials, raw,
                    ).also { serverDao.setCanReadInsights(true, account.liseurTokenCipher) }
                } catch (e: RemoteHttpFailure) {
                    if (e.reason == SyncFailure.Forbidden) {
                        serverDao.setCanReadInsights(false, account.liseurTokenCipher)
                    }
                    throw e
                }
                if (!sameAccount(account)) return null
                return parsePage(response, context, id, from, today, start, end, workByUrl, candidateWorks, candidates.length())
            }
            val first = page(initialFrom, today) ?: return@optional null
            val historyStart = from ?: listOfNotNull(first.firstActivity, firstLocalDay).minOrNull() ?: today
            if (historyStart > today || today.toEpochDay() - historyStart.toEpochDay() > MAX_CALENDAR_DAYS) {
                return@optional null
            }
            val calendar = first.totals.days.toMutableList()
            val overlapDays = first.totals.overlapDays.toMutableMap()
            if (historyStart < initialFrom) {
                val missing = initialFrom.toEpochDay() - historyStart.toEpochDay()
                if ((missing + capabilities.maxCalendarDays - 1) / capabilities.maxCalendarDays + 1 > MAX_CALENDAR_PAGES) {
                    return@optional null
                }
                for ((start, end) in calendarChunks(
                    historyStart, initialFrom.minusDays(1), capabilities.maxCalendarDays.toLong(),
                )) {
                    val next = page(start, end) ?: return@optional null
                    if (next.revision != first.revision || next.firstActivity != first.firstActivity ||
                        next.totals.copy(days = emptyList(), overlapDays = emptyMap()) !=
                        first.totals.copy(days = emptyList(), overlapDays = emptyMap())
                    ) return@optional null
                    calendar += next.totals.days
                    overlapDays += next.totals.overlapDays
                }
            }
            // Empty wire buckets are genuinely zero only after every interval was proved.
            val byDay = calendar.associateBy { it.date }
            if (!sameMinutes(calendar.map { it.activeMinutes }, first.totals.summary.activeMinutes) ||
                !sameMinutes(overlapDays.values, first.totals.overlapMinutes)
            ) return@optional null
            val dense = generateSequence(historyStart) { it.plusDays(1).takeUnless { day -> day > today } }
                .map { byDay[it] ?: InsightDay(it, 0.0) }.toList()
            val result = CompleteStatsSnapshot(
                context, id, first.revision, today, captured, aliases,
                first.totals.copy(days = dense, overlapDays = overlapDays),
            )
            result.takeIf { isCurrent(it) }
        }
    }

    internal suspend fun isCurrent(snapshot: CompleteStatsSnapshot): Boolean = withContext(Dispatchers.Default) {
        sameAccount(snapshot.context.account) &&
            snapshot.captured.matches(sessionDao.allOnce(), transmissionDao.forPeer(snapshot.peer)) &&
            snapshot.aliases == statsAliases(identityDao.aliasesFor(snapshot.peer)) &&
            sameAccount(snapshot.context.account)
    }

    private suspend fun sameAccount(account: RemoteServer): Boolean =
        serverDao.get()?.let(LiveIdentity::from) == LiveIdentity.from(account)

    private data class Page(val revision: String, val firstActivity: LocalDate?, val totals: SnapshotTotals)

    private fun parsePage(
        json: JSONObject,
        context: StatisticsContext,
        id: String,
        from: LocalDate?,
        today: LocalDate,
        calendarFrom: LocalDate,
        calendarTo: LocalDate,
        workByUrl: Map<String, String>,
        candidateWorks: Set<String>,
        candidateCount: Int,
    ): Page? {
        val expectedAccount = (context.capabilities.accountId ?: context.account.liseurAccountId)
            ?.takeIf { it.isNotBlank() } ?: return null
        if (json.nonnegativeCount("version") != 1 || json.nonnegativeCount("attribution_version") != 2 ||
            json.opt("complete") != true ||
            json.opt("account_id") != expectedAccount ||
            json.optString("timezone") != context.capabilities.timezone.id ||
            json.optString("snapshot_id") != id || json.optString("today") != today.toString() ||
            json.optString("calendar_from") != calendarFrom.toString() ||
            json.optString("calendar_to") != calendarTo.toString()
        ) return null
        if (from == null) {
            if (!json.has("range_days") || json.nonnegativeCount("range_days") != 0) return null
        } else if (json.optString("from") != from.toString() || json.optString("to") != today.toString()) return null
        val revision = (json.opt("stats_revision") as? String)
            ?.takeIf { it.isNotEmpty() && it.all { ch -> ch in '0'..'9' } && it.toLongOrNull() != null } ?: return null
        if (!json.has("first_activity_day")) return null
        val first = if (json.isNull("first_activity_day")) null else LocalDate.parse(json.getString("first_activity_day"))
        if (first != null && first > today) return null
        val summary = json.getJSONObject("summary")
        val top = InsightsSummary(
            summary.minutes("total_active_minutes"), summary.count("sessions"), summary.count("streak_days"),
            summary.optDouble("speed_prog_per_hour").takeIf { it.isFinite() && it > 0 },
        )
        if (top.activeMinutes > 0 && first == null) return null
        val works = linkedMapOf<String, WorkInsights>()
        val array = json.getJSONArray("works")
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val work = item.getString("work_id").takeIf { it.isNotEmpty() } ?: return null
            val insight = parseWorkInsights(item)?.copy(workId = work) ?: return null
            if (insight.activeMinutes > 0 && insight.lastReadAt == null) return null
            if (works.put(work, insight) != null) return null
        }
        val known = workByUrl.mapNotNull { (url, work) -> works[work]?.let { url to it } }.toMap()
        if (!sameMinutes(works.values.map { it.activeMinutes }, top.activeMinutes) ||
            works.values.sumOf { it.sessions.toLong() } != top.sessions.toLong()
        ) return null
        val elsewhere = works.values.filter { it.workId !in workByUrl.values }
        val days = parseDays(json.getJSONArray("days"), calendarFrom, calendarTo) ?: return null
        val overlap = json.getJSONObject("overlap")
        val overlapMinutes = overlap.minutes("total_active_minutes")
        val overlapSessions = overlap.count("sessions")
        if (exceedsMinutes(overlapMinutes, top.activeMinutes) || overlapSessions > top.sessions ||
            overlapSessions > candidateCount
        ) return null
        val overlapWorks = linkedMapOf<String, Pair<Double, Int>>()
        val matches = overlap.getJSONArray("works")
        for (index in 0 until matches.length()) {
            val item = matches.getJSONObject(index)
            val work = item.getString("work_id")
            if (work !in candidateWorks) return null
            val amount = item.minutes("total_active_minutes")
            val count = item.count("sessions")
            val full = works[work] ?: return null
            if (exceedsMinutes(amount, full.activeMinutes) || count > full.sessions ||
                overlapWorks.put(work, amount to count) != null
            ) return null
        }
        if (!sameMinutes(overlapWorks.values.map { it.first }, overlapMinutes) ||
            overlapWorks.values.sumOf { it.second.toLong() } != overlapSessions.toLong()
        ) return null
        val matchedDays = parseDays(overlap.getJSONArray("days"), calendarFrom, calendarTo) ?: return null
        val fullDays = days.associateBy { it.date }
        if (matchedDays.any { exceedsMinutes(it.activeMinutes, fullDays[it.date]?.activeMinutes ?: 0.0) }) return null
        // Normalize only the accepted sub-millisecond excess. Otherwise integer
        // rounding could turn that noise into a negative residual in the union.
        return Page(
            revision, first,
            SnapshotTotals(
                top, WorkTotals(known, elsewhere), days, minOf(overlapMinutes, top.activeMinutes), overlapSessions,
                overlapWorks.mapValues { (id, amount) ->
                    minOf(amount.first, works.getValue(id).activeMinutes) to amount.second
                },
                matchedDays.associate {
                    it.date to minOf(it.activeMinutes, fullDays[it.date]?.activeMinutes ?: 0.0)
                },
                json.count("combined_streak_days"),
            ),
        )
    }

    private fun parseDays(array: JSONArray, from: LocalDate, to: LocalDate): List<InsightDay>? {
        if (array.length().toLong() > to.toEpochDay() - from.toEpochDay() + 1) return null
        val days = linkedMapOf<LocalDate, InsightDay>()
        for (index in 0 until array.length()) {
            val row = array.getJSONObject(index)
            val date = LocalDate.parse(row.getString("date"))
            if (date < from || date > to || days.containsKey(date)) return null
            row.count("sessions")
            days[date] = InsightDay(date, row.minutes("minutes"))
        }
        return days.values.toList()
    }

    private fun JSONObject.minutes(key: String): Double =
        getDouble(key).takeIf { it.validMinutes() } ?: throw JSONException("Invalid statistics minutes")

    private fun JSONObject.count(key: String): Int =
        if (has(key)) nonnegativeCount(key) ?: throw JSONException("Invalid statistics count")
        else throw JSONException("Missing statistics count")

    private fun sameMinutes(parts: Collection<Double>, total: Double): Boolean {
        val sum = parts.sum()
        // JSON minutes are floating point; permit only sub-millisecond rounding.
        return sum.validMinutes() && sameMinuteValue(sum, total)
    }

    private fun sameMinuteValue(left: Double, right: Double): Boolean =
        abs((left - right) * 60_000.0) < 1.0

    private fun exceedsMinutes(value: Double, maximum: Double): Boolean =
        value > maximum && !sameMinuteValue(value, maximum)

    private suspend fun <T> optional(block: suspend () -> T?): T? = try {
        block()
    } catch (e: IOException) {
        Log.i(TAG, "No complete statistics snapshot", e)
        null
    } catch (e: JSONException) {
        Log.i(TAG, "Malformed statistics snapshot", e)
        null
    } catch (e: DateTimeException) {
        Log.i(TAG, "Invalid statistics date or timezone", e)
        null
    }

    private companion object {
        const val TAG = "liseur-sync-insights"
        const val CAPABILITIES = "/v1/insights/capabilities"
        const val SNAPSHOT = "/v1/insights/snapshot"
        // A resource refusal, not a truncated chart: unusually large histories remain local.
        const val MAX_CALENDAR_DAYS = 366_000L
        const val MAX_CALENDAR_PAGES = 128
    }
}
