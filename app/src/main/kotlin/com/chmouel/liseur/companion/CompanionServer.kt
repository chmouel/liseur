package com.chmouel.liseur.companion

import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.translate.TranslationError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The service and model the reading companion uses, as chosen now:
 * [serverId] and [model] name the recaps it writes, [generation] moves on
 * when the server's address or key changes, and [limits] bound what one
 * request may hold. [service] is the [CompanionService] that answers.
 */
data class CompanionChoice(
    val serverId: String,
    val url: String,
    val model: String,
    val origin: String,
    val generation: Int,
    val service: String = CompanionService.SERVER,
    val limits: CompanionLimits = CompanionLimits(),
)

/**
 * The reading companion's access to the service the reader chose for it.
 * Each lookup and each recapped chapter keeps the [CompanionChoice] it
 * started with, and a request made after it changed fails with
 * [TranslationError.Changed] rather than mixing two services or models.
 */
class CompanionServer internal constructor(
    private val settings: AppSettingsRepository,
    internal val server: ServerCompanionService,
    others: List<CompanionService> = emptyList(),
) {
    internal val services: List<CompanionService> = listOf(server) + others

    /** The chosen service; null for one this build lacks, such as Gemini from a Play backup restored on F-Droid. */
    internal val selected: Flow<CompanionService?> = settings.settings
        .map { s -> s.companionProvider ?: CompanionService.SERVER }
        .distinctUntilChanged()
        .map { id -> services.firstOrNull { it.id == id } }

    /** The services this phone can use, in the order the build lists them. */
    internal val offered: Flow<List<CompanionService>> =
        combine(services.map { service -> service.offered.map { if (it) service else null } }) { it.filterNotNull() }
            .distinctUntilChanged()

    /** The current choice; null until the chosen service is set up. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val choice: Flow<CompanionChoice?> = selected
        .flatMapLatest { it?.choice ?: flowOf(null) }
        .distinctUntilChanged()

    /** The choice as it stands, read once. */
    suspend fun current(): CompanionChoice? = choice.first()

    val codexEnabled: Flow<Boolean> = settings.settings.map { it.codexEnabled }.distinctUntilChanged()

    suspend fun setCodexEnabled(enabled: Boolean) = settings.setCodexEnabled(enabled)

    /** Whether background recaps are on. */
    val recapsEnabled: Flow<Boolean> = settings.settings.map { it.companionRecaps }.distinctUntilChanged()

    /** A chat bound to [choice]: every request goes to that service and model, or fails. */
    fun chat(choice: CompanionChoice): CompanionChat {
        val chat = services.firstOrNull { it.id == choice.service }?.chat(choice)
        return CompanionChat { messages, beforeRequest ->
            if (chat == null) throw TranslationError.NotSetUp()
            if (current() != choice) throw TranslationError.Changed()
            chat.reply(messages, beforeRequest)
        }
    }

    /** Has the companion use [service]; a listed server is chosen through [ServerCompanionService.select]. */
    internal suspend fun choose(service: CompanionService) = settings.setCompanionProvider(service.id)

    /** Turns background recaps on or off; off forgets every waiting chapter and stops the one under way. */
    suspend fun setRecaps(enabled: Boolean, store: CompanionStore) {
        settings.setCompanionRecaps(enabled)
        if (!enabled) store.stopRecaps()
    }
}
