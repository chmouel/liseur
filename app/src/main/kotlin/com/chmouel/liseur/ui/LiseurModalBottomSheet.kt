package com.chmouel.liseur.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * A Material sheet that does not repaint the page behind it on e-paper.
 *
 * Material's default scrim dims the whole window. On electronic paper that
 * turns opening a small control into a full-screen refresh and leaves the
 * dimmed text behind as ghosting when it closes. The sheet itself is already
 * opaque; dropping the scrim and its lift leaves only the region containing
 * the controls to change. The popup appears in its final position immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiseurModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val eInk = LocalEInk.current
    if (!eInk) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            content = content,
        )
        return
    }

    // A popup has no hidden-to-expanded state to animate through and no
    // window-sized scrim. Focusable keeps taps and Back with the sheet;
    // clicking outside dismisses it without forwarding that click to the page.
    // Clipping off keeps the keyboard from resizing the popup's own window:
    // a sheet that raises the IME already lifts itself with imePadding(), and
    // a resized window would take that space a second time and squeeze the
    // sheet's body away.
    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .widthIn(max = BottomSheetDefaults.SheetMaxWidth)
                .fillMaxWidth(),
        ) {
            Column(Modifier.navigationBarsPadding()) {
                BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                content()
            }
        }
    }
}
