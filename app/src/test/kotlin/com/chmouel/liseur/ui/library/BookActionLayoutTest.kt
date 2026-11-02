package com.chmouel.liseur.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookActionLayoutTest {
    private fun allInitialActions(state: BookActionState): List<BookAction> {
        val layout = bookActionLayout(state)
        return listOfNotNull(layout.primaryAction) +
            layout.sections.flatMap { section ->
                section.actions + listOfNotNull(section.secondaryReadingAction)
            }
    }

    private fun allManageActions(state: BookActionState): List<BookAction> =
        bookActionLayout(state).manageSections.flatMap { it.actions }

    private fun allActions(state: BookActionState): List<BookAction> =
        allInitialActions(state) + allManageActions(state)

    private val downloadedFromServer =
        BookActionState(
            downloadedCopy = true,
            onServer = true,
            inLibrary = true,
            canDeleteFromServer = true,
        )

    private val localOnly =
        BookActionState(
            inLibrary = true,
            canUpload = true,
        )

    @Test
    fun `no section or manage group is empty`() {
        val states =
            listOf(
                BookActionState(),
                localOnly,
                downloadedFromServer,
                downloadedFromServer.copy(canDeleteFromServer = false, serverDeleteNeedsReconnect = true),
                BookActionState(onServer = true),
            )
        states.forEach { state ->
            val layout = bookActionLayout(state)
            assertTrue("state: $state", layout.sections.none { it.actions.isEmpty() })
            assertTrue("state: $state", layout.manageSections.none { it.actions.isEmpty() })
        }
    }

    @Test
    fun `initial sheet has named Reading and Book sections`() {
        val layout = bookActionLayout(localOnly)
        assertEquals(
            listOf(BookActionSectionId.Reading, BookActionSectionId.Book),
            layout.sections.map { it.id },
        )
    }

    @Test
    fun `a local book offers upload in Book section and never download`() {
        val layout = bookActionLayout(localOnly)
        assertNull(layout.primaryAction)
        val bookSection = layout.sections.first { it.id == BookActionSectionId.Book }
        assertTrue(BookAction.UploadToServer in bookSection.actions)
        assertFalse(BookAction.Download in allActions(localOnly))
        assertFalse(BookAction.CancelDownload in allActions(localOnly))
        assertTrue(BookAction.DeleteFile in allManageActions(localOnly))
    }

    @Test
    fun `a downloading book offers cancel as primary action`() {
        val state = downloadedFromServer.copy(downloading = true, downloadedCopy = false)
        val layout = bookActionLayout(state)
        assertEquals(BookAction.CancelDownload, layout.primaryAction)
        assertFalse(BookAction.Download in allActions(state))
    }

    @Test
    fun `a download being indexed offers nothing to remove`() {
        // BookDownloadWorker marks the book downloaded before indexing it,
        // so both flags are true for a moment
        val state = downloadedFromServer.copy(downloading = true)
        val layout = bookActionLayout(state)
        assertEquals(BookAction.CancelDownload, layout.primaryAction)
        assertFalse(BookAction.RemoveCopy in allManageActions(state))
        assertFalse(BookAction.Download in allActions(state))
    }

    @Test
    fun `a book on the server without a copy offers prominent download`() {
        val state = BookActionState(onServer = true)
        val layout = bookActionLayout(state)
        assertEquals(BookAction.Download, layout.primaryAction)
        assertFalse(BookAction.RemoveCopy in allManageActions(state))
        assertFalse(BookAction.DeleteFile in allManageActions(state))
        assertFalse(layout.hasManageBook)
    }

    @Test
    fun `manage book keeps gentle removal above destructive delete and separates server delete`() {
        val localManage = bookActionLayout(localOnly).manageSections
        assertEquals(1, localManage.size)
        assertEquals(ManageBookSectionId.Device, localManage.single().id)
        assertEquals(
            listOf(BookAction.RemoveFromLibrary, BookAction.DeleteFile),
            localManage.single().actions,
        )

        val serverManage = bookActionLayout(downloadedFromServer).manageSections
        assertEquals(
            listOf(ManageBookSectionId.Device, ManageBookSectionId.Server),
            serverManage.map { it.id },
        )
        assertEquals(
            listOf(BookAction.RemoveCopy, BookAction.RemoveFromLibrary),
            serverManage.first { it.id == ManageBookSectionId.Device }.actions,
        )
        assertEquals(
            listOf(BookAction.DeleteFromServer),
            serverManage.first { it.id == ManageBookSectionId.Server }.actions,
        )
    }

    @Test
    fun `a server book being browsed offers nothing on the server`() {
        val state = downloadedFromServer.copy(browsingServer = true)
        val shown = allManageActions(state)
        assertFalse(BookAction.DeleteFromServer in shown)
        assertFalse(BookAction.ServerDeleteNeedsReconnect in shown)
    }

    @Test
    fun `the reconnect note replaces the server delete it explains`() {
        val state =
            downloadedFromServer.copy(canDeleteFromServer = false, serverDeleteNeedsReconnect = true)
        val serverSection =
            bookActionLayout(state).manageSections.first { it.id == ManageBookSectionId.Server }
        assertEquals(listOf(BookAction.ServerDeleteNeedsReconnect), serverSection.actions)
    }

    @Test
    fun `reading section shows one primary status action and keeps mark unread discoverable when in progress`() {
        val unread =
            bookActionLayout(BookActionState()).sections.first { it.id == BookActionSectionId.Reading }
        assertEquals(listOf(BookAction.MarkRead, BookAction.Stats), unread.actions)
        assertNull(unread.secondaryReadingAction)

        val inProgress =
            bookActionLayout(BookActionState(canMarkUnread = true))
                .sections
                .first { it.id == BookActionSectionId.Reading }
        assertEquals(listOf(BookAction.MarkRead, BookAction.Stats), inProgress.actions)
        assertEquals(BookAction.MarkUnread, inProgress.secondaryReadingAction)

        val finished =
            bookActionLayout(BookActionState(finished = true, canMarkUnread = true))
                .sections
                .first { it.id == BookActionSectionId.Reading }
        assertEquals(listOf(BookAction.MarkUnread, BookAction.Stats), finished.actions)
        assertNull(finished.secondaryReadingAction)
    }

    @Test
    fun `translation shows for a file on this device or a translation already made`() {
        assertFalse(BookAction.Translation in allInitialActions(BookActionState(onServer = true)))
        assertTrue(BookAction.Translation in allInitialActions(BookActionState(hasTranslation = true)))
        assertTrue(BookAction.Translation in allInitialActions(localOnly))
    }

    @Test
    fun `destructive and removal actions live only in manage book`() {
        val initial = allInitialActions(downloadedFromServer)
        assertTrue(initial.none { it in manageOnlyActions })
        assertTrue(allManageActions(downloadedFromServer).all { it in manageOnlyActions })
    }

    private companion object {
        val manageOnlyActions =
            setOf(
                BookAction.RemoveCopy,
                BookAction.RemoveFromLibrary,
                BookAction.DeleteFile,
                BookAction.DeleteFromServer,
                BookAction.ServerDeleteNeedsReconnect,
            )
    }
}

