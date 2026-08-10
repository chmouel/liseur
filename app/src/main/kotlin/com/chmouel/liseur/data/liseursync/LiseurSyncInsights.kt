package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.db.SyncAccountDao
import com.chmouel.liseur.data.db.WorkIdentityDao
import java.io.IOException
import org.json.JSONObject

/**
 * Reading added up across every device, as the server sees it.
 *
 * `range_days` is echoed back rather than assumed: a server may cap a
 * long range, and reporting thirty days of reading as a year's would be
 * a lie about the reader.
 */
data class InsightsSummary(
    val rangeDays: Int,
    val activeMinutes: Double,
    val sessions: Int,
    val streakDays: Int,
)

/** One book, added up across every device. */
data class WorkInsights(
    val sessions: Int,
    val activeMinutes: Double,
    /** Null when the server has no speed history to divide by. */
    val etaSeconds: Double?,
)

/**
 * Statistics from the sync server, when there are any.
 *
 * Every answer here is decoration. The dashboard is built from this
 * device's own sessions and stands on its own; what this adds is the
 * reading done on the reader's other devices, which no amount of local
 * arithmetic can produce. So every failure is null and silent: a
 * statistics screen is not worth an error banner, and a reader offline
 * on a train should see their own figures rather than a complaint.
 *
 * It needs the second, narrower token — the one that may read but not
 * write — which a reader who pasted a single sync token will not have.
 * That is the ordinary case for the answer being null.
 */
class LiseurSyncInsights(
    private val accountDao: SyncAccountDao,
    private val identityDao: WorkIdentityDao,
    private val http: LiseurSyncHttp = LiseurSyncHttp(),
) {

    suspend fun summary(rangeDays: Int = DEFAULT_RANGE_DAYS): InsightsSummary? {
        val account = accountDao.get() ?: return null
        val credentials = account.insightsCredentials ?: return null
        val answer = try {
            http.get(
                LiseurSyncApi.insightsSummary(account.baseUrl, "${rangeDays}d"),
                credentials,
            )
        } catch (e: IOException) {
            Log.i(TAG, "No statistics from the server this time", e)
            return null
        }
        return InsightsSummary(
            rangeDays = answer.optInt("range_days", rangeDays),
            activeMinutes = answer.optDouble("total_active_minutes", 0.0),
            sessions = answer.optInt("sessions"),
            streakDays = answer.optInt("streak_days"),
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
        val account = accountDao.get() ?: return null
        val credentials = account.insightsCredentials ?: return null
        val alias = identityDao.alias(bookUrl, account.peerId)?.takeIf { it.usable } ?: return null
        val answer = try {
            http.get(LiseurSyncApi.workInsights(account.baseUrl, alias.workId), credentials)
        } catch (e: IOException) {
            Log.i(TAG, "No statistics for this book", e)
            return null
        }
        return WorkInsights(
            sessions = answer.optInt("sessions"),
            activeMinutes = answer.optDouble("total_active_minutes", 0.0),
            etaSeconds = if (answer.isNull("eta_seconds")) {
                null
            } else {
                answer.optDouble("eta_seconds").takeIf { it.isFinite() && it > 0 }
            },
        ).takeIf { it.sessions > 0 || it.etaSeconds != null }
    }

    private companion object {
        const val TAG = "liseur-sync-insights"
        const val DEFAULT_RANGE_DAYS = 30
    }
}
