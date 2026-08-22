package com.chmouel.liseur.ui.eink

import android.util.Log
import android.view.View
import android.webkit.WebView
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Onyx's screen controller, reached without depending on it.
 *
 * Every call here is a guess that is allowed to be wrong. The class may
 * not exist, may exist under another name, may exist with these methods
 * missing or taking other arguments, or may exist and throw. All of
 * those are the same outcome — the app goes on drawing exactly as it did
 * before — and the last of them permanently retires this object, because
 * a controller that threw once will throw every time and there is
 * nothing to gain by asking it again on every page turn.
 *
 * Nothing is bundled and nothing is linked. This compiles and runs on a
 * device that has never heard of Onyx, which is the only reason it can
 * exist in an app that must stay free software from source.
 */
class OnyxEInkDisplay private constructor(
    override val vendor: String,
    private val setUpdateMode: Method,
    private val setContrast: Method,
    private val regal: Any,
) : EInkDisplay {

    private var retired = false

    override fun readingMode(view: View) {
        attempt("readingMode") { setUpdateMode.invoke(null, view, regal) }
    }

    override fun optimizeWebView(view: WebView) {
        attempt("optimizeWebView") { setContrast.invoke(null, view, true) }
    }

    /**
     * Runs [block], and on any failure gives up on this device for good.
     *
     * Everything reflection can raise is caught, and nothing else is: a
     * method that has gone missing or a controller that throws is exactly
     * the surprise this was written to expect, whereas an
     * `OutOfMemoryError` coming back through a vendor call is not this
     * code's to swallow.
     */
    private inline fun attempt(what: String, block: () -> Unit) {
        if (retired) return
        val failure = guard(block) ?: return
        retired = true
        Log.i(TAG, "$vendor screen controller withdrawn after $what: $failure")
    }

    companion object {
        private const val TAG = "OnyxEInkDisplay"
        private const val SET_UPDATE_MODE = "setViewDefaultUpdateMode"
        private const val SET_CONTRAST = "setWebViewContrastOptimize"

        /** The update mode Onyx documents as being for pages of text. */
        private const val REGAL = "REGAL"

        /**
         * Binds to whichever shape this device has, or to nothing.
         *
         * A shape is only taken once everything it needs has actually
         * been found: both classes, the enum constant, and both methods,
         * with the arguments they are called with and static. A shape
         * that falls short is passed over for the next rather than
         * accepted and left to fail later — partly because a later
         * spelling may be the right one, and mostly because [vendor] is
         * shown to the reader as the answer to "did this work", and it
         * must not say Onyx on a device where nothing will ever be
         * called.
         */
        fun bind(
            shapes: List<EInkVendorShape> = ONYX_SHAPES,
            loader: ClassLoader? = OnyxEInkDisplay::class.java.classLoader,
        ): EInkDisplay = shapes.firstNotNullOfOrNull { resolve(it, loader) } ?: EInkDisplay.Absent

        /** One shape, fully resolved, or null because something was missing. */
        private fun resolve(shape: EInkVendorShape, loader: ClassLoader?): OnyxEInkDisplay? {
            var bound: OnyxEInkDisplay? = null
            val failure = guard {
                val controller = loadClass(shape.controllerClass, loader) ?: return@guard
                val modes = loadClass(shape.updateModeClass, loader) ?: return@guard
                val regal = modes.enumConstants
                    ?.firstOrNull { (it as? Enum<*>)?.name == REGAL }
                    ?: return@guard
                // The enum *class* is what the method declares, not the
                // constant's own class: a constant carrying a body is an
                // instance of an anonymous subclass, and looking the
                // method up by that would never match.
                val setUpdateMode = controller
                    .getMethod(SET_UPDATE_MODE, View::class.java, modes)
                    .takeIf { Modifier.isStatic(it.modifiers) } ?: return@guard
                val setContrast = controller
                    .getMethod(SET_CONTRAST, WebView::class.java, Boolean::class.javaPrimitiveType)
                    .takeIf { Modifier.isStatic(it.modifiers) } ?: return@guard
                bound = OnyxEInkDisplay(shape.vendor, setUpdateMode, setContrast, regal)
            }
            failure?.let { Log.i(TAG, "no controller at ${shape.controllerClass}: $it") }
            return bound
        }

        private fun loadClass(name: String, loader: ClassLoader?): Class<*>? =
            try {
                Class.forName(name, false, loader)
            } catch (e: ClassNotFoundException) {
                null
            }
    }
}

/**
 * Runs [block] and returns what went wrong, or null if nothing did.
 *
 * These are what reflection against a class this code has never seen can
 * raise: the lookups themselves, the linkage of a class the firmware may
 * have built differently, and whatever the vendor's own method throws
 * once it is running. Anything outside that set is left alone rather
 * than swallowed.
 *
 * The vendor's own throw needs unwrapping before that promise is worth
 * anything. Everything a called method raises comes back wrapped in an
 * [InvocationTargetException], which is itself a
 * [ReflectiveOperationException] — so catching that alone would quietly
 * swallow a [VirtualMachineError] raised inside the firmware, which is
 * the exact thing the narrow catch was written to stop doing.
 */
private inline fun guard(block: () -> Unit): Throwable? =
    try {
        block()
        null
    } catch (e: InvocationTargetException) {
        e.cause?.let { if (it.isFatal()) throw it }
        e
    } catch (e: ReflectiveOperationException) {
        e
    } catch (e: LinkageError) {
        e
    } catch (e: RuntimeException) {
        e
    }

/** Not this code's to catch, wherever it was raised. */
private fun Throwable.isFatal(): Boolean = this is VirtualMachineError || this is ThreadDeath
