# 71. Backups carry books and reading sessions

Status: accepted

## Context

The settings backup restored positions, marks, recaps, fonts and
settings, but not the reading sessions behind the statistics screen,
and not the EPUB files themselves. Restoring on a new device gave an
empty statistics screen and a library whose books had to be found and
imported by hand, while the existing "export offline books" only wrote
a folder nothing could read back.

Books are large. A readable library easily runs to gigabytes, so the
obvious shape, a ZIP holding every entry staged in cache, would need
twice the space and would fail even when the device already has every
book the archive carries.

## Decision

Archive format 7 adds two optional payloads: `sessions.json` and
`books.json` with one `books/<sha256>.epub` entry per distinct book.

- Export hashes the ticked books before the document picker opens, then
  streams their bytes straight into the chosen document. The cache ZIP
  lists the book entries in its manifest without holding their bytes,
  so a cancelled save costs nothing and the manifest never describes
  files the archive lacks.
- Inspect stages only the books the library does not already have, with
  a cache-headroom check, and only hashes the rest. Restore moves a
  staged file into `files/books/<sha256>.epub` and indexes it, so the
  archive is read once and no second copy of the library is written.
- Books match by URL, then by an existing content-addressed copy, then
  by unambiguous title and author. Nothing is imported twice and no
  stored `books.url` is rewritten: positions, marks, sessions, recaps
  and translations resolve archived URLs through an alias map.
- Reading sessions travel without ids, checkpoints or upload state.
  Restore dedupes on book and start inside the archive and against
  stored rows, keeps sessions whose book is missing under the archived
  URL, and records the rows with a new `imported_at` column.
- Limits keep one archive under 4 GiB, so it needs no ZIP64 and fits a
  FAT32 card: 512 MiB per book, 3 GiB of books, 2000 files. Anything
  larger is refused as too large rather than silently trimmed.

## Consequences

Restored sessions count locally and appear in statistics, but
liseur-sync never uploads them and never offers them as candidates.
A session id derives from the device and the stored local row id, so
letting a restored row be uploaded would count the same sitting twice
on the server. In server-only statistics they show up only if the
source device uploaded them.

Remote-origin books come back as local copies. Reconnecting the same
server later leaves its catalog and that copy as two rows; the reader
can remove either.

A multi-gigabyte inspect reads the whole archive once, which is slow
from a cloud provider but cannot be avoided without trusting a second
pass over the grant. A book changed between preparing and saving fails
the save instead of writing an entry whose digest does not match its
manifest.
