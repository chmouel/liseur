package com.chmouel.liseur.domain

import com.chmouel.liseur.companion.ChapterRecap
import com.chmouel.liseur.companion.Recaps
import com.chmouel.liseur.companion.StorySummaries
import com.chmouel.liseur.companion.SummaryDep
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class CompanionBackupTest {
    private fun entry() = JSONObject()
        .put("book_id", "book").put("unit_key", "chapter").put("unit", 0)
        .put("content_sha", "a".repeat(64)).put("server_id", "server").put("model", "model")
        .put("recap_format", Recaps.FORMAT).put("json", ChapterRecap.EMPTY.encode())
        .put("saved_at", 100).put("used_at", 200)

    private fun payload(vararg entries: JSONObject) = JSONObject()
        .put("format", 1).put("application", "liseur").put("recaps", JSONArray(entries.toList())).toString()

    @Test
    fun `invalid recap identities timestamps and field types are rejected`() {
        val invalid = listOf(
            "book_id" to "", "unit_key" to "", "unit" to -1, "unit" to 0.5,
            "unit" to Int.MAX_VALUE.toLong() + 1, "content_sha" to "bad-hash",
            "recap_format" to 0, "json" to "broken", "server_id" to false,
            "model" to "", "saved_at" to -1, "used_at" to "200", "title" to 7,
        )
        for ((key, value) in invalid) {
            assertThrows("$key=$value", Exception::class.java) {
                decodeCompanionBackup(payload(entry().put(key, value)))
            }
        }
        assertThrows(Exception::class.java) { decodeCompanionBackup(payload(entry(), entry())) }
    }

    @Test
    fun `unknown optional fields are ignored and chapter keys may repeat across books`() {
        val decoded = decodeCompanionBackup(payload(
            entry().put("future", true), entry().put("book_id", "other-book"),
        ))
        assertEquals(listOf("book", "other-book"), decoded.recaps.map { it.recap.bookUrl })
        assertEquals(listOf(100L, 100L), decoded.recaps.map { it.recap.savedAt })
        assertTrue(decoded.summaries.isEmpty())
    }

    private fun summary() = JSONObject()
        .put("book_id", "book").put("key", "c".repeat(64)).put("level", StorySummaries.TOP).put("lo", 0).put("hi", 2)
        .put("content_sha", "a".repeat(64)).put("recap_format", Recaps.FORMAT).put("summary_format", StorySummaries.FORMAT)
        .put("deps", StorySummaries.encodeDeps(listOf(SummaryDep(0, "a", true), SummaryDep(2, "c", false))))
        .put("text", "So far.").put("saved_at", 100).put("used_at", 200)

    private fun withSummaries(vararg entries: JSONObject) =
        JSONObject(payload(entry())).put("summaries", JSONArray(entries.toList())).toString()

    @Test
    fun `running summaries decode and invalid ones are rejected`() {
        val decoded = decodeCompanionBackup(withSummaries(summary()))
        assertEquals(listOf("So far."), decoded.summaries.map { it.summary.text })
        val invalid = listOf(
            "key" to "short", "lo" to 3, "level" to -1, "deps" to "[]", "deps" to "broken",
            "deps" to StorySummaries.encodeDeps(listOf(SummaryDep(5, "f", true))),
            "deps" to StorySummaries.encodeDeps(listOf(SummaryDep(2, "c", true), SummaryDep(0, "a", true))),
            "text" to "", "text" to "x".repeat(StorySummaries.TOP_CHARACTERS + 1), "summary_format" to 0,
        )
        for ((key, value) in invalid) {
            assertThrows("$key=$value", Exception::class.java) {
                decodeCompanionBackup(withSummaries(summary().put(key, value)))
            }
        }
        assertThrows(Exception::class.java) { decodeCompanionBackup(withSummaries(summary(), summary())) }
    }
}
