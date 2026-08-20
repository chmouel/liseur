package com.chmouel.liseur.data.remote

import com.chmouel.liseur.data.NetworkAvailability
import com.chmouel.liseur.data.db.SeriesExtra
import com.chmouel.liseur.data.db.SeriesExtraDao
import com.chmouel.liseur.data.komga.KomgaSeriesClient
import com.chmouel.liseur.domain.SeriesExtras

/**
 * What the connected server can add to a series screen, if anything.
 *
 * Only Komga has anything to add, and the screen is built to look
 * finished without it: a calibre-web library and a folder of EPUBs get
 * the same volumes in the same order with the same button on top, and
 * simply no summary. Anything that failed here is null, quietly.
 *
 * The answer is kept so a series opened once reads the same offline. It
 * is refreshed when it is old rather than on every visit, because
 * nothing here changes on the hour.
 */
class SeriesExtrasRepository(
    private val account: RemoteAccountRepository,
    private val dao: SeriesExtraDao,
    private val komga: KomgaSeriesClient = KomgaSeriesClient(),
    private val now: () -> Long = System::currentTimeMillis,
    private val networkAvailability: NetworkAvailability = NetworkAvailability { true },
) {

    suspend fun extras(seriesId: String?): SeriesExtras? {
        if (seriesId.isNullOrBlank()) return null
        val cached = dao.get(seriesId)
        if (cached != null && now() - cached.fetchedAt < FRESH_FOR_MS) return cached.toExtras()
        if (!networkAvailability.isAvailable()) return cached?.toExtras()

        val server = account.current()
        if (server?.kind != ServerKind.KOMGA) return cached?.toExtras()
        val credentials = server.credentials ?: return cached?.toExtras()

        val fetched = komga.series(server.baseUrl, credentials, seriesId)
            ?: return cached?.toExtras()
        val row = SeriesExtra(
            seriesId = seriesId,
            summary = fetched.summary?.takeIf { it.isNotBlank() },
            status = fetched.status?.takeIf { it.isNotBlank() },
            totalBookCount = fetched.totalBookCount,
            fetchedAt = now(),
        )
        dao.upsert(row)
        return row.toExtras()
    }

    private fun SeriesExtra.toExtras() = SeriesExtras(
        summary = summary,
        status = status,
        totalBookCount = totalBookCount,
    )

    private companion object {
        /** A week. A series' summary is not news. */
        const val FRESH_FOR_MS = 7L * 24 * 60 * 60 * 1000
    }
}
