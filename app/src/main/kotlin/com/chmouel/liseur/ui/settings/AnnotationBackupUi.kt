package com.chmouel.liseur.ui.settings

import androidx.compose.runtime.Stable
import com.chmouel.liseur.data.library.BackupSummary
import com.chmouel.liseur.data.library.Inspection

/**
 * Everything the highlights-backup card needs to show, as state rather
 * than as events.
 *
 * The card is a picture of this: what an export would carry, what a
 * picked file would do, and how the last attempt went. Nothing here
 * Toasts, because a toast vanishes and the question it answered —
 * "did that work?" — usually occurs a few seconds later.
 */
@Stable
class AnnotationBackupUi(
    val summary: BackupSummary?,
    /** The file currently being asked about, if one is. */
    val pendingImport: Inspection?,
    /** How the last export or import ended, in words already made. */
    val status: String?,
    val export: () -> Unit,
    val restore: () -> Unit,
    /** Applies the file being asked about. */
    val confirmImport: () -> Unit,
    /** Sets the file back down, untouched. */
    val dismissImport: () -> Unit,
)
