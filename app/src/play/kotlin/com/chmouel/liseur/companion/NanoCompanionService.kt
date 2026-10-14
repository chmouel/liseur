package com.chmouel.liseur.companion

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State as ComposeState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.providers.ServiceOption
import com.chmouel.liseur.translate.TranslationError
import com.google.mlkit.common.MlKit
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Gemini Nano, run on the phone by Android's AICore through ML Kit's
 * Prompt API. Only some phones have it, and it reads about 4,000 tokens
 * at once, so lookups and recaps send it far less of the book.
 *
 * ML Kit is started only once Nano is the chosen service, as for ML Kit
 * translation: until then Liseur only looks for AICore among the
 * installed apps, so a reader who never picks Nano never starts the SDK or
 * its diagnostics.
 */
internal class NanoCompanionService(
    context: Context,
    private val settings: AppSettingsRepository,
    private val model: Lazy<GenerativeModel> = lazy {
        MlKit.initialize(context.applicationContext)
        Generation.getClient()
    },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : CompanionService {
    override val id = ID
    override val privacy = R.string.companion_nano_privacy

    private sealed interface State {
        data object Unknown : State
        data object Unavailable : State
        data class Downloadable(val failed: Boolean) : State
        data class Downloading(val done: Long, val total: Long) : State
        data object Available : State
    }

    private val state = MutableStateFlow<State>(State.Unknown)
    private var download: Job? = null
    private var systemPrompt: Boolean? = null

    private val packages = context.applicationContext.packageManager

    override val offered: Flow<Boolean> = flow { emit(aicoreInstalled()) }.flowOn(Dispatchers.IO)

    private fun aicoreInstalled(): Boolean = try {
        packages.getApplicationInfo(AICORE, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override val choice: Flow<CompanionChoice?> = state.onStart { refresh() }
        .map { if (it == State.Available) CHOICE else null }
        .distinctUntilChanged()

    private suspend fun refresh() {
        if (download?.isActive == true) return
        val status = try {
            model.value.checkStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A phone without AICore, or one too old for it.
            Log.w(TAG, "Gemini Nano status unknown: ${e.javaClass.simpleName}")
            FeatureStatus.UNAVAILABLE
        }
        when (status) {
            FeatureStatus.AVAILABLE -> state.value = State.Available
            FeatureStatus.DOWNLOADING -> startDownload()
            FeatureStatus.DOWNLOADABLE -> state.update { if (it is State.Downloadable) it else State.Downloadable(failed = false) }
            else -> state.value = State.Unavailable
        }
    }

    /** Has AICore fetch the model, or follows a download already under way. */
    private fun startDownload() {
        if (download?.isActive == true) return
        state.value = State.Downloading(0, 0)
        download = scope.launch {
            try {
                model.value.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> state.value = State.Downloading(0, status.bytesToDownload)
                        is DownloadStatus.DownloadProgress -> state.update {
                            State.Downloading(status.totalBytesDownloaded, (it as? State.Downloading)?.total ?: 0)
                        }
                        is DownloadStatus.DownloadCompleted -> state.value = State.Available
                        is DownloadStatus.DownloadFailed -> {
                            Log.w(TAG, "Gemini Nano download failed: ${status.e.errorCode}")
                            state.value = State.Downloadable(failed = true)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Nano download failed: ${e.javaClass.simpleName}")
                state.value = State.Downloadable(failed = true)
            }
        }
    }

    override fun chat(choice: CompanionChoice): CompanionChat = CompanionChat { messages, beforeRequest ->
        val generative = model.value
        beforeRequest()
        try {
            val system = systemPrompt ?: generative.isSystemPromptAvailable().also { systemPrompt = it }
            val (instruction, prompt) = NanoPrompt.of(messages, system)
            val request = if (instruction != null) {
                generateContentRequest(SystemInstruction(instruction), TextPart(prompt)) { temperature = 0f }
            } else {
                generateContentRequest(TextPart(prompt)) { temperature = 0f }
            }
            // Over the limit the model would read only part of the book it was given: fail instead.
            if (generative.countTokens(request).totalTokens > generative.getTokenLimit()) throw CompanionError.TooLong()
            val candidate = generative.generateContent(request).candidates.firstOrNull()
            NanoPrompt.answer(candidate?.text, candidate?.finishReason)
        } catch (e: GenAiException) {
            throw NanoPrompt.errorFor(e.errorCode)
        }
    }

    @Composable
    override fun heading(): ServiceHeading = ServiceHeading(
        Icons.Outlined.PhoneAndroid,
        stringResource(R.string.companion_provider_nano),
        stringResource(R.string.companion_provider_nano_summary),
    )

    /** The model's state, asked of ML Kit again each time a screen shows it. */
    @Composable
    private fun watched(): ComposeState<State> {
        LaunchedEffect(Unit) { refresh() }
        return state.collectAsState()
    }

    @Composable
    override fun summary(): String? {
        val current by watched()
        return if (current == State.Available) stringResource(R.string.companion_provider_nano) else null
    }

    @Composable
    override fun options(inUse: Boolean, pick: (suspend () -> Unit) -> Unit): List<ServiceOption> = listOf(
        ServiceOption(
            selected = inUse,
            icon = Icons.Outlined.PhoneAndroid,
            headline = stringResource(R.string.companion_provider_nano),
            supporting = stringResource(R.string.companion_provider_nano_summary),
            onClick = { pick { settings.setCompanionProvider(id) } },
        ),
    )

    @Composable
    override fun SettingsRows(onManageServices: () -> Unit) {
        val current by watched()
        Column(Modifier.padding(top = 16.dp)) {
            val text = when (val s = current) {
                State.Available -> R.string.companion_nano_ready
                is State.Downloading -> R.string.companion_nano_downloading
                is State.Downloadable -> if (s.failed) R.string.companion_nano_download_failed else R.string.companion_nano_downloadable
                State.Unknown, State.Unavailable -> R.string.companion_nano_unavailable
            }
            Text(
                text = stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val s = current) {
                is State.Downloadable -> TextButton(onClick = ::startDownload) { Text(stringResource(R.string.companion_nano_download)) }
                is State.Downloading -> if (s.total > 0) {
                    LinearProgressIndicator(progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                else -> Unit
            }
            Text(
                text = stringResource(R.string.companion_nano_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    companion object {
        const val ID = "nano"
        private const val TAG = "NanoCompanion"

        /** Android's on-device AI service, which runs Gemini Nano. */
        private const val AICORE = "com.google.android.aicore"

        /** Sized for about 4,000 tokens in: the lookup prompt, its passages, and the model's own turns. */
        val LIMITS = CompanionLimits(
            investigation = InvestigationLimits(rounds = 3, operations = 6, sourceCharacters = 5_000, requests = 5, seedCharacters = 3_000),
            recapBatch = 6_000,
        )

        private val CHOICE = CompanionChoice(ID, "", "gemini-nano", ID, 0, ID, LIMITS)
    }
}

/** Gemini Nano's side of a chat, apart from the model for testing. */
internal object NanoPrompt {
    /**
     * The system instruction and the one prompt [messages] become: Nano
     * takes a single turn, so earlier turns are written out in it. With no
     * [systemPrompt] support, the instruction leads the prompt instead.
     */
    fun of(messages: List<ChatMessage>, systemPrompt: Boolean): Pair<String?, String> {
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }.ifEmpty { null }
        val turns = messages.filter { it.role != "system" }
        val conversation = turns.singleOrNull()?.takeIf { it.role == "user" }?.content
            ?: turns.joinToString("\n\n") { "[${if (it.role == "assistant") "you" else "user"}]\n${it.content}" } +
            "\n\n[you]\n"
        return if (systemPrompt || system == null) system to conversation else null to "$system\n\n$conversation"
    }

    /** The text of a reply. Throws [TranslationError] for one cut short, unfinished or empty. */
    fun answer(text: String?, finish: Int?): String {
        if (finish == Candidate.FinishReason.MAX_TOKENS) throw TranslationError.Truncated()
        if (finish != null && finish != Candidate.FinishReason.STOP) throw TranslationError.Malformed("finished with $finish")
        return text?.takeIf { it.isNotBlank() } ?: throw TranslationError.Empty()
    }

    fun errorFor(code: Int): Exception = when (code) {
        // Busy, or over the phone's quota for this app: worth asking again later.
        GenAiException.ErrorCode.BUSY, GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> TranslationError.RateLimited(code)
        GenAiException.ErrorCode.REQUEST_TOO_LARGE -> CompanionError.TooLong()
        GenAiException.ErrorCode.NOT_AVAILABLE, GenAiException.ErrorCode.NOT_SUPPORTED,
        GenAiException.ErrorCode.AICORE_INCOMPATIBLE, GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> TranslationError.NotSetUp()
        GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR, GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR ->
            TranslationError.Malformed("code $code")
        else -> TranslationError.Service(code)
    }
}
