package com.chmouel.liseur.ui.widget

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Glance routes taps inside lazy widget lists through its trampoline
 * activities, which the manifest disables. A lazy list would leave its
 * taps silently doing nothing.
 */
class NoLazyWidgetListsTest {
    @Test
    fun `widgets use no Glance lazy lists while the trampolines are disabled`() {
        val sources = File(System.getProperty("user.dir"), "src/main/kotlin")
        assertTrue("expected Kotlin sources at $sources", sources.isDirectory)

        val offenders = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("androidx.glance.appwidget.lazy") }
            .map { it.relativeTo(sources).path }
            .toList()

        assertTrue(
            "Glance lazy lists need ActionTrampolineActivity and " +
                "InvisibleActionTrampolineActivity; re-enable them in AndroidManifest.xml: $offenders",
            offenders.isEmpty(),
        )
    }
}
