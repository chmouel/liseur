package com.chmouel.liseur.ui.eink

import android.view.View
import android.webkit.WebView

/**
 * Stand-ins for the vendor classes, so that the reflection can be tested.
 *
 * The real controller exists only on the devices, and there is no device
 * here. What there is, is a set of classes shaped the several ways a
 * device's controller might be shaped — right, absent, non-static, one
 * method short, and throwing — which is enough to ask the binder every
 * question that matters except whether Onyx spells it the way this
 * guesses.
 *
 * Arguments are nullable because these are called with nulls: what is
 * under test is whether the call is made and what happens when it fails,
 * not what a real panel would do with a real view.
 */
enum class FakeUpdateMode { DU, GC, REGAL }

enum class FakeModeWithoutRegal { DU, GC }

object FakeController {
    var updateModeCalls = 0
    var contrastCalls = 0

    fun reset() {
        updateModeCalls = 0
        contrastCalls = 0
    }

    @JvmStatic
    fun setViewDefaultUpdateMode(view: View?, mode: FakeUpdateMode?) {
        updateModeCalls++
    }

    @JvmStatic
    fun setWebViewContrastOptimize(view: WebView?, enable: Boolean) {
        contrastCalls++
    }
}

/** The methods are there, but on instances, so nothing can be invoked. */
object FakeInstanceController {
    fun setViewDefaultUpdateMode(view: View?, mode: FakeUpdateMode?) = Unit
    fun setWebViewContrastOptimize(view: WebView?, enable: Boolean) = Unit
}

/** Names the controller, but never learned about web views. */
object FakeHalfController {
    @JvmStatic
    fun setViewDefaultUpdateMode(view: View?, mode: FakeUpdateMode?) = Unit
}

/** Everything in the right place, and it throws anyway. */
object FakeThrowingController {
    var calls = 0

    fun reset() {
        calls = 0
    }

    @JvmStatic
    fun setViewDefaultUpdateMode(view: View?, mode: FakeUpdateMode?): Unit {
        calls++
        throw UnsupportedOperationException("no panel here")
    }

    @JvmStatic
    fun setWebViewContrastOptimize(view: WebView?, enable: Boolean): Unit {
        calls++
        throw UnsupportedOperationException("no panel here")
    }
}

/**
 * Fails in a way that is nobody's to swallow.
 *
 * Reflection hands back everything a called method threw wrapped in an
 * `InvocationTargetException`, so without unwrapping, this would be
 * caught and logged as an ordinary reflective mishap.
 */
object FakeFatalController {
    @JvmStatic
    fun setViewDefaultUpdateMode(view: View?, mode: FakeUpdateMode?): Unit =
        throw OutOfMemoryError("not a vendor problem")

    @JvmStatic
    fun setWebViewContrastOptimize(view: WebView?, enable: Boolean): Unit = Unit
}
