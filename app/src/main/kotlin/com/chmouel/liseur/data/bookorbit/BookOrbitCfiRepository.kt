package com.chmouel.liseur.data.bookorbit

import androidx.room.withTransaction
import com.chmouel.liseur.data.db.BookOrbitBinding
import com.chmouel.liseur.data.db.BookOrbitBindingState
import com.chmouel.liseur.data.db.BookOrbitCfiRecord
import com.chmouel.liseur.data.db.LiseurDatabase
import java.io.IOException

data class BookOrbitCfiContext(
    val request: BookOrbitRequestContext,
    val bookUrl: String,
    val bookId: Long,
    val fileId: Long,
    val bindingRevision: Long,
) {
    fun matches(binding: BookOrbitBinding?): Boolean =
        binding != null && binding.accountKey == request.accountKey &&
            binding.bookUrl == bookUrl && binding.bookId == bookId &&
            binding.fileId == fileId && binding.revision == bindingRevision &&
            binding.fileFormat.equals("epub", ignoreCase = true) &&
            binding.stateValue in setOf(BookOrbitBindingState.SELECTED, BookOrbitBindingState.DOWNLOADED)
}

/** No sync registration: later phases can retain a fetched CFI without adopting it. */
class BookOrbitCfiRepository(private val database: LiseurDatabase) {
    suspend fun capture(bookUrl: String): BookOrbitCfiContext = database.withTransaction {
        val server = database.remoteServerDao().get()
        val request = server?.let(BookOrbitRequestContext::from) ?: stale()
        val binding = database.bookOrbitBindingDao().get(request.accountKey, bookUrl) ?: stale()
        BookOrbitCfiContext(request, bookUrl, binding.bookId, binding.fileId ?: stale(), binding.revision)
            .also { if (!it.matches(binding)) stale() }
    }

    suspend fun check(context: BookOrbitCfiContext) = database.withTransaction {
        checkCurrent(context)
    }

    suspend fun retain(context: BookOrbitCfiContext, raw: String): BookOrbitForeignCfi =
        database.withTransaction {
            checkCurrent(context)
            database.bookOrbitCfiDao().write(
                BookOrbitCfiRecord(
                    context.request.accountKey, context.bookUrl, context.bookId,
                    context.fileId, context.bindingRevision, raw,
                ),
            )
            foreign(context, raw)
        }

    suspend fun load(context: BookOrbitCfiContext): BookOrbitForeignCfi? = database.withTransaction {
        checkCurrent(context)
        val row = database.bookOrbitCfiDao().get(context.request.accountKey, context.bookUrl)
            ?: return@withTransaction null
        if (row.bookId != context.bookId || row.fileId != context.fileId ||
            row.bindingRevision != context.bindingRevision
        ) stale()
        foreign(context, row.rawCfi)
    }

    private suspend fun checkCurrent(context: BookOrbitCfiContext) {
        if (!context.request.matches(database.remoteServerDao().get()) ||
            !context.matches(database.bookOrbitBindingDao().get(context.request.accountKey, context.bookUrl))
        ) stale()
    }

    private fun foreign(context: BookOrbitCfiContext, raw: String) = BookOrbitForeignCfi.capture(
        context.request.accountKey, context.bookId, context.fileId, context.bindingRevision, raw,
    )

    private fun stale(): Nothing = throw BookOrbitIdentityChanged()
}

class BookOrbitIdentityChanged : IOException("The BookOrbit connection or selected file changed")
