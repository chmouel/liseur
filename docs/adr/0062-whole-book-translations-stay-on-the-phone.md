# 62. Whole-book translations stay on the phone

Status: accepted

## Context

Readers want to translate a downloaded book before losing connectivity.
The page translator already stores answers with keys derived from the
service, model, languages, source sentence and its context. Removing the
page's ahead limit would leave those paid answers subject to cache eviction
and would keep a novel's answers in memory.

## Decision

A separate foreground job walks the EPUB using the page translator's
request preparation. It pins the approved destination and settings, writes
through a strict store, and marks the book as kept before the first request.
Kept books survive cache trimming and global cache clear. Deletion invalidates
the job generation so outstanding requests cannot restore deleted rows.
Up to four sentence requests run concurrently. Request preparation stays in
reading order, duplicate pending keys share a request, and completed answers
are saved without waiting for slower requests. Pause cancels outstanding calls;
it cannot undo processing or billing already performed by the provider.

The reader uses its existing sentence cache. Saved failures are skippable
refusals; successful answers take precedence. The same service, model and
options must be selected to reuse these keys offline.

Whole-book preparation opens from Translation in a book cover's long-press
actions, in the library or a series. The sheet shows saved state first;
preparation and coverage checks require a tap. Progress, review and completion
each have their own view. Translation adds no reader overflow or appearance
control. Selected-text translation still offers translated reading.

Existing liseur-sync cache sharing continues unchanged. The server keeps
its current cache limits; this change makes no server-retention promise.

## Consequences

The app needs no new translation prompts or server protocol. Changing a
model or prompt can cause live requests again, and missing or failed
sentences are not guaranteed translated offline. The background job can
be interrupted by Android and resumes only when the reader asks.
