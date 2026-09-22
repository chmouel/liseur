# BookOrbit position synchronization

BookOrbit position synchronization remains disabled. `ServerKind.BOOKORBIT`
must stay at `syncAbility = NONE` and `canSync = false` until the complete
round trip and durable reconciliation work is proven.

## Phase 0 progress transport

`BookOrbitProgressClient.read` fetches only the selected EPUB file in a
captured `BookOrbitCfiContext`. It checks the connection and binding before
and after the GET. The parser requires a numeric percentage in 0..100. It
distinguishes BookOrbit's unopened default (zero, null position fields, no
`updatedAt`) from a saved zero (`updatedAt` present). Malformed JSON, a
partial default, an invalid percentage, or a non-JSON response fails the
call. `lastReadAt`, then `textUpdatedAt`, then `updatedAt` is display context
only; none is a local merge timestamp.

`BookOrbitProgressMutationTransport` is disconnected from sync. A POST
returns `ReadBackRequired` for a successful response, `Rejected` for an
explicit 401/403, or `Uncertain` for other responses and network failures.
It does not automatically retry connection failures, redirects, or
unauthorized POSTs. A 401 invalidates the refused access token so a later
attempt can renew it, without replaying this POST. A later phase must
compare a fresh selected-file GET against the exact sent payload before
acknowledging or retrying. Never interpret `Uncertain` as rejection. The
transport does not persist outgoing bytes or make writes on its own.

The `progress-*.json` test bodies are **synthetic, source-shaped examples**,
not authenticated responses: `progress-unopened.json` follows the literal
default in BookOrbit v3.0.0 `book.controller.ts`; the saved row follows the
`readingProgress` row returned by `book.repository.ts`; the multi-file
example follows `getBookProgress` in `book.service.ts`. The status parser
preserves all eight names in `user-book-status.constants.ts` and unknown
future values. MockWebServer tests exercise selected-file identity,
malformed replies, a lost POST response, and no automatic POST replay.

Source review of v3.0.0 `book.service.ts` (`resolveTextPosition` and
`saveProgress`) and `book.repository.ts` (`upsertProgress`) shows that a
text POST with only `percentage` sets `cfi` and `pageNumber` to null.
It also clears `positionSeconds`, both media-overlay fields, all four
Kobo fields, and `koreaderProgress` when omitted. `percentage` is replaced
by the sent value. Narration percentage and timestamp are preserved on
an existing row because `narrationColumns` is empty for a text write;
`textUpdatedAt` and `updatedAt` advance. A text write may also update read
status, Kobo state, and sibling EPUB progress through server-side hooks.
This is source-derived evidence, **not a test against a running BookOrbit
server**. Before enabling writes, capture sanitized authenticated v3.0.0
responses for each fixture shape and run a disposable-book text POST with
preexisting CFI, KOReader, Kobo, narration, and overlay fields; verify each
read-back and downstream effect. The Phase 0 server replacement-test gate
is still open. The current environment has no running Docker daemon or
PostgreSQL, and its Node 22 is below BookOrbit v3.0.0's Node 24 requirement.
Do not substitute a production server for the disposable-book test.

## Phase 1 acceptance

Phase 1 provides a parser and a guarded retention API. It does not run
background progress requests or pass foreign CFIs to Readium. Close this
phase against the checks below; another open-ended review is not an
acceptance criterion.

| Requirement | Evidence |
|-------------|----------|
| Point/range parsing and raw retention | `BookOrbitCfiTest`, plus captured Foliate output in `BookOrbitAcceptanceTest` |
| EPUB 2/3 package paths and non-linear items | `BookOrbitEpubPackageTest`; actual package and itemref element indices in `BookOrbitAcceptanceTest` |
| Selected-file info request | `BookOrbitEpubInfoClient`, request-path and malformed-response tests in `BookOrbitCfiRepositoryTest` |
| Wrong account, epoch, file or binding rejection | `BookOrbitCfiRepositoryTest`; checks and writes share a Room transaction |
| Restart-safe foreign CFI | Schema 55 `book_orbit_cfi`; the file-backed database reopen test preserves unsupported raw input |
| Account/book cleanup | Retention rows follow binding deletion by foreign key; disconnect clears retained CFIs while keeping downloaded-file bindings |
| Android XML behavior | Production APK parser smoke on disposable API 26 emulator, 2026-09-22 |

The `/info` test body follows BookOrbit v3.0.0's published DTO; it is not
a captured authenticated response. No BookOrbit credentials were available
for this acceptance run. Phase 0's progress reader and mutation transport
have tests, but its live replacement-field test is still open.

