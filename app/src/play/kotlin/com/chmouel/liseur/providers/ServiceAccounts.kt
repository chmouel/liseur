package com.chmouel.liseur.providers

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.chmouel.liseur.R
import com.chmouel.liseur.tts.ApiKeyStore
import com.chmouel.liseur.tts.GeminiAccount
import com.chmouel.liseur.tts.KeyRow
import com.chmouel.liseur.tts.gemini
import com.chmouel.liseur.ui.settings.SettingsGroup

/** The Play build's accounts on the Services page: the reader's Gemini key. */
internal class ServiceAccounts(context: Context, connections: ServerConnections) {
    val gemini = GeminiAccount(ApiKeyStore.gemini(context), connections)

    /** The accounts' rows at the top of the Services page. */
    @Composable
    fun Rows() {
        val configured by gemini.configured.collectAsState()
        val failure by gemini.keyFailure.collectAsState()
        SettingsGroup(stringResource(R.string.read_aloud_provider_gemini)) {
            KeyRow(
                title = stringResource(R.string.services_gemini_key),
                missing = stringResource(R.string.services_gemini_key_missing),
                privacy = stringResource(R.string.services_gemini_privacy),
                owner = GeminiAccount.OWNER,
                configured = configured,
                failed = failure == GeminiAccount.OWNER,
                onKey = { _, key, done -> gemini.commitKey(key, done) },
                onClear = { gemini.commitKeyRemoval() },
            )
        }
    }
}
