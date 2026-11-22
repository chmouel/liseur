# 76. Hardcover catalogue details update

Status: accepted

## Context

Books imported from loose EPUB files or uncurated servers often have
missing or inconsistent covers, titles, authors, descriptions or series
numbers. Once a book is matched to a Hardcover edition
([ADR 0075](0075-hardcover-reading-tracker.md)), Hardcover's public
catalogue holds those details for that edition and its book.

## Decision

In the Google Play build, the Hardcover section of Book details offers
"Update from Hardcover" whenever a book is matched to an edition, even
if the book is excluded from reading sync.

Nothing is applied without asking. Tapping the action fetches the
matched edition and its book from `api.hardcover.app` with the account
token, compares the fields Liseur can store locally as overrides (cover,
title, author, description, and series name and position), and presents
a review sheet with a checkbox per differing field. The cover and any
field whose local value is blank are ticked by default; fields that
would overwrite an existing local value start unticked. Values exceeding
the wire byte limits for metadata overrides are omitted.

Confirmed fields are saved as ordinary user overrides: text and cover
through `BookMetadataOverrideRepository`, and series through
`fileBookInSeries`. Cover images are previews in memory via
`HardcoverImages.loader` and, only when the cover checkbox is applied,
downloaded from `assets.hardcover.app` over the bare `HardcoverImages`
client without the token or redirects and re-encoded as the user cover
override. Nothing from the reader's personal Hardcover library (shelf
status, dates, rating or review) is pulled; that remains a separate
future change.

## Consequences

- Applied details behave like any manual edit: "Edit details" can reset
  them to the file or catalogue values, and `liseur-sync` carries them
  to other devices.
- Every write checks that the book is still the generation that was
  reviewed, so a file replaced meanwhile keeps its own details.
- `hardcover.db` (version 2) remembers the imported cover URL and the
  digest of the override it became, so an unchanged cover is not offered
  again. `liseur.db` and the sync protocol are unchanged.
- Ratings and reviews from the reader's Hardcover library sync in
  [ADR 0077](0077-hardcover-rating-review-sync.md); the rest of that
  library state is not pulled.
- Hardcover keeps one description per book, often in English for a
  translated edition. A description whose common words read as another
  language than the book's current description (or its declared
  language) is not offered.
- The Hardcover cover is downloaded when the sheet opens, once, for both
  the preview and the save. It is not offered when its difference hash
  is within a few bits of the book's current cover file.
