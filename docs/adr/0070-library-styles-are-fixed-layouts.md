# 70. Library styles are fixed layouts

Status: accepted

## Context

The library had one look: a cover grid with the book being read on
top. KOReader readers get very different libraries from community
plugins. SimpleUI turns the start screen into a dashboard, Bookshelf
stands covers on drawn shelves, and Project: Title lists books with
their details and a progress bar scaled to the book's length. People
coming from KOReader asked for something similar.

SimpleUI lets the reader build the dashboard from modules. Doing that
here would mean a layout editor, a stored module list to migrate and
back up, and every combination to keep working on phones, tablets and
e-ink.

## Decision

Four styles, picked in Settings › Appearance or from the button beside
the sort above the books: Classic (the grid, still the default), Home,
Shelves and List.
Each is a fixed layout with at most one option of its own: Shelves
chooses series, author or reading state for its shelves, and List can
use compact rows. Nothing is reorderable.

Only the library screen changes. Every style keeps the same book
actions, badges, download progress, search results, empty states and
e-ink rules. Home is the Classic grid with a larger current book and
strips of recent books, series under way and new books above it, so
filters, the archive and series piles still work there. Shelves and
List lay out the filtered, sorted library and keep the filter row on
top. The grouping, strips and track lengths are computed in
`domain/LibraryLayout.kt`, outside Compose.

The layouts borrow ideas from the plugins and none of their code or
artwork. Shelves are drawn in the theme's surface colours, with no wood
texture.

## Consequences

Three DataStore keys (`library_style`, `library_shelf_grouping`,
`library_compact_list`) are backed up with the other settings. No
database change.

Search and the empty and loading states are shared, so they look the
same in every style.

A modular home screen, stats cards or a spine view would need a
new decision.

*Where:* `domain/LibraryLayout.kt`, `ui/library/styles/`,
`ui/library/LibraryScreen.kt`.
