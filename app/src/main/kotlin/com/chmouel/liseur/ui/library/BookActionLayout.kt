package com.chmouel.liseur.ui.library

internal enum class BookAction {
    CancelDownload,
    Download,
    UploadToServer,
    MarkRead,
    MarkUnread,
    ToggleArchive,
    Details,
    Stats,
    Translation,
    Series,
    RemoveCopy,
    RemoveFromLibrary,
    DeleteFile,
    DeleteFromServer,
    ServerDeleteNeedsReconnect,
}

internal enum class BookActionSectionId {
    Reading,
    Book,
}

internal enum class ManageBookSectionId {
    Device,
    Server,
}

internal data class BookActionState(
    val downloading: Boolean = false,
    val downloadedCopy: Boolean = false,
    val onServer: Boolean = false,
    val inLibrary: Boolean = false,
    val finished: Boolean = false,
    val canMarkUnread: Boolean = false,
    val hasTranslation: Boolean = false,
    val canUpload: Boolean = false,
    val canDeleteFromServer: Boolean = false,
    val serverDeleteNeedsReconnect: Boolean = false,
    val browsingServer: Boolean = false,
)

internal data class BookActionSection(
    val id: BookActionSectionId,
    val actions: List<BookAction>,
    val secondaryReadingAction: BookAction? = null,
)

internal data class ManageBookSection(
    val id: ManageBookSectionId,
    val actions: List<BookAction>,
)

internal data class BookActionLayout(
    val primaryAction: BookAction? = null,
    val sections: List<BookActionSection>,
    val manageSections: List<ManageBookSection>,
) {
    val hasManageBook: Boolean
        get() = manageSections.isNotEmpty()
}

internal fun bookActionLayout(state: BookActionState): BookActionLayout {
    val primaryAction =
        when {
            state.downloading -> BookAction.CancelDownload
            state.onServer && !state.downloadedCopy -> BookAction.Download
            else -> null
        }

    val primaryReadingAction = if (state.finished) BookAction.MarkUnread else BookAction.MarkRead
    val secondaryReadingAction =
        BookAction.MarkUnread.takeIf { !state.finished && state.canMarkUnread }

    val reading =
        BookActionSection(
            id = BookActionSectionId.Reading,
            actions = listOf(primaryReadingAction, BookAction.Stats),
            secondaryReadingAction = secondaryReadingAction,
        )

    val bookActions =
        listOfNotNull(
            BookAction.Details,
            BookAction.Translation.takeIf { state.inLibrary || state.hasTranslation },
            BookAction.Series,
            BookAction.ToggleArchive,
            BookAction.UploadToServer.takeIf { state.canUpload },
        )
    val book = BookActionSection(id = BookActionSectionId.Book, actions = bookActions)

    val deviceRemoval =
        listOfNotNull(
            // BookDownloadWorker marks the book downloaded before indexing finishes, so hide
            // remove-copy while downloading is still active
            BookAction.RemoveCopy.takeIf { state.downloadedCopy && !state.downloading },
            BookAction.RemoveFromLibrary.takeIf { state.inLibrary },
            BookAction.DeleteFile.takeIf { !state.onServer },
        )

    val serverRemoval =
        when {
            !state.onServer || state.browsingServer -> emptyList()
            state.canDeleteFromServer -> listOf(BookAction.DeleteFromServer)
            state.serverDeleteNeedsReconnect -> listOf(BookAction.ServerDeleteNeedsReconnect)
            else -> emptyList()
        }

    val manageSections =
        listOf(
            ManageBookSection(ManageBookSectionId.Device, deviceRemoval),
            ManageBookSection(ManageBookSectionId.Server, serverRemoval),
        ).filter { it.actions.isNotEmpty() }

    return BookActionLayout(
        primaryAction = primaryAction,
        sections = listOf(reading, book).filter { it.actions.isNotEmpty() },
        manageSections = manageSections,
    )
}

