package com.chmouel.liseur.tts

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.data.remote.LocalNetworkAccess
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.reader.chrome.ChromePill

/** Asks only for a blocked LAN address, and offers settings after a permanent denial. */
@Composable
internal fun SpeechLocalNetworkPrompt(
    access: LocalNetworkAccess,
    url: String,
    theme: ReaderTheme? = null,
): Boolean {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val grants by access.grants.collectAsStateWithLifecycle(0L)
    var revision by remember { mutableIntStateOf(0) }
    var blocked by remember(url) { mutableStateOf<Boolean?>(if (access.granted) false else null) }
    var asked by rememberSaveable(url) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        access.recheck()
        revision++
    }
    LifecycleResumeEffect(access) {
        access.recheck()
        revision++
        onPauseOrDispose { }
    }
    LaunchedEffect(url, grants, revision) {
        blocked = access.blocks(url)
    }
    LaunchedEffect(url, blocked) {
        if (blocked == true && !asked) {
            asked = true
            launcher.launch(LocalNetworkAccess.PERMISSION)
        }
    }
    if (blocked == true) {
        val settingsOnly = asked && activity?.shouldShowRequestPermissionRationale(LocalNetworkAccess.PERMISSION) == false
        val notice: @Composable () -> Unit = {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.server_local_network_blocked))
                TextButton(onClick = {
                    if (settingsOnly) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } else {
                        launcher.launch(LocalNetworkAccess.PERMISSION)
                    }
                }) {
                    Text(stringResource(if (settingsOnly) R.string.server_local_network_settings else R.string.server_local_network_retry))
                }
            }
        }
        if (theme == null) notice() else ChromePill(theme = theme) { notice() }
    }
    return blocked == false
}
