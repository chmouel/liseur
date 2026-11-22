package com.chmouel.liseur.ui.settings

import com.chmouel.liseur.data.library.SettingsBackupPreview
import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreSummaryTest {

    @Test
    fun `includes only categories present in the backup with accurate operations`() {
        val preview = SettingsBackupPreview(
            settingCount = 49,
            fontCount = 1,
            annotationCount = 59,
            annotationBookCount = 10,
            matchedAnnotationBooks = 0,
            positionCount = 61,
            companionRecapCount = 38,
            includesConnections = false,
            translationBookCount = 0,
            bookCount = 0,
            sessionCount = 363,
            metadataBookCount = 0,
        )

        val entries = buildRestoreSummaryEntries(preview)

        assertEquals(
            listOf(
                RestoreSummaryEntry(RestoreCategory.SETTINGS, RestoreOperation.REPLACE),
                RestoreSummaryEntry(RestoreCategory.FONTS, RestoreOperation.ADD),
                RestoreSummaryEntry(RestoreCategory.MARKS, RestoreOperation.MERGE),
                RestoreSummaryEntry(RestoreCategory.POSITIONS, RestoreOperation.REPLACE),
                RestoreSummaryEntry(RestoreCategory.SESSIONS, RestoreOperation.MERGE),
                RestoreSummaryEntry(RestoreCategory.RECAPS, RestoreOperation.ADD),
            ),
            entries,
        )
        assertEquals(RestoreIntroKind.MIXED, restoreIntroKind(entries))
    }

    @Test
    fun `marks books as add when missing and keep when all are already present`() {
        val withNewBooks = SettingsBackupPreview(
            settingCount = 0,
            fontCount = 0,
            bookCount = 3,
            booksInLibrary = 1,
            booksToImport = 2,
            bookImportBytes = 1024L,
        )
        val allPresent = withNewBooks.copy(booksInLibrary = 3, booksToImport = 0, bookImportBytes = 0L)

        assertEquals(
            listOf(RestoreSummaryEntry(RestoreCategory.BOOKS, RestoreOperation.ADD)),
            buildRestoreSummaryEntries(withNewBooks),
        )
        assertEquals(
            listOf(RestoreSummaryEntry(RestoreCategory.BOOKS, RestoreOperation.KEEP)),
            buildRestoreSummaryEntries(allPresent),
        )
        assertEquals(RestoreIntroKind.PRESERVE_ONLY, restoreIntroKind(buildRestoreSummaryEntries(allPresent)))
    }

    @Test
    fun `detects replace-only backups including accounts and metadata overrides`() {
        val preview = SettingsBackupPreview(
            settingCount = 12,
            fontCount = 0,
            includesConnections = true,
            metadataBookCount = 4,
            positionCount = 2,
        )

        val entries = buildRestoreSummaryEntries(preview)

        assertEquals(
            listOf(
                RestoreSummaryEntry(RestoreCategory.SETTINGS, RestoreOperation.REPLACE),
                RestoreSummaryEntry(RestoreCategory.ACCOUNTS, RestoreOperation.REPLACE),
                RestoreSummaryEntry(RestoreCategory.METADATA, RestoreOperation.REPLACE),
                RestoreSummaryEntry(RestoreCategory.POSITIONS, RestoreOperation.REPLACE),
            ),
            entries,
        )
        assertEquals(RestoreIntroKind.REPLACE_ONLY, restoreIntroKind(entries))
    }
}
