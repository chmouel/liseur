package com.chmouel.liseur.ui.library

import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.chmouel.liseur.R

@Composable
internal fun BrowserDownloadDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(R.string.browse_download_on_website)) },
        confirmButton = {
            TextButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
                runCatching { context.startActivity(intent) }
                onDismiss()
            }) { Text(stringResource(R.string.browse_open_browser)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