On 2026-09-22, `make check` passed, the Chromium recapture matched all four
committed CFI fixtures, and the API 26 smoke passed. One final review of the
five implementation gates (retention, ownership, lifecycle, metadata
cross-check and evidence) found no significant blockers. Further review
changes should start with a reproducible failure against those gates.

## Implementation

The app now has pure JVM helpers for the identity half of the CFI bridge:

- `BookOrbitCfi` parses point and range CFIs following EPUB CFI 1.1:
  indirections, element/text steps, id assertions, a single terminating
  character offset, and the text location assertion with side bias inside
  the offset's brackets (`/1:18[before,after;s=b]`, the form foliate-js
  writes). Values are split on unescaped `,` and `;` before `^` escapes are
  removed. Range endpoints may be bare offsets (`,:1,:4`); an empty start
  path means the parent location. The indirection into a content document
  may end the parent (foliate's
  `/6/4!,/4/..,/6/..`) or open each endpoint (`/6/4,!/4/..,!/4/..`); both
  mean it applies to the parent's last step. `serialize()`
  returns the exact original string, so a retained CFI is never rewritten.
- Temporal and spatial offsets are refused as unsupported. Malformed syntax
  (an offset mid-path, `!` without a following step or offset, an offset on
  a range parent, an unknown side bias, unbalanced brackets) and duplicate
  assertion parameters are refused as malformed. Unknown unique assertion
  parameters are kept, as the specification asks readers to ignore them
  rather than fail.
- `BookOrbitForeignCfi` ties a raw CFI to the BookOrbit account, book, file
  and local file-binding revision. An unparseable CFI stays intact with its
  reason; it is not silently converted to a percentage.
- `BookOrbitCfiRepository.capture` snapshots the connection and persisted
  binding. `retain` rechecks both inside the write transaction; `load`
  reparses the stored raw string and refuses a changed edition. The stored
  row contains source data only. No position or status acknowledgement
  changes.
- `BookOrbitEpubPackage.parse(File)` opens the EPUB through the zip central
  directory (what Readium trusts) and decompresses only
  `META-INF/container.xml` and the OPF, each capped at 4 MiB. It chooses
  the first OCF-namespace rootfile with the OPF media type, reads only
  OPF-namespace manifest and spine elements including non-linear itemrefs,
  percent-decodes
  hrefs into archive entry names (refusing escapes that are not valid
  UTF-8), leaves remote manifest items out, and refuses duplicate ids,
  absolute hrefs and paths that escape the archive.
- Jsoup checks for document types without fetching or expanding entities;
  bare declarations such as `<!DOCTYPE package>` are accepted, but external
  identifiers and internal subsets are refused. A namespace-aware DOM parser
  then requires well-formed XML. Its hardening features are best effort
  because Android rejects some of them, while an explicit entity resolver
  blocks external references. All XML entry points, including the byte-array
  methods, enforce the 4 MiB limit before building either parser's tree.
- `BookOrbitEpubInfoClient.read` sends a context-bound GET with an explicit
  `fileId`, on `Dispatchers.IO`, and rechecks ownership after the response.
  `BookOrbitEpubInfo.agreesWith` compares the package path and full ordered
  spine. A match is a structural cross-check, not proof of identical book
  bytes or of an exact DOM location.

The focused JVM tests cover the spec forms above, foliate's `[;s=a]` side
bias, escaped Unicode assertions, malformed and unsupported syntax, EPUB 2
(prefixed) and EPUB 3 packages, multiple renditions, non-linear spine
items, invalid spine references, oversized package documents, and external
entities.

## Fixture provenance and reproduction

`app/src/test/resources/bookorbit/OPS/` contains synthetic, project-authored
package and chapter documents. `foliate-cfis.json` records output from
BookOrbit's unmodified `client/public/assets/foliate/epubcfi.js`, pinned to
v3.0.0 commit `6be648b48a9cc376cfeff8953ebf22123583f7eb`. Chromium headless
generated the corpus on 2026-09-22 using real DOM ranges. It includes a
UTF-16 offset after a surrogate pair, two ranges and an escaped element ID.
This is output from BookOrbit's Foliate library, not a live-server or
Liseur-reader round trip.

To reproduce it, place `tests/bookorbit/capture.html`, the pinned upstream
`epubcfi.js`, and the fixture's `package.opf` and `one.xhtml` in the same
temporary directory. Serve that directory on localhost and open
`capture.html` in Chromium. The page prints the JSON corpus. Do not vendor
the upstream JavaScript into the Android app.

