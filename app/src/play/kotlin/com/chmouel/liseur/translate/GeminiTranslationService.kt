package com.chmouel.liseur.translate

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
import com.chmouel.liseur.tts.GeminiAccount
import com.chmouel.liseur.tts.ListedField
import com.chmouel.liseur.tts.Listing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Gemini on the reader's own key, set on the Services page. */
internal class GeminiTranslationService(
    private val account: GeminiAccount,
    private val settings: AppSettingsRepository,
    private val client: GeminiTranslationClient = GeminiTranslationClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : TranslationService {
    override val id = GeminiAccount.OWNER
    override val label = R.string.read_aloud_provider_gemini
    override val summary = R.string.translation_provider_gemini_summary
    override val icon = Icons.Outlined.AutoAwesome
    override val detectsLanguage = true
    override val configured: Flow<Boolean> = account.configured

    private val model: Flow<String> =
        settings.settings.map { GeminiTranslation.modelOf(it.translationGeminiModel) }.distinctUntilChanged()

    override suspend fun destination(): String = "Gemini"

    override suspend fun owner(): String = GeminiAccount.OWNER

    override suspend fun targets(source: String?): Map<String, PairState>? = null

    override suspend fun sources(): Set<String>? = null

    override suspend fun translate(passage: String, source: String?, target: String): String {
        val key = account.key() ?: throw TranslationError.NotSetUp()
        return client.translate(key, model.first(), source, target, passage)
    }

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
        if (!configured) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = stringResource(R.string.translation_gemini_key_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onManageServices) { Text(stringResource(R.string.translation_open_services)) }
            }
        }
        ListedField(
            title = stringResource(R.string.read_aloud_settings_server_model),
            placeholder = GeminiTranslation.DEFAULT_MODEL,
            loading = stringResource(R.string.read_aloud_settings_server_models_loading),
            stored = stored,
            enabled = configured,
            listing = listing,
            onSave = { name -> commit { settings.setTranslationGeminiModel(name) } },
            onRetry = { asked++ },
        )
    }

    private companion object {
        // The key of the one list there is, as a menu tells lists apart by address.
        const val MODELS = "gemini"
    }
}
