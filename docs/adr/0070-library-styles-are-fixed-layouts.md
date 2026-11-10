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
Each is a fixed layout with at most a few options of its own:
any style can choose between a top card for the current book and a
bottom bar with the current book's cover on the left and Home, Library
and More tabs beside it (the bottom bar is the default on new installs,
and upgrades keep the top card unless changed); Shelves chooses series,
author or reading state for its shelves, a shelf colour (theme accent,
stone, oak, walnut or brass) and a backdrop behind them (plain, wood,
plaster, bricks or a custom image); and List can use compact rows.
Nothing is reorderable.

Only the library screen changes. Every style keeps the same book
actions, badges, download progress, search results, empty states and
e-ink rules. Home is the Classic grid with a larger current book and
strips of recent books, series under way and new books above it, so
filters, the archive and series piles still work there. Shelves and
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
and frame textures are drawn by `hack/shelf-textures` in grayscale and
tinted from the theme or chosen shelf tone at run time, so they follow
light, dark and dynamic colour; the plants keep their natural colours.
E-ink keeps a flat plank and no decoration.

## Consequences

Six DataStore keys (`library_style`, `library_shelf_grouping`,
`library_shelf_backdrop`, `library_shelf_tone`, `library_compact_list`,
`library_bottom_bar`) are backed up with the other settings; a seventh
(`library_shelf_backdrop_uri`) points to the copied custom image in
local app storage. No database change.

Search and the empty and loading states are shared, so they look the
same in every style.

A modular home screen, stats cards or a spine view would need a
new decision.

*Where:* `domain/LibraryLayout.kt`, `ui/library/styles/`,
`ui/library/LibraryScreen.kt`.
