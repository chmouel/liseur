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
OpenAI-compatible servers (except TranslateGemma, or with a reply token
cap set) and Gemini get up to 40 consecutive sentences of one chapter per
request, numbered one per line, with up to four requests running at once;
other translators get one sentence per request. A reply must answer every
number in order; otherwise the batch is asked again in halves, down to
single sentences, whose refusals become failure markers. Each answer is
saved under its own sentence key, so the reader still finds it; a batched
answer may read slightly differently from a live single-sentence one.
Request preparation stays in reading order, duplicate pending keys share a
request, and completed answers are saved without waiting for slower
requests. A 429, a 408 or 5xx, or a lost connection is asked again with
growing waits, or the wait the server asks for, within 30 minutes of the
first failure; with no network the job waits for one, a LAN included,
without using up a try. A server asking to wait longer pauses the job
instead of being asked early. A retried request may be billed twice. The
cost estimate groups sentences the way the job does. Pause cancels outstanding calls;
it cannot undo processing or billing already performed by the provider.

The reader uses its existing sentence cache. Saved failures are skippable
refusals; successful answers take precedence. The same service, model and
options must be selected to reuse these keys offline.

Whole-book preparation opens from Translation in a book cover's long-press
actions, in the library or a series, and from a long press on the reader's
⋮ "Translate into <language>" item when the chosen service can prepare it
or the book already has one. The sheet shows saved state first;
preparation and coverage checks require a tap. Progress, review and completion
each have their own view. Translation adds no reader appearance control.
Selected-text translation still offers translated reading.

Settings ZIP backups carry kept answers and failures. Restored coverage is
unverified until checked against the destination EPUB. Restored books can
be exported again without regenerating their translations.

Existing liseur-sync cache sharing continues unchanged. The server keeps
its current cache limits; this change makes no server-retention promise.

## Consequences

The app needs a batch prompt but no new server protocol. Changing a
model or prompt can cause live requests again, and missing or failed
sentences are not guaranteed translated offline. The background job can
be interrupted by Android. A job paused by Android, its time limit, a
lost connection, a 429 or a 408/5xx resumes when the app next comes to
the foreground; a pause the reader chose, a 402 or other refusal, changed
settings or a storage failure waits for the reader. The consent says so.
Resume skips the full check when the book file's sha256
(`kept_translations.fingerprint`), service and languages still match the
approved job; a changed target language pauses it as changed settings.
A resume only starts while the record it was asked from is still paused
in the same generation, so deleting the translation meanwhile ends it
without recreating anything.

Backup archives can be much larger and contain readable translated book
text. Their password encrypts connections only. Export reports books left
out by its size cap rather than keeping an incomplete book entry.
