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
    private val deviceKey: suspend () -> String,
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
        val credentials = account.credentials
            ?: return@optional refuse("the account has no key to ask with")
        if (!sameAccount(account)) return@optional null
        val json = try {
            http.get(LiseurSyncApi.url(account.baseUrl, CAPABILITIES), credentials)
        } catch (e: RemoteHttpFailure) {
            if (e.reason == SyncFailure.Forbidden) serverDao.setCanReadInsights(false, account.liseurTokenCipher)
            throw e
        }
        if (!sameAccount(account)) return@optional null
        if (!account.canReadInsights) serverDao.setCanReadInsights(true, account.liseurTokenCipher)
        val capabilities = StatisticsCapabilities.parse(json)
            ?: return@optional refuse("the server did not say what statistics it can answer")
        if (capabilities.accountId != null && account.liseurAccountId != null &&
            account.liseurAccountId != capabilities.accountId
        ) return@optional refuse("the server answered about a different account")
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
            if ((capabilities.accountId ?: account.liseurAccountId).isNullOrBlank()) {
                return@optional refuse("the account has no identity to check a reply against")
            }
            if (range == StatsRange.ALL_TIME && !capabilities.allTime) {
                return@optional refuse("the server does not answer about all time")
            }
            // A sitting is named on the server by a key derived from
            // this, so a blank one would quietly rebuild candidates the
            // server cannot recognise and have it count them twice.
            val key = deviceKey().takeIf { it.isNotBlank() }
                ?: return@optional refuse("this device has no identity to name its own reading by")
            val from = range.startDate(today, weekStart)
            val recorded = statsSessions(sessions)
            val evidence = transmissionDao.forPeer(account.accountKey)
            val aliases = statsAliases(identityDao.aliasesFor(account.accountKey))
            val bySession = evidence.associateBy { it.sessionId }
            val aliasByUrl = aliases.associateBy { it.bookUrl }
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
                if (days.size > capabilities.maxLocalActiveDays) {
                    return@optional refuse("more reading days than the server will accept")
                }
                if (day > today || (from != null && day < from)) continue
                if (session.endedAt == null || session.startProgression == null || session.endProgression == null) continue
                contributingIds += session.id
                val transmission = bySession[session.id]
                if (transmission == null && !session.legacyEvidenceUnknown) {
                    // Nothing was ever offered for this sitting, and the
                    // absence is trustworthy: since the evidence table
                    // existed a request has been written down before it
                    // is sent, so no server can be holding this. This
                    // device's own figure is the whole of it.
                    continue
                }
                // A sitting with no retained request may still be on the
                // server: it was sent before that table existed, or the
                // record of it was dropped when an account was
                // disconnected. Neither the acknowledgement nor its
                // absence settles that, so rebuild what would have been
                // sent and let the server say. It answers on identity
                // first, so a sitting it has never seen is ignored and
                // counted here, one it holds is named in the overlap and
                // counted once, and a rebuild it disagrees with is
                // refused outright rather than guessed at (ADR-0021).
                val payload = if (transmission != null) {
                    transmission.payload
                } else {
                    val alias = aliasByUrl[session.bookUrl]
                        ?: return@optional refuse("a sitting of unknown standing is of a book with no name here")
                    SessionUploads.toJson(session, key, alias.workId, alias.editionSha)?.toString()
                        ?: return@optional refuse("a sitting of unknown standing cannot be described again")
                }
                val device = transmission?.deviceId ?: account.accountId.orEmpty()
                if (device.isEmpty()) {
                    return@optional refuse("a sitting to account for names no device")
                }
                candidateBytes += payload.toByteArray(Charsets.UTF_8).size
                if (candidates.length() >= capabilities.maxCandidates || candidateBytes > capabilities.maxBodyBytes) {
                    return@optional refuse("more evidence than one request may carry")
                }
                val json = JSONObject(payload)
                val work = json.getString("work_id")
                if (workByUrl[session.bookUrl] != work) {
                    return@optional refuse("a book was resolved to a different work since it was sent")
                }
                candidateWorks += work
                candidates.put(json.put("device_id", device))
            }
            if (candidates.length() > capabilities.maxCandidates) {
                return@optional refuse("more candidates than the server will accept")
            }
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
                if (raw.toByteArray(Charsets.UTF_8).size > capabilities.maxBodyBytes) {
                    return refuse("the evidence is larger than one request may carry")
                }
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
                return@optional refuse("a longer history than this screen will chart")
            }
            val calendar = first.totals.days.toMutableList()
            val overlapDays = first.totals.overlapDays.toMutableMap()
            if (historyStart < initialFrom) {
                val missing = initialFrom.toEpochDay() - historyStart.toEpochDay()
                if ((missing + capabilities.maxCalendarDays - 1) / capabilities.maxCalendarDays + 1 > MAX_CALENDAR_PAGES) {
                    return@optional refuse("more calendar requests than this screen will make")
                }
                for ((start, end) in calendarChunks(
                    historyStart, initialFrom.minusDays(1), capabilities.maxCalendarDays.toLong(),
                )) {
                    val next = page(start, end) ?: return@optional null
                    if (next.revision != first.revision || next.firstActivity != first.firstActivity ||
                        next.totals.copy(days = emptyList(), overlapDays = emptyMap()) !=
                        first.totals.copy(days = emptyList(), overlapDays = emptyMap())
                    ) return@optional refuse("the server's figures moved between calendar pages")
                    calendar += next.totals.days
                    overlapDays += next.totals.overlapDays
                }
            }
            // Empty wire buckets are genuinely zero only after every interval was proved.
            val byDay = calendar.associateBy { it.date }
            if (!sameMinutes(calendar.map { it.activeMinutes }, first.totals.summary.activeMinutes) ||
                !sameMinutes(overlapDays.values, first.totals.overlapMinutes)
            ) return@optional refuse("the server's calendar does not add up to its own total")
            val dense = generateSequence(historyStart) { it.plusDays(1).takeUnless { day -> day > today } }
                .map { byDay[it] ?: InsightDay(it, 0.0) }.toList()
            val result = CompleteStatsSnapshot(
                context, id, first.revision, today, captured, aliases,
                first.totals.copy(days = dense, overlapDays = overlapDays),
            )
            result.takeIf { isCurrent(it) } ?: refuse("the reading moved while the server was answering")
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

    /**
     * Gives up on the proof, and says why.
     *
     * The screen is unchanged by this — it shows what this device counted
     * and says so — but a refusal that named nothing left the one reader
     * who wanted to know why looking at a blank. Every one of these is a
     * fact about evidence rather than an error, so it goes to the log and
     * nowhere near the reader.
     */
    private fun <T> refuse(reason: String): T? {
        Log.i(TAG, "Statistics count this device alone: $reason")
        return null
    }

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
            ?.takeIf { it.isNotBlank() } ?: return refuse("the account has no identity to check a reply against")
        if (json.opt("complete") != true) {
            // The one refusal the server explains itself: it names which
            // piece of evidence it could not place.
            return refuse(
                "the server could not place this device's evidence" +
                    json.optString("incomplete_reason").takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty(),
            )
        }
        if (json.nonnegativeCount("version") != 1 || json.nonnegativeCount("attribution_version") != 2 ||
            json.opt("account_id") != expectedAccount ||
            json.optString("timezone") != context.capabilities.timezone.id ||
            json.optString("snapshot_id") != id || json.optString("today") != today.toString() ||
            json.optString("calendar_from") != calendarFrom.toString() ||
            json.optString("calendar_to") != calendarTo.toString()
        ) return refuse("the server answered about a different snapshot")
        if (from == null) {
            if (!json.has("range_days") || json.nonnegativeCount("range_days") != 0) {
                return refuse("the server answered about a span with a beginning")
            }
        } else if (json.optString("from") != from.toString() || json.optString("to") != today.toString()) {
            return refuse("the server answered about a different span")
        }
        val revision = (json.opt("stats_revision") as? String)
            ?.takeIf { it.isNotEmpty() && it.all { ch -> ch in '0'..'9' } && it.toLongOrNull() != null }
            ?: return refuse("the server named no usable revision")
        if (!json.has("first_activity_day")) return refuse("the server did not say when its history begins")
        val first = if (json.isNull("first_activity_day")) null else LocalDate.parse(json.getString("first_activity_day"))
        if (first != null && first > today) return refuse(INCOHERENT)
        val summary = json.getJSONObject("summary")
        val top = InsightsSummary(
            summary.minutes("total_active_minutes"), summary.count("sessions"), summary.count("streak_days"),
            summary.optDouble("speed_prog_per_hour").takeIf { it.isFinite() && it > 0 },
        )
        if (top.activeMinutes > 0 && first == null) return refuse(INCOHERENT)
        val works = linkedMapOf<String, WorkInsights>()
        val array = json.getJSONArray("works")
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val work = item.getString("work_id").takeIf { it.isNotEmpty() } ?: return refuse(INCOHERENT)
            val insight = parseWorkInsights(item)?.copy(workId = work) ?: return refuse(INCOHERENT)
            if (insight.activeMinutes > 0 && insight.lastReadAt == null) return refuse(INCOHERENT)
            if (works.put(work, insight) != null) return refuse(INCOHERENT)
        }
        val known = workByUrl.mapNotNull { (url, work) -> works[work]?.let { url to it } }.toMap()
        if (!sameMinutes(works.values.map { it.activeMinutes }, top.activeMinutes) ||
            works.values.sumOf { it.sessions.toLong() } != top.sessions.toLong()
        ) return refuse(INCOHERENT)
        val elsewhere = works.values.filter { it.workId !in workByUrl.values }
        val days = parseDays(json.getJSONArray("days"), calendarFrom, calendarTo) ?: return refuse(INCOHERENT)
        val overlap = json.getJSONObject("overlap")
        val overlapMinutes = overlap.minutes("total_active_minutes")
        val overlapSessions = overlap.count("sessions")
        if (exceedsMinutes(overlapMinutes, top.activeMinutes) || overlapSessions > top.sessions ||
            overlapSessions > candidateCount
        ) return refuse(INCOHERENT)
        val overlapWorks = linkedMapOf<String, Pair<Double, Int>>()
        val matches = overlap.getJSONArray("works")
        for (index in 0 until matches.length()) {
            val item = matches.getJSONObject(index)
            val work = item.getString("work_id")
            if (work !in candidateWorks) return refuse(INCOHERENT)
            val amount = item.minutes("total_active_minutes")
            val count = item.count("sessions")
            val full = works[work] ?: return refuse(INCOHERENT)
            if (exceedsMinutes(amount, full.activeMinutes) || count > full.sessions ||
                overlapWorks.put(work, amount to count) != null
            ) return refuse(INCOHERENT)
        }
        if (!sameMinutes(overlapWorks.values.map { it.first }, overlapMinutes) ||
            overlapWorks.values.sumOf { it.second.toLong() } != overlapSessions.toLong()
        ) return refuse(INCOHERENT)
        val matchedDays = parseDays(overlap.getJSONArray("days"), calendarFrom, calendarTo) ?: return refuse(INCOHERENT)
        val fullDays = days.associateBy { it.date }
        if (matchedDays.any { exceedsMinutes(it.activeMinutes, fullDays[it.date]?.activeMinutes ?: 0.0) }) return refuse(INCOHERENT)
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
        /** One reason for the whole family of "the server's figures disagree with themselves". */
        const val INCOHERENT = "the server's figures do not add up to each other"
        // A resource refusal, not a truncated chart: unusually large histories remain local.
        const val MAX_CALENDAR_DAYS = 366_000L
        const val MAX_CALENDAR_PAGES = 128
    }
}