The info DTO and request contract were checked against the same upstream
commit's `packages/types/src/epub.ts` and
`server/src/modules/reader/epub/epub.controller.ts`. Its `containerPath`
names the OPF, and its spine `href`s are archive-root paths.

The Android smoke test is `tests/bookorbit/ParserSmoke.java`. Compile it
with `javac --release 8`, convert the class with Android build-tools `d8
--min-api 26`, then copy its dex jar and the debug APK to an emulator's
`/data/local/tmp`. Run `app_process /system/bin ParserSmoke` with both files
in `CLASSPATH`. This executes production parser code without installing
over the reader. The API 26 run passed nine checks, including UTF-16 XML,
bare DOCTYPE acceptance, forbidden DTDs, malformed XML and namespaces.

## Phase 2 handoff and caveats

Phase 2 now has an offline, read-only resolution boundary.
`BookOrbitCfiResource.locate` matches a parsed CFI's spine and itemref
indices and optional ID assertions against the local OPF, returning a
resource href and both range endpoint paths. It rejects another spine,
itemref, or nested indirection; it does not claim DOM accuracy.
`BookOrbitCfiDom.resolve` checks each endpoint against a supplied document:
element parity and ID assertions, grouped adjacent UTF-16 text nodes,
offset and text assertions, endpoint order, and an explicitly supplied
predicate for flattening reader-injected wrappers. Side bias is kept with
each resolved position. `BookOrbitCfiDom.capture` builds point/range CFIs
from supplied text nodes, adjusted for split UTF-16 text and optional
reader wrappers; it returns a CFI only if both endpoints resolve back to
the same nodes and offsets. Captured Foliate output and synthetic
altered-DOM tests cover these checks. These helpers are not wired to the
reader or sync.
The caller still needs to prove that the document is the selected file,
and must mark resolution failed if any DOM step cannot be verified.

Complete Phase 0's live-server fixture and replacement test before using
progress reads for reconciliation or the mutation transport for writes.
In particular, a 200/201/204 POST only authorizes read-back, never a
position or status acknowledgement. Recheck the selected binding after
read-back, and retain the exact bytes of any outgoing request durably before
attempting a mutation. A narration write has different CFI and timestamp
behavior from a text write; do not generalize the text replacement rule.

The next phase must resolve parsed CFIs only against the EPUB selected by the
persisted `BookOrbitBinding`; check account key, file id and binding revision
before accepting any result. A successful parse is not evidence that the CFI
belongs to that EPUB or to a live DOM.

Do not use `BookOrbitForeignCfi.parsed` as a `Locator`, reader restore target,
or POST payload yet. DOM resolution must account for Readium's injected
wrappers, split text nodes and browser UTF-16 offsets. It must preserve both
range endpoints even when restoring from the start endpoint. A failed exact
resolution may retain the raw CFI and offer only an explicitly approximate
fallback; it must never write that fallback back as exact.

Phase 2 must add the live BookOrbit web-reader and Liseur round trip on a
disposable book. The captured library-output corpus is a starting point,
not that acceptance test. Update this record with findings, fixture
provenance and resolver caveats before handing work to the Phase 3 agent.

Parser caveats for the resolver:

- Step index parity is not enforced; the resolver must check that an even
  step lands on an element and an odd one between elements.
- A step's `s=` parameter is kept in `Step.parameters`, not `Offset.sideBias`.
  The resolver must honor it for element-boundary locations too.
- `!` followed directly by an offset (`/6/4!:3`) parses, as the grammar
  allows, but has no text meaning and should be treated as unresolved.
- A range parent ending in `!` is an open path, and the resolver must join
  it with each endpoint before walking; the same holds for endpoints that
  begin with `!`. An empty start path denotes the parent location. Do not
  resolve `Range.parent` on its own when it ends in `!`.
- Remote manifest items are omitted, so a spine that names one fails to
  parse rather than pointing at a resource that is not in the archive.
- Use `spineStep` and each spine item's `step`; the package path is not
  necessarily `/6`, and non-linear itemrefs still occupy CFI indices.
- A syntactically parsed CFI is still DOM-unverified even when
  `unresolvedReason` is null. That field describes a parser failure only.
- `BookOrbitCfiDom` takes a DOM supplied by its caller. It does not parse
  the selected EPUB itself or recognize Readium's injected wrappers.
  Outgoing capture has not been connected to the current reader viewport.
  Flatten only known reader-owned wrappers; never flatten an element from
  the EPUB. Reader wiring and live two-way acceptance are still required.
- Retained foreign CFIs are separate from Phase 3's agreement and outgoing
  bytes. Do not use the retention table as a position acknowledgement or
  outgoing-request queue.
