package com.chmouel.liseur.ui.eink

import android.util.Log
import android.view.View
import android.webkit.WebView
import java.lang.reflect.Method

/**
 * Onyx's screen controller, reached without depending on it.
 *
 * Every call here is a guess that is allowed to be wrong. The class may
 * not exist, may exist under another name, may exist with these methods
 * missing or taking other arguments, or may exist and throw. All of
 * those are the same outcome — the app goes on drawing exactly as it
 * did before — and the first of them permanently retires this object,
 * because a controller that failed once will fail every time and there
 * is nothing to gain by asking it again on every page turn.
 *
 * Nothing is bundled and nothing is linked. This compiles and runs on a
 * device that has never heard of Onyx, which is the only reason it can
 * exist in an app that must stay free software from source.
 */
class OnyxEInkDisplay private constructor(
    override val vendor: String,
    private val controller: Class<*>,
    private val updateMode: Class<*>,
    private val regal: Any,
) : EInkDisplay {

    private var retired = false
    private var touched = false

    override fun readingMode(view: View) {
        attempt("readingMode") {
            controller
                .getMethod(SET_UPDATE_MODE, View::class.java, updateMode)
                .invoke(null, view, regal)
            touched = true
        }
    }

    override fun optimizeWebView(view: WebView) {
        attempt("optimizeWebView") {
            controller
                .getMethod(SET_CONTRAST, WebView::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, view, true)
            touched = true
        }
    }

    override fun release() {
        if (!touched || retired) return
        touched = false
        // There is deliberately nothing to undo for the two calls above:
        // both are set per view, and the view goes away with the reader.
        // This exists so that the moment anything is added here that is
        // *not* per view — a fast mode applied by application name, say —
        // there is already a place it must be given back, called from
        // every path that leaves the reader.
    }

    /**
     * Runs [block], and on any failure gives up on this device for good.
     *
     * The catch is deliberately everything. This is reflection against a
     * class nobody here has ever seen, on firmware that varies between
     * devices sold under the same name, and the correct response to any
     * surprise is the behaviour the app had before it tried.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun attempt(what: String, block: () -> Unit) {
        if (retired) return
        try {
            block()
        } catch (e: Throwable) {
            retired = true
            Log.i(TAG, "$vendor screen controller withdrawn after $what: $e")
        }
    }

    companion object {
        private const val TAG = "OnyxEInkDisplay"
        private const val SET_UPDATE_MODE = "setViewDefaultUpdateMode"
        private const val SET_CONTRAST = "setWebViewContrastOptimize"

        /** The update mode Onyx documents as being for pages of text. */
        private const val REGAL = "REGAL"

        /**
         * Binds to whichever controller this device has, or to nothing.
         *
         * The enum constant is resolved here rather than at each call
         * because failing to find it is the same as not having the
         * controller at all: every method worth calling takes one. The
         * enum *class* is kept alongside the constant, because a constant
         * that carries a body is an instance of an anonymous subclass and
         * would never match the parameter type the method declares.
         */
        @Suppress("TooGenericExceptionCaught")
        fun bind(
            shapes: List<EInkVendorShape> = ONYX_SHAPES,
            loader: ClassLoader? = OnyxEInkDisplay::class.java.classLoader,
        ): EInkDisplay {
            val shape = firstAvailableShape(shapes) { name -> loadClass(name, loader) != null }
                ?: return EInkDisplay.Absent
            return try {
                val controller = loadClass(shape.controllerClass, loader)
                    ?: return EInkDisplay.Absent
                val modes = loadClass(shape.updateModeClass, loader)
                    ?: return EInkDisplay.Absent
                val regal = modes.enumConstants
                    ?.firstOrNull { (it as? Enum<*>)?.name == REGAL }
                    ?: return EInkDisplay.Absent
                OnyxEInkDisplay(shape.vendor, controller, modes, regal)
            } catch (e: Throwable) {
                Log.i(TAG, "no usable ${shape.vendor} screen controller: $e")
                EInkDisplay.Absent
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun loadClass(name: String, loader: ClassLoader?): Class<*>? = try {
            Class.forName(name, false, loader)
        } catch (e: Throwable) {
            null
        }
    }
}
