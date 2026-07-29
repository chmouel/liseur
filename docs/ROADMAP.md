# Roadmap

What is worth building next, and what has been considered and turned
down. Nothing here blocks a release; the release checklist lives in
[`DEVELOPER.md`](../DEVELOPER.md#releasing).

Each entry says what the problem is and where in the tree it lives, so
that picking one up does not start with a search.

## Before the first release

### Say why the account vanished

A restored backup carries the database and the settings across, but not
the credentials: they are sealed with a key that never leaves the
original device's keystore. `forgetUnreadableAccount()` notices this on
launch and drops the account row, which is correct but silent — the user
just finds themselves signed out with no explanation.

Tell them once, on the calibre-web screen, that credentials cannot travel
between devices and the server has to be signed into again.

*Where:* `data/calibre/CalibreAccountRepository.kt`,
`ui/settings/CalibreAccountScreen.kt`.

## Soon after

### Search the library

The library can be ordered four ways but not searched. Anyone with more
than a shelf-full has to scroll. Title and author search over the grid,
sitting alongside the existing sort control.

*Where:* `BookDao` in `data/db/Book.kt`, `ui/library/LibraryViewModel.kt`,
`ui/library/LibraryScreen.kt`.

### Test the sync and download flows

The riskiest code in the app has the least coverage: reconciling a Kobo
sync feed, resuming an interrupted download, and mapping a sync outcome
onto a WorkManager result. Fake DAOs cover the first and last; the
network paths want MockWebServer.

*Where:* `data/calibre/KoboSyncRepository.kt`,
`data/calibre/BookDownloadRepository.kt`, `sync/PositionSyncWorker.kt`.

### Show what sync is actually doing

Catalog and sync state exist in the database but barely surface. When a
sync fails, the only sign is a snackbar that has already gone. Show the
last catalog refresh, the last position sync, and why the last one
failed.

*Where:* `ui/settings/SettingsScreen.kt`,
`ui/settings/CalibreAccountScreen.kt`.

### Admit the search cap

In-book search stops at `MAX_SEARCH_HITS = 500` and says nothing, so a
search through a dictionary or an omnibus looks complete when it is not.
Say so at the end of the results list.

*Where:* `reader/ReaderViewModel.kt`.

## Later, and worth designing first

### Archive instead of delete

Deleting is currently the only way to tidy the library, and it is final.
A hidden or archived state would sit between "on the shelf" and "gone".
Needs a decision about what archiving means for a downloaded file and for
the server copy before any of it is written.

*Where:* `data/db/Book.kt`, `ui/library/`.

### Carry highlights between devices

Highlights, notes and bookmarks never leave the device. Either sync them
through calibre-web, or build a library-wide export and import so they
can be moved by hand. The per-book Markdown export is not enough.

*Where:* `data/db/BookAnnotation.kt`, `data/calibre/`.

### Per-book typography

Reader preferences are global. A novel and a technical reference do not
want the same measure, the same size or the same theme.

*Where:* `data/settings/ReaderPreferencesRepository.kt`, `reader/`.

### Decide what a swapped file keeps

Replacing a file at the same path keeps its reading position and its
annotations. That is right for a book re-downloaded at a better quality,
and wrong for a genuinely different book, whose highlights then point at
text that is not there. Keeping the position but dropping annotations
whose locators no longer resolve is probably the answer.

*Where:* `data/library/LocalLibraryRepository.kt`.

### Test the schema migrations

Ten schemas are exported under `app/schemas/` and none are exercised.
Doing it properly needs an `androidTest` source set, `room-testing` and
an emulator in CI, which is why it has not happened yet.

*Where:* `data/db/LiseurDatabase.kt`.

## Considered and turned down

Recorded so they do not get raised again without new evidence.

- **Splitting up `ReaderViewModel`.** It is around 500 lines and every
  part of it belongs to the same reading session. Splitting it now would
  be refactoring for its own sake. Revisit if a second reader surface or
  a second format arrives.
- **Interfaces in front of the repositories, or several Gradle modules.**
  The manual composition root in `AppContainer.kt` is a deliberate
  choice: no Hilt, no Koin, no indirection that only exists for tests.
  The pure logic that needs testing already lives in `domain/`.
- **Bundling an offline dictionary.** Several megabytes of APK and a
  licensing problem for F-Droid, to replace a handoff that already works
  with whichever dictionary the user has installed.
- **Counting local books in the storage figure.** The settings screen
  reports what Liseur has downloaded and therefore what it can free.
  Books in the user's own folder are not Liseur's to count or to reclaim.
