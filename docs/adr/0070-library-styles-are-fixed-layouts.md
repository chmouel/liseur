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

Four styles, picked in Settings › Library or from the button beside
the sort above the books, both opening the same sheet: Classic (the
grid, still the default), Home, Shelves and List.
Each is a fixed layout with at most a few options of its own:
Shelves chooses series, author or reading state for its shelves, a
shelf colour (theme
accent, stone, oak, walnut or brass) and a backdrop behind them (plain,
wood, plaster, bricks, the Marigold, Willow Bough or Daisy wallpapers by
William Morris, or a custom image); Classic can fit one more
column with one-line titles; and List can use compact rows.
Nothing is reorderable.

A separate choice, Settings › Appearance › App layout, sets the scope
rather than the look. Detailed, the default, is the five-tab app:
Home, Library, Series, Stats and Settings in a floating bar along the
bottom. Simple is the library alone: it opens on the books, with the
statistics, translation, sync and Settings in its top bar's overflow
menu, and series reachable as a grouping instead of a tab. The scope
does not change what the reader can do, only how many doors the app
has.

Home is a tab rather than a style in both scopes. With Detailed the
app opens
on Home or Library as the reader chooses, and the style sheet, titled
Library view, picks only how the Library tab looks (Classic, Shelves or
List). A style saved as Home from a version where it was a style draws
the catalogue as Classic; the stored value is left alone.

Only the library screen changes. Every style keeps the same book
actions, badges, download progress, search results, empty states and
e-ink rules. Home is a short reading desk of its own, not a prefix of
the grid: the current book with its chapter and time left (or a book
to start), four shelf tiles (reading, to read, series, finished) that
open the Library filtered to them, series under way with the next
volume, a pick-up-next strip, recently added books and a way into the
whole Library. It is computed from the whole shelf, so the Library's
filters never change it. The Library tab shows one-tap reading-state
chips with their counts above the filter row; they write the same
filters as the Filter menu. Shelves and
List lay out the filtered, sorted library and keep the filter row on
top. The grouping, strips and track lengths are computed in
`domain/LibraryLayout.kt`, outside Compose.

The layouts borrow ideas from the plugins and none of their code or
artwork. Shelves stand on a bookcase: a wall (bricks by default, or
wood panelling, plaster, plain surface or a photo from the device),
ledges with a dentil band, and empty places that take either a finished
book's cover hung as a framed picture or a potted plant. Some shelves
also get a small pot at one end or a plant hanging under them, chosen
from the shelf's key so nothing moves while scrolling. The wall, ledge
and frame textures are drawn by `hack/shelf-textures` in grayscale; the
Morris wallpapers are cut by `hack/shelf-patterns` from public-domain
scans in The Met's Open Access collection into seamless grayscale tiles.
All are tinted from the theme or chosen shelf tone at run time, so they follow
light, dark and dynamic colour; the plants keep their natural colours.
E-ink keeps a flat plank and no decoration.

## Consequences

Eight DataStore keys (`library_style`, `library_shelf_grouping`,
`library_shelf_backdrop`, `library_shelf_tone`, `library_compact_list`,
`library_grid_compact`, `library_scope`, `library_start_home`) are
backed up with the other settings; a ninth
(`library_shelf_backdrop_uri`) points to the copied custom image in
local app storage. No database change. `library_scope` reads a stored
`library_bottom_bar` boolean from an older version as Detailed or
Simple, and backups of that version are converted the same way.

Search and the empty and loading states are shared, so they look the
same in every style.

A modular home screen, stats cards or a spine view would need a
new decision.

*Where:* `domain/LibraryLayout.kt`, `ui/library/styles/`,
`ui/library/LibraryScreen.kt`.
