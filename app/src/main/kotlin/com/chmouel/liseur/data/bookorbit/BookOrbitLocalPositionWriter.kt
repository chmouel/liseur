package com.chmouel.liseur.data.bookorbit

import android.util.Log
import androidx.room.withTransaction
import com.chmouel.liseur.data.db.BookOrbitLocalCfi
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.reader.ResourceAddress
import com.chmouel.liseur.sync.PositionUpdate
import org.json.JSONException
import org.json.JSONObject

/** Saves each local locator and its verified CFI in one transaction. */
class BookOrbitLocalPositionWriter(private val database: LiseurDatabase) {
    suspend fun save(update: PositionUpdate, status: String?) = database.withTransaction {
        val progress = database.readingProgressDao()
        progress.recordLocal(
            bookUrl = update.bookUrl,
            locatorJson = update.locatorJson,
            progression = update.progression,
            readingSecondsPerPosition = update.readingSecondsPerPosition,
            readingPaceSamples = update.readingPaceSamples,
            readingPaceElapsedMs = update.readingPaceElapsedMs,
            readingPaceEvidence = update.readingPaceEvidence,
            status = status,
            updatedAt = update.updatedAt,
        )
        // Agreed in the move's own transaction, so a restart cannot separate them.
        update.bookOrbitApproximate?.takeIf { it.context.bookUrl == update.bookUrl }
            ?.let { adoptApproximateBaselineIn(database, it) }
        update.bookOrbitPull?.takeIf { it.context.bookUrl == update.bookUrl }
            ?.let { agreeOpeningPullIn(database, it) }
        update.bookOrbitDeclined?.takeIf { it.context.bookUrl == update.bookUrl }
            ?.let { declineServerPlaceIn(database, it) }
        val localCfis = database.bookOrbitLocalCfiDao()
        localCfis.clearBook(update.bookUrl)
        val candidate = update.bookOrbitCfi ?: return@withTransaction
        val context = candidate.context
        val locatorHref = try {
            JSONObject(update.locatorJson).optString("href")
        } catch (error: JSONException) {
            Log.w("bookorbit-position", "Discarded a CFI paired with a malformed locator", error)
            return@withTransaction
        }
        val locatorPath = ResourceAddress.canonicalPath(locatorHref)
        if (context.bookUrl != update.bookUrl ||
            candidate.locatorJson != update.locatorJson ||
            locatorPath == null || locatorPath != ResourceAddress.canonicalPath(candidate.href) ||
            !context.request.matches(database.remoteServerDao().get()) ||
            !context.matches(database.bookOrbitBindingDao().get(context.request.accountKey, context.bookUrl))
        ) {
            Log.w("bookorbit-position", "Discarded a stale or mismatched local CFI")
            return@withTransaction
        }
        val local = progress.get(update.bookUrl) ?: error("Local position was not saved")
        localCfis.write(
            BookOrbitLocalCfi(
                accountKey = context.request.accountKey,
                bookUrl = context.bookUrl,
                bookId = context.bookId,
                fileId = context.fileId,
                bindingRevision = context.bindingRevision,
                localRevision = local.positionRevision,
                locatorJson = local.locatorJson,
                rawCfi = candidate.rawCfi,
            ),
        )
    }
}
