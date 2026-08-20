package com.chmouel.liseur.data.db

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against writing an SQL upsert again.
 *
 * `INSERT ... ON CONFLICT ... DO UPDATE` needs SQLite 3.24, which reached
 * Android in version 10. Liseur still supports Android 8, where the
 * statement is a syntax error — and because Room checks queries at build
 * time against a modern SQLite, nothing complains until someone on an old
 * phone loses their place on every page turn.
 *
 * Room's own `@Upsert` is fine and deliberately not caught here: it is
 * compiled into an insert followed by an update, not into this statement.
 * Anything else wants an update, then an insert if the update matched
 * nothing, with the two inside one `@Transaction`.
 */
class NoUpsertStatementsTest {
    @Test
    fun `no query relies on an upsert older phones cannot run`() {
        val sources = File(System.getProperty("user.dir"), "src/main/kotlin")
        assertTrue("expected Kotlin sources at $sources", sources.isDirectory)

        val offenders = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("ON CONFLICT", ignoreCase = true) }
            .map { it.relativeTo(sources).path }
            .toList()

        assertTrue(
            "SQL upserts fail on Android 9 and older; rewrite as an update " +
                "then an insert in one @Transaction: $offenders",
            offenders.isEmpty(),
        )
    }
}
