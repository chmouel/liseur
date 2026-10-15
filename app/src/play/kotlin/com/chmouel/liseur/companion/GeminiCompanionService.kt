package com.chmouel.liseur.companion

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.providers.ServiceOption
import com.chmouel.liseur.translate.GeminiTranslation
import com.chmouel.liseur.translate.GeminiTranslationClient
import com.chmouel.liseur.translate.TranslationError
import com.chmouel.liseur.tts.GeminiAccount
import com.chmouel.liseur.tts.ListedField
import com.chmouel.liseur.tts.Listing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Gemini on the reader's own key, set on the Services page. A new key
 * fences a lookup or recap under way, as a server's new key does.
 */
internal class GeminiCompanionService(
    private val account: GeminiAccount,
    private val settings: AppSettingsRepository,
    private val client: GeminiTranslationClient = GeminiTranslationClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : CompanionService {
    override val id = GeminiAccount.OWNER
    override val offered: Flow<Boolean> = flowOf(true)
    override val privacy = R.string.companion_gemini_privacy

    private val model: Flow<String> =
        settings.settings.map { GeminiTranslation.modelOf(it.companionGeminiModel) }.distinctUntilChanged()

    override val choice: Flow<CompanionChoice?> = combine(account.configured, model, account.generation) { configured, model, generation ->
        if (configured) CompanionChoice(id, GeminiTranslation.ENDPOINT, model, id, generation, id) else null
    }.distinctUntilChanged()

    override fun chat(choice: CompanionChoice): CompanionChat = CompanionChat { messages, beforeRequest ->
        val key = account.key() ?: throw TranslationError.NotSetUp()
        client.chat(key, choice.model, messages.map { it.role to it.content }) {
            if (account.generation.value != choice.generation) throw TranslationError.Changed()
            beforeRequest()
        }
    }

    @Composable
    override fun heading(): ServiceHeading {
        val configured by account.configured.collectAsState()
        val stored by model.collectAsState(initial = GeminiTranslation.DEFAULT_MODEL)
        return ServiceHeading(
            Icons.Outlined.AutoAwesome,
            stringResource(R.string.read_aloud_provider_gemini),
            if (configured) stored else stringResource(R.string.read_aloud_settings_entry_missing),
        )
    }

    @Composable
    override fun summary(): String? {
        val configured by account.configured.collectAsState()
        val stored by model.collectAsState(initial = GeminiTranslation.DEFAULT_MODEL)
        return if (configured) stringResource(R.string.companion_settings_entry_summary, stringResource(R.string.read_aloud_provider_gemini), stored) else null
    }

    @Composable
    override fun options(inUse: Boolean, pick: (suspend () -> Unit) -> Unit): List<ServiceOption> = listOf(
        ServiceOption(
            selected = inUse,
            icon = Icons.Outlined.AutoAwesome,
            headline = stringResource(R.string.read_aloud_provider_gemini),
            supporting = stringResource(R.string.companion_provider_gemini_summary),
            onClick = { pick { settings.chooseCompanionGemini(id, RECOMMENDED_MODEL) } },
        ),
    )

    /** Saves in the service's scope, so a model typed as the screen closes is still saved. */
    private fun commit(save: suspend () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { save() }
    }

    @Composable
    override fun SettingsRows(onManageServices: () -> Unit) {
        val configured by account.configured.collectAsState()
        val stored by model.collectAsState(initial = GeminiTranslation.DEFAULT_MODEL)
        val generation by account.generation.collectAsState()
        var listing by remember(generation) { mutableStateOf<Listing?>(null) }
        var asked by remember(generation) { mutableIntStateOf(0) }
        LaunchedEffect(generation, asked, configured) {
            if (!configured || asked == 0) return@LaunchedEffect
            listing = Listing.Loading(MODELS)
            val key = account.key()
            listing = try {
                if (key == null) throw TranslationError.NotSetUp()
                val models = client.models(key)
                if (models.isEmpty()) Listing.Failed(MODELS, R.string.read_aloud_settings_server_models_none) else Listing.Loaded(MODELS, models)
            } catch (e: TranslationError) {
                Listing.Failed(
                    MODELS,
                    when (e) {
                        is TranslationError.Network -> R.string.read_aloud_settings_server_unreachable
                        is TranslationError.InvalidKey -> R.string.translation_gemini_key_refused
                        else -> R.string.read_aloud_settings_server_models_failed
                    },
                )
            }
        }
        Column(Modifier.padding(top = 16.dp)) {
            if (!configured) {
                Text(
                    text = stringResource(R.string.companion_gemini_key_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onManageServices) { Text(stringResource(R.string.translation_open_services)) }
            }
            ListedField(
                title = stringResource(R.string.companion_settings_model),
                placeholder = GeminiTranslation.DEFAULT_MODEL,
                loading = stringResource(R.string.read_aloud_settings_server_models_loading),
                stored = stored,
                enabled = configured,
                listing = listing,
                onSave = { name -> commit { settings.setCompanionGeminiModel(name) } },
                onRetry = { asked++ },
                detail = { id -> if (id == RECOMMENDED_MODEL) stringResource(R.string.companion_gemini_model_recommended) else null },
            )
        }
    }

    private companion object {
        // The key of the one list there is, as a menu tells lists apart by address.
        const val MODELS = "gemini"

        /**
         * Follows the companion's JSON actions and citations more reliably
         * than the translation default, in the maintainer's testing. Stored
         * when Gemini is first chosen, so earlier users keep their default.
         */
        const val RECOMMENDED_MODEL = "gemini-2.5-flash-lite"
    }
}
