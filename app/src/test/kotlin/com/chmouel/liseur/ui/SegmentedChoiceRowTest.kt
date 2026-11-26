package com.chmouel.liseur.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Segmented choice rows are built through [SegmentedChoiceRow] and
 * [Segment], never from the Material pieces directly.
 *
 * The pieces are the bug: a row that measures each segment on its own
 * comes out ragged as soon as one label wraps, which is what Czech's
 * "Bez animace" does to the page turn row (#547), and nothing in the
 * layout fails at compile time or in English. Keeping the direct calls
 * out of the source is the only guard that does not need a device, a
 * font scale and a translation to catch it.
 */
class SegmentedChoiceRowTest {

    private val helper = "SegmentedChoiceRow.kt"

    /** A Material piece that builds a row or a segment on its own. */
    private val raw = listOf(
        Regex("""(?<!\w)SingleChoiceSegmentedButtonRow\s*\("""),
        Regex("""(?<!\w)MultiChoiceSegmentedButtonRow\s*\("""),
        Regex("""(?<!\w)SegmentedButton\s*\("""),
        Regex("""(?<!\w)SegmentedButtonDefaults\."""),
        Regex("""^import androidx\.compose\.material3\.(SingleChoiceSegmentedButtonRow|MultiChoiceSegmentedButtonRow|SegmentedButton)\b"""),
    )

    @Test
    fun `segmented rows are built through the shared helpers`() {
        // Gradle runs from app/; an IDE may run from the repository root.
        val base = sequenceOf(File("src"), File("app/src")).firstOrNull { it.isDirectory }
        assertTrue("no sources found from ${File(".").absolutePath}", base != null)

        val files = base!!.listFiles { f -> f.isDirectory && !f.name.startsWith("test") && !f.name.startsWith("androidTest") }
            .orEmpty()
            .flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(
            "cannot see the sources from ${File(".").absolutePath}",
            files.any { it.name == helper },
        )

        val offenders = files
            .filter { !it.path.endsWith("com/chmouel/liseur/ui/$helper") }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") }
                    .filter { (_, line) -> raw.any { it.containsMatchIn(line) } }
                    .map { (index, line) -> "${file.path}:${index + 1}: ${line.trim()}" }
            }

        assertTrue(
            "Build segmented rows with SegmentedChoiceRow and Segment, not with the " +
                "Material pieces, so a wrapped label cannot make one segment taller than " +
                "the others:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
