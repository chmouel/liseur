package com.chmouel.liseur.data.liseursync

import java.time.ZoneId
import org.json.JSONObject

data class StatisticsCapabilities(
    val timezone: ZoneId,
    val activeMs: Boolean,
    val maxCandidates: Int,
    val maxCalendarDays: Int,
    val allTime: Boolean,
    val accountId: String?,
    val maxBodyBytes: Int,
    val maxLocalActiveDays: Int,
) {
    companion object {
        internal fun parse(json: JSONObject): StatisticsCapabilities? {
            if (json.nonnegativeCount("version") != 1 || json.nonnegativeCount("attribution_version") != 2) return null
            val zone = ZoneId.of(json.getString("timezone"))
            val candidates = json.nonnegativeCount("max_candidates")?.takeIf { it > 0 } ?: return null
            val days = json.nonnegativeCount("max_calendar_days")?.takeIf { it > 0 } ?: return null
            val account = (json.opt("account_id") as? String)?.takeIf { it.isNotBlank() }
            if (json.has("account_id") && account == null) return null
            val bytes = if (json.has("max_body_bytes")) {
                json.nonnegativeCount("max_body_bytes")?.takeIf { it > 0 } ?: return null
            } else 1024 * 1024
            val activeDays = if (json.has("max_local_active_days")) {
                json.nonnegativeCount("max_local_active_days")?.takeIf { it > 0 } ?: return null
            } else 25_000
            return StatisticsCapabilities(
                zone, json.opt("active_ms") == true, minOf(candidates, 10_000), minOf(days, 4_000),
                // Version 1 defines range=all; honor an explicit opt-out when supplied.
                !json.has("all_time") || json.opt("all_time") == true,
                account, minOf(bytes, 4 * 1024 * 1024), minOf(activeDays, 25_000),
            )
        }
    }
}
