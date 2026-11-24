package com.chmouel.liseur.ui.settings

/**
 * Whether the Book server page shows the library-sync backend (Google Drive) controls.
 *
 * A connected main server normally hides them; [switchRequested] is the reader's explicit
 * "switch to Drive" tap, which lets the backend's own confirmation run the disconnect.
 */
internal fun showsLibrarySyncSection(
    hasServer: Boolean,
    editingAddress: Boolean,
    supported: Boolean,
    selected: Boolean,
    switchRequested: Boolean,
): Boolean = supported && !editingAddress && selected && (!hasServer || switchRequested)

/** Selection after the server, backend activity or switch request changed. */
internal fun nextLibrarySyncSelected(
    current: Boolean,
    hasServer: Boolean,
    switchRequested: Boolean,
    backendActive: Boolean,
    supported: Boolean,
): Boolean = when {
    !supported -> false
    hasServer -> current && switchRequested
    backendActive -> true
    else -> current
}
