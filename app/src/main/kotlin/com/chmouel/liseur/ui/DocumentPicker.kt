package com.chmouel.liseur.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.chmouel.liseur.R

/**
 * Launches a system document picker, or tells the user none is installed:
 * some e-ink readers, TV boxes and trimmed ROMs ship without DocumentsUI.
 * Returns false when nothing could handle the request.
 */
fun <I> ActivityResultLauncher<I>.launchPicker(context: Context, input: I): Boolean =
    try {
        launch(input)
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_file_picker, Toast.LENGTH_LONG).show()
        false
    }
