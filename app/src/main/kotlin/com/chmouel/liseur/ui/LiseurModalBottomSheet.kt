package com.chmouel.liseur.ui

import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.annotation.RequiresApi
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
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.doOnAttach
import com.chmouel.liseur.ui.theme.SystemBarIcons
import com.chmouel.liseur.ui.theme.isDarkSurface

/**
 * A Material sheet that does not repaint the page behind it on e-paper.
 *
 * Material's default scrim dims the whole window. On electronic paper that
 * turns opening a small control into a full-screen refresh and leaves the
 * dimmed text behind as ghosting when it closes. The sheet itself is already
 * opaque; dropping the scrim and its lift leaves only the region containing
 * the controls to change. The popup appears in its final position immediately.
 *
 * [containerColor] and [contentColor] are Material's by default. A sheet
 * that sits over the page itself — a note, say — passes the reading
 * theme's instead, for the same reason the footnote card does: a white
 * sheet over a black page at night is a lamp in the face.
 *
 * A sheet opened from another sheet passes a transparent [scrimColor], as
 * the one below already dims the page. E-paper has no scrim to pass.
 *
 * [onBack], when set, takes Back instead of dismissing: a sheet with a
 * page inside it steps back to the previous page. It is a parameter
 * rather than a `BackHandler` in [content] because the e-paper popup is a
 * window of its own that takes Back before any handler inside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiseurModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val eInk = LocalEInk.current
    if (!eInk) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            containerColor = containerColor,
            contentColor = contentColor,
            scrimColor = scrimColor,
            properties = ModalBottomSheetProperties(shouldDismissOnBackPress = onBack == null),
            dragHandle = {
                BottomSheetDefaults.DragHandle(color = contentColor.copy(alpha = 0.4f))
            },
            content = {
                // The sheet is a window of its own, and Material points
                // the bar icons at its paper once, when that window is
                // built. A reader changing the reading theme from the
                // sheet in front of them repaints the page and the sheet
                // underneath the icons without moving them, which leaves
                // a black clock on a black status bar (#225). Said here,
                // inside the sheet's own window, it moves with the paper.
                SystemBarIcons(dark = isDarkSurface(containerColor))
                if (onBack != null) {
                    DialogBack(onBack)
                }
                content()
            },
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
    //
    // An unclipped window is not laid out around a display cutout either, so
    // the insets a ModalBottomSheet gets for free are applied here by hand:
    // the top, or a full-height sheet runs under the status bar, and the
    // sides, or a landscape cutout sits over the controls.
    //
    // A popup's alignment is relative to the layout it is called from,
    // not the window. Called from inside scrolling content, the bottom of
    // that content is usually below the screen, and the sheet with it.
    //
    // The popup would answer Back with the same dismissal as an outside
    // click, before any BackHandler inside it hears of it, so Back is taken
    // on the popup's own window instead. Before Android 9 there is no hook
    // for a key nothing handled, and Back keeps closing the whole sheet.
    val back by rememberUpdatedState(onBack ?: onDismissRequest)
    val ownBack = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    Popup(
        popupPositionProvider = WindowBottomPosition,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, dismissOnBackPress = !ownBack, clippingEnabled = false),
    ) {
        if (ownBack) PopupBack { back() }
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, contentColor.copy(alpha = 0.3f)),
            modifier = modifier
                .windowInsetsPadding(
                    WindowInsets.safeDrawing
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .widthIn(max = BottomSheetDefaults.SheetMaxWidth)
                .fillMaxWidth(),
        ) {
            Column(Modifier.navigationBarsPadding()) {
                BottomSheetDefaults.DragHandle(
                    color = contentColor.copy(alpha = 0.4f),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                content()
            }
        }
    }
}

@Composable
private fun DialogBack(onBack: () -> Unit) {
    val view = LocalView.current
    val current by rememberUpdatedState(onBack)
    val owner = (view.parent as DialogWindowProvider).window.callback as OnBackPressedDispatcherOwner
    DisposableEffect(owner) {
        // Register directly: the sheet inherits the activity's lifecycle, not the dialog's.
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = current()
        }
        owner.onBackPressedDispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }
}

/**
 * Back on the popup's window: through its back callbacks where predictive
 * back is on (Android 13 and later, as the manifest opts in), and as a key
 * no view handled otherwise. Only one of the two ever sees a given press.
 */
@RequiresApi(Build.VERSION_CODES.P)
@Composable
private fun PopupBack(onBack: () -> Unit) {
    val view = LocalView.current
    val current by rememberUpdatedState(onBack)
    DisposableEffect(view) {
        val keys = View.OnUnhandledKeyEventListener { _, event ->
            if (event.keyCode != KeyEvent.KEYCODE_BACK && event.keyCode != KeyEvent.KEYCODE_ESCAPE) {
                return@OnUnhandledKeyEventListener false
            }
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) current()
            true
        }
        view.addOnUnhandledKeyEventListener(keys)
        var unregister: (() -> Unit)? = null
        var disposed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            view.doOnAttach { if (!disposed) unregister = PopupBackApi33.register(it) { current() } }
        }
        onDispose {
            disposed = true
            view.removeOnUnhandledKeyEventListener(keys)
            unregister?.invoke()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object PopupBackApi33 {
    fun register(view: View, onBack: () -> Unit): (() -> Unit)? {
        val dispatcher = view.findOnBackInvokedDispatcher() ?: return null
        val callback = OnBackInvokedCallback { onBack() }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        return { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}

/**
 * Bottom centre of the window, wherever the sheet was called from. With
 * clipping off, [windowSize] is the whole window, as Material's sheet is.
 */
internal object WindowBottomPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = (windowSize.width - popupContentSize.width) / 2,
        y = windowSize.height - popupContentSize.height,
    )
}
