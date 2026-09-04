# 23. Where Liseur's sync stands against Kindle Whispersync

Status: accepted

## Context

The bar most readers hold a reading app to is the Kindle's: close a
book on one device, open it on another, and be on the same page. Amazon
calls it Whispersync. After the run of work that made a refused
liseur-sync batch recover on its own and a reconnect keep its device
(liseur `78e375013..cd2ca6d14`; liseur-sync `99b37b5`, `01eb90f`,
`f1bcf4b`, `46add1f`), the question was whether that bar is met, and on
which connections, so that the answer written into the README and the
server screen is an honest one.

Liseur syncs against five kinds of server through one client-side
machine: every persisted page turn is pushed live and coalesced per book
(`LatestPositionSync`), leaving the reader queues a background backstop
(`PositionSyncWorker.pushBook`), an hourly job reconciles every book on
any network, and opening or resuming a book asks whether another device
has read further (`ReaderViewModel.maybeOfferCatchUp`). What differs
between the kinds is what goes on the wire and how much of the place it
carries. `domain/ReadingStateMerge.kt` decides every conflict, once.

## The comparison

Kindle is the reference column. *kosync* is KOReader's protocol, paired
alongside a Grimmory or custom OPDS server (ADR-0014). *Grimmory / OPDS*
is what those two carry on their own, which is nothing.

| Behaviour | Kindle | liseur-sync | calibre-web (Kobo) | Komga | kosync | Grimmory / OPDS |
|---|---|---|---|---|---|---|
| What travels | last page, furthest page | full Readium locator and progression; the server keeps every op | percentage | full Readium locator, in Komga's spelling (`KomgaLocator`) | percentage | nothing |
| Reopens at the exact place | yes | yes | the right page, near enough | yes | the right page, near enough | — |
| Push while reading | on sleep or close | every persisted page turn, coalesced | same | same | same | — |
| Push on leaving the book | yes | background backstop, expedited on API 31+ | same | same | same | — |
| Background refresh | server push, and on wake | hourly, any network | same | same | same | — |
| On open or resume | modal: go to the furthest page? | a pill offering the further place, one tap back, a decline remembered | same | same | same | — |
| Conflict rule | furthest page wins | three-way merge against the last agreed baseline | same | same | same | — |
| Finished / unread | yes | yes, including a status set by hand | yes (Kobo `ReadingStatus`) | yes, including "mark read" in Komga's own UI | no; the percentage implies it | — |
| Highlights, notes, bookmarks | yes | yes (ADR-0011) | no | no | no | no |
| Reading statistics across devices | totals only | yes (ADR-0021) | no | no | no | no |
| Offline, then back | yes | append-only log, derived ids; a retry is byte-identical and answered `duplicate` | idempotent write of the current value; the sync token moves in the transaction that stores what it covers | idempotent write of the current value | idempotent write | — |
| Learning what moved elsewhere | server push | cursor over `GET /v1/changes` | Kobo sync token | catalog walk, `readProgress` | one `GET` per candidate book; there is no list | — |
| How a book is named | ASIN | SHA-256, KOReader partial-MD5, `ta:` title/author with a confirmation on a low match, `409` to merge | calibre uuid | Komga book id | partial-MD5 | — |
| Which books | bought, or sent to Kindle | every book, including local books; upload and adoption are optional | downloaded from that calibre-web | downloaded from that Komga | downloaded server books, since a hash needs the file | — |
| Who else sees the place | Kindle devices and apps | liseur-desktop, the web reader, KOReader through `/adapter/kosync` | Kobo devices, the calibre-web reader | Komga's reader and other Komga clients | KOReader | — |
| One device across reconnects | yes | yes (`ServerSetup.reconnect`; liseur-sync ADR-0033) | not applicable | not applicable | not applicable | — |
| A book open on two devices at once | last writer wins | the pull is held while the page is on screen (`OpenBooks`) and offered on the next resume | same | same | same | — |
| Opening a book is not reading | yes | yes (`OpeningRestoration`) | same | same | same | — |

"Same" means the row is the shared machinery above, not something the
provider does.

## Decision

**On liseur-sync, the claim holds.** Every position behaviour a Kindle
reader can see is there, and three are better: the merge tells a
deliberate reread apart from the other device having moved, rather than
taking the furthest page; the offer is a pill rather than a question to
answer before reading; and a sideloaded file is a first-class book without requiring upload. On
top of that come highlights, notes, bookmarks and statistics, which
Whispersync also has, and a book only on this phone reaching the server,
which it does not.

Two things are not there and are said plainly. There is no server push:
a change made elsewhere is learnt on the next open, resume, or hourly
run. Kindle behaves the same way for positions in practice, so no reader
notices, but it is a difference. And the guarantee is only as good as
every client: the web reader and liseur-desktop must push on the same
occasions and merge by the same rule, and nothing yet proves end to end
that two of them converge. That proof, not another feature, is what
would justify the sentence in the README.

**On Komga, the claim holds for positions only.** The exact place and
the finished status travel; annotations and statistics do not.

**calibre-web and kosync carry a percentage, and the claim is not made
for them.** A percentage reopens the right page on the same edition
with the same typography, and near it otherwise. That is "your place
follows you", which the server screen already says, and not "the exact
place". Neither protocol has a field for more.

**Grimmory and a plain OPDS catalog carry nothing** until a kosync
server is paired, after which the kosync column applies.

Two small changes came out of writing this down, both provider-neutral:

- The background backstop is queued when the reader pauses, not when
  the activity stops. A process killed while paused never reaches
  `onStop`, and pause is when the settled place is known.
- On API 31 and later that backstop is expedited work, so Doze holds it
  for seconds rather than minutes. Below 31 expedited work needs a
  foreground notification, which a few dozen bytes of position do not
  justify, so those phones keep the plain job.

## Consequences

The server screen's wording per kind (`server_sync_exact`,
`server_sync_progression`) is already the honest version of this table
and stays as it is. A README sentence comparing Liseur to the Kindle
waits on an end-to-end test that closes a book in one client and opens
it in another against a real liseur-sync.
