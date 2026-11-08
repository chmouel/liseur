# 66. Choosing which format to download from an OPDS catalog

Status: accepted

## Context

An OPDS entry can offer the same book several times. Standard Ebooks
lists a compatible EPUB, an advanced EPUB 3 and a Kobo file, all
readable by Readium; calibre-web lists EPUB and KEPUB once kepubify is
set up. Liseur kept only the first readable link, so the advanced EPUB
was out of reach.

## Decision

The parser keeps every readable link as a `DownloadFormat`, in feed
order, with a link titled "EPUB" first and Kobo files last.
`downloadHref` stays that default, so nothing that only knows one href
changes. The list is stored on the book row only when there is a
choice.

The reader picks a format on the catalog browser's book sheet or on Book
Details. The pick is a `DownloadFormat.key` (media type, link title, and its
order among links sharing both)
stored on the catalog's server row, not on the book: a catalog names its
formats the same way for every book, and the reader who wants advanced
EPUBs wants them for the next book too, including books added in bulk.
`BookDownloadWorker` resolves the key against the book's formats when a
download starts and falls back to the default when the book does not
offer it. Applying it there rather than rewriting `download_href` means
a choice takes effect at once for every book from that catalog, and a
catalog refresh can never undo it.

A book already on the device is not fetched again when the preference
changes. A half-finished download is resumed only from the same
address, so switching formats midway starts the file over.
