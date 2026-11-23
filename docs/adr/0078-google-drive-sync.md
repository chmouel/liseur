# 78. Google Drive library sync (Play build)

Status: proposed

## Context

Readers without a Liseur server want their library state to follow them
between devices. The Play build can use the Drive `appDataFolder`
(`drive.appdata`) for this; the FOSS build must not depend on Google
libraries. This ADR is Phase 0: it fixes the design before code lands on
branch `feat/google-drive-sync`. The full domain matrix and wire schema
go in `docs/DRIVE_SYNC.md`.

Constraints found in the code:

- `DeviceIdentity.id` is a UUID in the `device` DataStore. That DataStore
  and `liseur.db` are both in cloud backup and device transfer
  (`res/xml/data_extraction_rules.xml`), so a restored tablet inherits
  the phone's device id and the same `reading_sessions.id` values.
  `SessionUploads.sessionIdFor(deviceKey, localId)` would then give two
  different sittings one id.
- Every reconciliation table is already keyed per peer: `sync_peer_state`,
  `work_alias`, `annotation_sync`, `evaluation_sync`,
  `metadata_override_sync`, `vocabulary_word_sync`,
  `vocabulary_review_sync`, `furthest_position` and `peak_delivery`.
- The automatic server is the `remote_server` row with `id = 1`; ids
  above 1 are browse-only.
- `RemoteAccountRepository.disconnect()` deletes `id = 1` and calls
  `BookRemoval.deleteRemoteNotDownloaded()`, which drops rows matching
  `remote_uuid IS NOT NULL AND browse_server_id IS NULL AND local_uri IS
  NULL` via `BookRemoval.forget` (which also deletes those books'
  sessions). A previously downloaded book whose download was removed
  (`local_uri IS NULL`) still holds reading sessions and notes, so
  Server → Drive must use a history-preserving disconnect path rather
  than calling `deleteRemoteNotDownloaded()` blindly.
- Each automatic position partner is a `PeerPositionSync` inside
  `CompositePositionSync`.
- Flavor features are an interface in `src/main`, a same-named factory
  per flavor and construction in `AppContainer` (`ReadAloudFeatureFactory`,
  `ReadingTracker`).
- `hardcover.db` is an existing Play-only Room database with
  `exportSchema = true`.
- Readium keeps only the unique `dc:identifier`, so an ISBN is available
  only when that identifier is an ISBN.
- `ServerKind` is referenced in 36 files under `src/main/kotlin`.
- `LiseurDatabase` is at version 84.
- `tmp/` is gitignored (`.gitignore:1`); `tmp/google-drive.md` stays the
  local working progress tracker while committed design specs live in
  this ADR and `docs/DRIVE_SYNC.md`.

## Decision

### Model

Each device writes only its own files in `appDataFolder`: one manifest,
immutable deltas and snapshot parts. No file is written by two devices,
so nothing relies on Drive compare-and-swap. Drive v3 has no conditional
content update, which rules out a shared `library.json`.

Ordering uses a hybrid logical clock (HLC),
`"%013d-%04x-%s".format(wallMs, counter, deviceId)`, compared
lexicographically, persisted in `drive_account`; on receipt
`local = max(local, seen)`. The `counter` field is fixed width: on overflow
(`0xffff -> 0x10000`) the clock advances logical `wallMs` by 1 and resets
`counter` to 0 instead of widening the field, so lexicographic ordering stays
monotonic.

Identifiers:

- Account peer key: `drive:<permissionId>`, used as `peer_id` in every
  per-peer table of `liseur.db` and as partition key in `drive_sync.db`.
  No schema change to existing `liseur.db` sync tables is needed.
- `deviceId`: lowercase UUIDv4 generated once per install in
  `drive_install`. It is never taken from `DeviceIdentity`.
- `fp` (version fingerprint, lowercase hex SHA-256 of canonical JSON:
  sorted keys, UTF-8, no whitespace, canonical numbers as specified in
  `docs/DRIVE_SYNC.md`):
  - For `op: "put"`: `contentFp = sha256({"op":"put","body":<body>})`.
    Recomputable directly from the committed `liseur.db` row.
  - For `op: "del"`: `delFp = sha256({"op":"del","d":<d>,"k":<k>,"hlc":<hlc>})`.
    Including `hlc` guarantees a later delete after a kept resolution
    never collides with an earlier tombstone in `anc`.
  - For `op: "resolve"`:
    `resolveFp = sha256({"op":"resolve","outcome":<outcome>,"resolvedFps":<sorted>,"hlc":<hlc>,"body":<body>})`.
    Distinct from both conflicting fps even when the reader keeps a
    version verbatim, so the resolution causally supersedes both sides.
- `anc`: the record's superseded version fps, newest first, capped at 64.
  Used for ordinary causal supersession and staleness checks.
- `resRefs`: sorted, deduplicated array of winning resolution fps
  (`resolveFp`) from which this record causally descends, carried
  forward across every subsequent edit, delete, or resolution for
  `(d, k)` **outside** the 64-entry `anc` cap (persisted in
  `drive_record_state.res_refs_json` and emitted on wire records;
  excluded from `contentFp`). Serves as the durable causal proof for
  resolution-bridged supersession after compaction.
- Durable resolution facts (`drive_resolution` in `drive_sync.db` and
  the dedicated `resolutions` section in snapshots): uncapped records of
  `(d, k, resolutionFp, resolvedFps, outcome, hlc)` preserved outside
  the 64-entry `anc` cap so a returning offline device still closes open
  conflicts cleanly after >64 successor edits and multiple compactions
  when the incoming record proves descent via `resRefs`.

### Two-database commit order and revision-to-fingerprint adapter

On every remote apply or outgoing batch ACK, the `liseur.db` transaction
(data plus per-peer ack) commits first, then the `drive_sync.db`
transaction (causal state, resolutions, outbox state, and cursor).
Applies are idempotent against `liseur.db`, so a crash between the two
commits replays cleanly on restart.

Existing `liseur.db` per-peer tables fall into two shapes:

- **Fingerprint-native tables (`annotation_sync`, `vocabulary_word_sync`):**
  store `acked_fingerprint` atomically with the data row. On crash
  recovery, recompute `contentFp` from the committed `liseur.db` row (or
  `"DELETED"` when the row is absent while its sync row remains) and
  reconcile `drive_record_state` against `acked_fingerprint`.
- **Revision-based tables (`evaluation_sync`, `metadata_override_sync`):**
  these tables store `acked_revision` (`Long`), `sent_revision`
  (`Long?`), and `sent_json` (`String?`) rather than an `acked_fp`
  column. Drive uses an exact **revision-to-fingerprint adapter** so the
  atomic data-plus-ack invariant holds without adding columns to either
  table:
  1. On **remote apply** (Transaction 1 in `liseur.db`): write the
     applied value to `book_evaluation` (or `books` +
     `metadata_override_revision`) at `rev_applied`, and in the **same
     transaction** upsert `evaluation_sync` / `metadata_override_sync`
     for `peer_id = drive:<permissionId>` with
     `acked_revision = rev_applied`, `sent_revision = rev_applied`, and
     `sent_json = {"vfp":"<R.fp>","cfp":"<contentFp>","rev":<rev_applied>}`.
  2. On **outgoing batch ACK** (Transaction 1 in `liseur.db`, after
     Drive confirms the delta `fileId`): advance `acked_revision =
     target_revision`, `sent_revision = target_revision`, and
     `sent_json = {"vfp":"<batchFp>","cfp":"<contentFp>","rev":<target_revision>}`.
     If the reader edited the row to `row.revision > target_revision`
     while upload was in flight, `row.owed(sync)` stays `true` so the
     newer edit is picked up in the next batch with `batchFp` in its
     `anc`.
  3. On **crash recovery** (runs before diffing or pulling whenever
     `drive_record_state.last_synced_revision < sync.acked_revision` or
     the `drive_record_state` row is missing after a crash between
     Transaction 1 and Transaction 2):
     - If `row.revision == sync.acked_revision` (no local edit occurred
       after the crash): **recompute `contentFp` directly from the
       committed `liseur.db` row**, pair it with `vfp` from
       `sync.sent_json` (falling back to the recomputed `contentFp`),
       and repair `drive_record_state` (`published_fp = vfp`,
       `content_fp = contentFp`, `last_synced_revision =
       sync.acked_revision`, adding `vfp` to `ancestors_json`). When the
       un-advanced batch replays, it hits Agreement and advances the
       cursor without rewriting `liseur.db` (and if the replayed record
       is `op: "resolve"`, the mandatory incoming-resolution pre-step
       still persists `drive_resolution` and closes any covered `OPEN`
       conflict before advancing the cursor).
     - If `row.revision > sync.acked_revision` (the reader edited the
       field again locally after restart before recovery ran): seed
       `drive_record_state`'s causal ancestry with `sync.sent_json.vfp`
       (the exact pre-edit committed baseline at `acked_revision`), keep
       the current row dirty (`L = contentFp(row)`), and publish the new
       edit with `sync.sent_json.vfp` in its `anc`. Replaying the
       already-applied batch sees `R.fp ∈ anc(L)` (Stale) and never
       overwrites the reader's new edit (while still running the
       mandatory `op: "resolve"` persistence/settlement pre-step if the
       replayed record is a resolution).

For `books` fields without a per-peer ack table in `liseur.db` (series
overrides and catalog `archived_at`), `drive_record_state` holds
`published_fp` and `content_fp`; remote applies are idempotent because
replaying the batch after a crash recomputes `contentFp(book) ==
contentFp(R.body)` (Agreement).

### Conflict rule for mutable records

Outgoing **delivery state** and incoming **causal supersession** are
strictly separated:

- **Delivery state** (`published_fp` in `drive_record_state`, plus the
  per-peer `liseur.db` acks) answers only *"does this Drive account need
  an upload from me?"* Completing an upload to this device's own stream
  advances delivery state (`published_fp := L`) and **never** qualifies
  `L` for silent fast-forward when another device's batch arrives.
- **Causal state** (`L` = current local version fp, `version_op(L)` =
  `"put"` | `"del"` | `"resolve"`, `anc(L)` = known causal ancestors of
  `L`, `res_refs(L)` = uncapped winning resolution fps from which `L`
  causally descends, `(origin_device(L), origin_seq(L))` when `L` was
  applied unmodified from another device, and durable `drive_resolution`
  facts) decides conflict detection.

Let `L` be the local version fp (`none` if the record has never existed
locally; a `put` `contentFp`, `delFp`, or `resolveFp` otherwise), and
`R` be an incoming record `(R.op, R.fp, R.anc, R.resRefs, R.hlc,
R.deviceId, R.seq)`.

**Mandatory resolution & `resRefs`-bridge settlement pre-step (runs BEFORE
Cases 0–5, including `Agreement` and `Stale` replay paths, and BEFORE
advancing `drive_cursor`):**
1. **When `R.op == "resolve"`:** ALWAYS idempotently persist
   `(d, k, R.fp, R.resolvedFps, R.outcome, R.hlc, R.deviceId)` in
   `drive_resolution`, settle/close any `OPEN` (or `RESOLVING`) conflict in
   `drive_conflict` for `(d, k)` whose fps are covered by `R.resolvedFps`
   (marking it `RESOLVED` with `resolution_fp = winningResolutionFp`, the
   highest `(hlc, fp)` among overlapping resolutions in `drive_resolution`),
   and collect `R.resRefs` (plus `R.fp` if `R` is the winning resolution)
   as candidate resolution refs.
2. **When any incoming record `R` (`put`, `del`, or `resolve`) carries
   proven resolution descent in `R.resRefs`:** for every winning
   `res ∈ drive_resolution` for `(d, k)` with `res.resolutionFp ∈ R.resRefs`,
   idempotently settle/close any `OPEN` (or `RESOLVING`) conflict in
   `drive_conflict` whose fps are covered by `res.resolvedFps` (marking it
   `RESOLVED` with `resolution_fp = res.resolutionFp`) and collect
   `res.resolutionFp` as a candidate resolution ref. This guarantees a crash between
   `liseur.db` and `drive_sync.db` while applying a compacted successor
   `put`/`del` still closes covered conflicts on `Agreement` or `Stale`
   replay before advancing `drive_cursor`.
3. Candidate refs join `res_refs(L)` only when the case below makes `L`
   adopt or descend from `R` (Unseen, Agreement, applied resolution, causal
   or bridged supersession, Stale by `R.fp ∈ anc(L)`). A record that opens
   a conflict, or is Stale only because a resolution retired it, leaves
   `res_refs(L)` unchanged, so a later local edit never advertises descent
   from a resolution it did not adopt.

Then evaluate in order:

| Case | Condition | Result |
|---|---|---|
| 0. Unseen | `L == none` | Apply `R` silently; set `L := R.fp`, `version_op := R.op`, `published_fp := R.fp`, `anc(L) := R.anc`, `res_refs(L) := R.resRefs ∪ ({R.fp} if R.op == "resolve" else ∅)`. |
| 1. Agreement | `R.fp == L`, OR both `L` and `R` are deletions (`del`, or a `del` paired with a `resolve` whose `outcome` is `"delete"` — two **distinct** `op: "resolve"` records never agree here and are routed to Case 2 first), OR (`R.op == "resolve" && R.outcome == "put"` and committed `liseur.db` row already matches `contentFp(R.body)` with matching per-peer ack after crash recovery) | No data conflict: merge `anc(L) := anc(L) ∪ R.anc` and `res_refs(L) := res_refs(L) ∪ R.resRefs`; for concurrent deletes keep deleted and adopt the higher-HLC `delFp` (or `R.fp` if `R.op == "resolve"`); for resolved-body replay agreement adopt `L := R.fp`, `version_op := "resolve"`, and add `R.fp` to `res_refs(L)`; set `published_fp := L`. (Any incoming `op: "resolve"` side effects were already completed by the mandatory pre-step before cursor advancement.) |
| 2. Incoming resolution | `R.op == "resolve"` | Run the resolution rule below (the mandatory pre-step has already persisted `drive_resolution` and closed covered `OPEN` conflicts). |
| 3. Causal supersession | (No `OPEN` conflict on `(d, k)` and `L` is not retired by any `drive_resolution`, AND (`L ∈ R.anc` OR `L` was applied unmodified from `R.deviceId` at `origin_seq(L) < R.seq`)), OR (`∃` winning `res ∈ drive_resolution` for `(d, k)` with `L ∈ res.resolvedFps` **AND `res.resolutionFp ∈ R.resRefs`** — never merely `R.hlc >= res.hlc`) | **Fast-forward silently:** apply `R` (including a remote `del` on an untouched copy), close any `OPEN` conflict covered by `res.resolvedFps` on resolution-bridged supersession, set `L := R.fp`, `version_op := R.op`, `published_fp := R.fp`, merge `anc(L) := {L} ∪ anc(L) ∪ R.anc` and `res_refs(L) := res_refs(L) ∪ R.resRefs`. |
| 4. Stale | `R.fp ∈ anc(L)` OR `∃ res ∈ drive_resolution` with `R.fp ∈ res.resolvedFps` | Ignore `R`'s body (already superseded or retired by a completed resolution; any incoming `op: "resolve"` side effects were already persisted by the mandatory pre-step). |
| 5. Concurrent divergence | Otherwise | **Conflict:** `EDIT_EDIT` if both `L` and `R` are live puts; `DELETE_EDIT` if one is a live put and the other is a delete. |

**Resolution rule (`op: "resolve"`):**

- Both keep-body and delete resolutions use `op: "resolve"` with an
  explicit `outcome` (`"put"` with non-null `body`, or `"delete"` with
  `body: null`), `resolvedFps` (sorted array of the conflicting fps
  closed by this choice), `resRefs` (inherited winning resolution fps),
  and `hlc`:
  - `EDIT_EDIT`: both versions are preserved until resolved; the local
    row stays in `liseur.db`, and the remote body is stored in
    `drive_conflict.remote_json`.
  - `DELETE_EDIT`: the reader chooses **Keep edited note**
    (`op: "resolve", outcome: "put", body: <edited>`) or **Delete note**
    (`op: "resolve", outcome: "delete", body: null`).
  - Authoring or applying a winning resolution records `resolveFp` in
    `drive_record_state.res_refs_json`, which every subsequent `put`,
    `del`, or `resolve` carries forward in its wire `resRefs` outside
    the 64-entry `anc` cap.
- When an incoming `op: "resolve"` reaches Case 2 (after the mandatory
  pre-step has persisted `drive_resolution` and settled covered `OPEN`
  conflicts):
  - If `R.fp ∈ anc(L)`: Stale; ignore body (side effects already
    persisted).
  - **Competing resolutions (two devices resolved the same conflict
    offline, e.g., keep vs. delete or keep-A vs. keep-B):** silent
    `(hlc, fp)` arbitration applies **ONLY** when **both** the **current**
    local version `L` (`version_op(L) == "resolve"`, matching a
    `drive_resolution` row `L_res` with `L_res.resolutionFp == L`) AND
    incoming `R` (`R.op == "resolve"`) are themselves competing
    resolutions with **equivalent `resolvedFps` sets**
    (`set(L_res.resolvedFps) == set(R.resolvedFps)`, i.e. both close
    exactly the same conflicting fps) and neither is in the other's
    `anc`. Compare
    `(R.hlc, R.fp)` vs. `(L_res.hlc, L_res.resolutionFp)`
    lexicographically: **the higher HLC wins silently without prompting
    the reader again.** If `R` wins, apply `R.outcome`, set `L := R.fp`,
    `version_op := "resolve"`, `published_fp := R.fp`, add `R.fp` (and
    `R.resRefs`) to `res_refs(L)`, and update the settled
    `resolution_fp` to `R.fp`; if local wins, keep local and absorb
    `R.fp` into `anc(L)`.
  - **Partially overlapping `resolvedFps` sets** (intersection non-empty
    but sets not equivalent): never arbitrate the independently chosen
    branches silently. The fps covered by both sets may be applied, every
    branch behind an fps present in only one set is preserved, and a
    conflict (`EDIT_EDIT` if either uncovered outcome is `"put"`,
    `DELETE_EDIT` if both are `"delete"`) is opened for the uncovered fps
    so an independently chosen edit is never silently discarded.
  - **Subsequent edit after local resolution (`version_op(L) !=
    "resolve"`):** if local already resolved the conflict as `resA` and
    then moved on to a subsequent edit `fNew` (so `L = fNew`, not
    `op: "resolve"`), **do NOT** run silent competing-resolution
    arbitration against the historical `resA`. Fall through to the
    normal causal/conflict checks below so `fNew` is never silently
    overwritten.
  - Else if `L ∈ R.resolvedFps` or an open local conflict's fps were
    covered by `R.resolvedFps` (and `L` has not moved on to a newer edit
    outside `R.resolvedFps`): apply `R.outcome` silently (`put` writes
    `R.body`; `delete` deletes the local row while retaining its
    per-peer sync row), set `L := R.fp`, `version_op := "resolve"`,
    `published_fp := R.fp`, merge `anc` and `res_refs(L) := res_refs(L)
    ∪ R.resRefs ∪ {R.fp}`.
  - Else if `L ∈ R.anc` (with no uncovered concurrent edit): apply
    `R.outcome` silently and update `L`, `version_op`, `anc`, and
    `res_refs`.
  - Else if proven resolution descent carries `R` past local: `∃` winning
    `res ∈ drive_resolution` for `(d, k)` with `L ∈ res.resolvedFps`
    **AND `res.resolutionFp ∈ R.resRefs`** (the same durable bridge Case 3
    requires — never merely `R.hlc >= res.hlc`). Close any `OPEN` conflict
    covered by `res.resolvedFps`, apply `R.outcome` silently, and set
    `L := R.fp`, `version_op := "resolve"`, `published_fp := R.fp`, merge
    `anc` and `res_refs(L) := res_refs(L) ∪ R.resRefs ∪ {R.fp}`. This
    check lives inside the incoming-resolution rule and runs BEFORE
    falling through to conflict, so a `resolve` that provably descends
    from the winning resolution never opens a spurious conflict.
  - Else: local has an independent or subsequent edit (e.g., `fNew`) not
    covered by `R.resolvedFps` (and `L ∉ R.anc`): open a new conflict
    (`EDIT_EDIT` if `R.outcome == "put"`, `DELETE_EDIT` if
    `R.outcome == "delete"`) preserving both.

Append-only sets (sessions, vocabulary reviews) dedupe by event id; a
review `del` (undo) is final. Vocabulary words and occurrences merge
automatically with `VocabularyMerge.mergeWordMetadata` and
`mergeOccurrencePair`; live SRS schedule fields (`dueAt`, `reviewedAt`,
`streak`, `intervalMinutes`, `repetitions`) are never sent and are
always derived locally via `VocabularyMerge.reconcileSchedule(addedAt,
mergedBaseline, liveReviews)`, while the optional legacy `baseline`
(`VocabularyWord.baselineOrNull()`) is synced and merged via
`VocabularyMerge.mergeBaseline` so pre-event-log schedules converge
identically. A word `del` is silent when the receiver is untouched; a
concurrent edit or review wins silently and is republished. Because
vocabulary reviews are append-only and outside `anc(L)`, a word `del`
records the causal review boundary as an `observed` vector (the sorted
`syncId`s of the reviews its author had seen, or the snapshot provenance
`seq` of a restored one) and the receiver checks for uncovered live local
reviews before cascading any delete: an uncovered review makes the delete
a concurrent review, so add-wins keeps the word, keeps its reviews, and
republishes. Reading
progress reuses `reconcileReadingState`, and `SyncDecision.Conflict`
goes through the existing position-choice UI. Series conflicts are
grouped by `seriesKey` and resolved per shelf, written as per-book
`op: "resolve"` records. Metadata override and evaluation conflicts
prompt per field. `archived_at` auto-resolves to the higher HLC.

### Synced domains

Edition is `book_fingerprint.sha256`. Synced domains, each with wire key
`k`: library catalog and editions (`workKey`), metadata overrides and
custom covers (`workKey/field`, fields from `MetadataOverrideFields`),
series membership and order (`workKey`), reading progress and status
(`workKey/deviceId`), furthest position and resets
(`peak/workKey/editionSha/deviceId`, `reset/workKey/resetId`),
annotations (`annotations.id`), evaluations (`workKey/field`),
vocabulary words (`<len(lang)>:<lang>:<normalized>`) and occurrences
(`<wordKey>/<canonicalOccurrenceKey>`), vocabulary reviews (`syncId`),
reading sessions (`remote_session_id`), and work aliases
(`merge:<fromWorkKey>`, `reject:<min(a,b)>|<max(a,b)>`).

- Catalog rows for books with no local file use `download_state=REMOTE`,
  url `drive:<workKey>` and `remote_uuid NULL`. They must never set
  `remote_uuid`, or a later `disconnect()` would delete them.
  `hidden_at` stays local.
- Catalog `del` is published only by **Delete from synced library**. The
  receiver removes `drive:` rows through `BookRemoval` and keeps
  annotations. A book with a local file is unlinked from Drive, not
  removed. A Drive-side EPUB delete only clears `driveFileId`.
- Annotation delete: the `annotations` row is gone but its
  `annotation_sync` row remains, so a `del` with `anc` is published. Book
  removal is not a delete.
- Furthest position: peaks keep the max per slot
  (`FurthestPositionDao.observe`); a reset `{floors:{deviceId:seq}}` wins
  over peaks with `seq ≤ floor[device]`. This needs a new DAO query
  deleting `furthest_position` rows for a peer, work and origin with
  `seq <= :floor`. Resets are kept forever.
- Reading progress applies the locator only when `editionSha` equals the
  local sha; otherwise it applies progression only. "Mark unread" is a
  status act (`unread_reset_revision`).
- Vocabulary word key is `"<len(lang)>:<lang>:<normalized>"`; occurrence
  key is the word key, `/`, and
  `VocabularyMerge.canonicalOccurrenceKey(workKey, locationKey)` computed
  at publish time. Review ids for legacy null `syncId` are
  `nameUUIDFromBytes("vocab-review|v1|lang|normalized|reviewedAt|rating")`.
  With Drive active there is no `id = 1` row, so new words get
  `ownerAccount = ''`; Drive syncs the visible set, including words owned
  by retired liseur-sync accounts, merged by `(language, normalized)`.
- Sessions: Migration 84→85 adds
  `reading_sessions.remote_session_id TEXT NULL` (with a `UNIQUE` index)
  and `origin_device_id TEXT NULL`. `remote_session_id` is generated
  **once if null** as
  `nameUUIDFromBytes("liseur-drive-session|v1|workKey|startedAt|endedAt|durationMs|startProg|endProg")`
  (using canonical number formatting so restored devices agree), and
  never cleared once set. Pulled Drive sessions are stored with
  `origin_device_id = <authorDeviceId>` (non-null) and `imported_at =
  NULL`. `awaitingUpload` and `awaitingUploadTo` gain `AND
  origin_device_id IS NULL`, so `liseur-sync` never uploads Drive-pulled
  rows, and Drive never republishes pulled sessions. Eligible local
  sessions for a Drive account `permissionId` are selected where
  `ended_at IS NOT NULL AND imported_at IS NULL AND origin_device_id IS
  NULL AND start_progression IS NOT NULL AND end_progression IS NOT
  NULL` **and** no delivery acknowledgement exists for
  `(permissionId, remote_session_id)` in `drive_sync.db`
  (`drive_session_ack`) — **never** by `remote_session_id IS NULL`. This
  ensures switching to a second Google account or restoring `liseur.db`
  without `drive_sync.db` still seeds all local sessions cleanly.
- Work aliases: merge aliases use `k = "merge:<fromWorkKey>"` with body
  `{to, updatedAt}` where `to < fromWorkKey` lexicographically, so
  chains reach a fixpoint and cannot cycle. Rejected fuzzy matches use
  `k = "reject:<min(a,b)>|<max(a,b)>"` as a `put` that suppresses the
  prompt.

Excluded: `series_extra` (a cache keyed by the server's series id),
`book_metadata(source='catalog')`, `remote_stats_*`, `bookorbit_*`,
`kosync_peer`, `upload_refusal`, `starter_catalog_progress` (server
state), `book_typography`, `book_screen`, `book_reading_mode`,
`book_ai_preferences` (per-device, ADR 0041), translation caches and
`translation_glossary`.

### `workKey`

For books with a local file, `workKey` is computed once
`book_fingerprint` exists and `books.file_metadata_checked = 1`. First
match wins:

1. `isbn:<isbn13>` from `WorkIdentifiers.dcFrom(books.work_id, …)` or the
   `book_metadata(file).identifiers` entry, normalised with the Play-only
   `hardcover/Isbn.from`.
2. `dc:<sha256hex(trim.lowercase(id))>` for any other usable
   `dc:identifier`, excluding `USELESS_IDENTIFIERS`.
3. `sha:<edition sha256>`.

**Portable identity path for preserved fileless books (Server → Drive
switch):** Because `BookRemoval.downloadRemoved()` deletes
`book_fingerprint` and `book_metadata(source='file')` and resets
`file_metadata_checked = 0` (and never-downloaded stubs with reader
overrides never had a local file), preserved fileless stubs cannot wait
for a local fingerprint. **Before** main-server disconnect calls
`forgetSyncPeer(server)` (which deletes the server's `work_alias` rows),
Drive captures retained identity evidence for every preserved fileless
stub in priority order and upserts its Drive `work_alias`
(`peer_id = drive:<permissionId>`, `confidence = 'high'`,
`confirmed = 1`, `seeded = 0`):

1. `isbn:<isbn13>` from `WorkIdentifiers.dcFrom(books.work_id, …)` or
   `book_metadata(source='catalog' | 'file').identifiers` (normalised
   via `hardcover/Isbn.from`).
2. `dc:<sha256hex(trim.lowercase(id))>` from usable `dc:identifier` in
   `books.work_id` or `book_metadata(source='catalog' | 'file').identifiers`
   (excluding `USELESS_IDENTIFIERS`).
3. `sha:<edition_sha>` from the retiring main server's
   `work_alias.edition_sha` (also copied into the new Drive
   `work_alias.edition_sha`).
4. `stub:<sha256hex("liseur-stub|v1|" + book.url)>` — a deterministic
   portable stub key when none of (1)–(3) exist, allowing the preserved
   stub's catalog entry, metadata overrides, custom cover, series
   choices, archive state, annotations, evaluations, and eligible
   reading sessions to seed Drive immediately. If another device or a
   later download supplies a real EPUB (`isbn:` / `dc:` / `sha:`),
   title+author confirmed matching proposes a `work_alias(confidence =
   'low')` merge that emits a `workAlias` `merge:` record on reader
   confirmation.

Once a book is published its key is frozen in `work_alias.work_id`; a
stronger identifier found later produces an alias, not a rekey. Title
and author never form a key; they only propose a merge, stored as
`work_alias.confidence='low'`, which the reader confirms.

### Wire schema v1

Flat files in `appDataFolder`:

| Name | Writer | Mutability |
|---|---|---|
| `device-<deviceId>-manifest.json` | owning device | updated in place |
| `device-<deviceId>-seq-<%010d>.json` | owning device | immutable delta |
| `device-<deviceId>-snapshot-<%010d through>-part-<%03d>.json` | owning device | immutable, at most 4 MiB per part |
| `cover-<sha256 of webp bytes>.webp` | any device | content-addressed |

Every file has `appProperties` `liseur=1`, `kind=manifest|delta|snapshot|cover`
and `schemaVersion=1` (each key and value ≤ 124 bytes). Device files add
`deviceId`, deltas add `seq` and `batchId`, covers add `sha256`. EPUBs in
the `Liseur/` folder carry `liseurKind=epub`, `sha256` and `workKey`.

Manifest:

```json
{"schemaVersion":1,"kind":"manifest","deviceId":"…","deviceLabel":"Google Pixel 8",
 "appVersionCode":123,"updatedHlc":"…",
 "snapshot":{"throughSeq":40,"parts":[{"name":"…-part-000.json","fileId":"…","sha256":"…","records":812,"resolutions":4}]},
 "previousSnapshot":{"throughSeq":20,"parts":[…]},
 "domains":{"catalog":1,"metadata":1,"series":1,"position":1,"furthest":1,"annotation":1,
            "evaluation":1,"vocabWord":1,"vocabOccurrence":1,"vocabReview":1,"session":1,"workAlias":1},
 "epubFolder":{"fileId":"…","createdTime":"…"},
 "appliedVector":{"<otherDeviceId>":120}}
```

`appliedVector` is informational; it is reserved for tombstone garbage
collection.

Delta batch:

```json
{"schemaVersion":1,"kind":"delta","batchId":"<nameUUIDFromBytes('batch|deviceId|seq')>",
 "deviceId":"…","seq":58,"createdHlc":"…","domains":{"annotation":1,"position":1},
 "records":[
   {"d":"annotation","k":"5b1e…","op":"put","hlc":"…","fp":"…","anc":["…"],"resRefs":["…"],"body":{…}},
   {"d":"annotation","k":"7c2a…","op":"resolve","outcome":"delete","resolvedFps":["…","…"],
    "hlc":"…","fp":"…","anc":["…"],"resRefs":["…"],"body":null}
 ]}
```

A `vocabWord` `del` additionally carries `observed`, the sorted `syncId`s
of the reviews its author had seen; it is excluded from `delFp` and lets
the receiver detect reviews the deleter never observed before it cascades
any delete.

- A batch holds at most 2,000 records and 1 MiB; larger ones split into
  consecutive seqs.
- One batch is in flight. It is built, frozen into `drive_outbox` (seq
  allocated in the same transaction), then uploaded. Retries look it up
  with `files.list(spaces=appDataFolder, q="appProperties has
  {key='batchId' and value='…'}")`. Once the fileId is known, per-peer
  delivery acks are written in `liseur.db` (`acked_fingerprint` /
  `acked_revision` + `sent_json`), `drive_record_state.published_fp` /
  `drive_session_ack` are updated, and the outbox row becomes `ACKED`.
  Advancing delivery acks stops redundant re-upload and never alters
  causal conflict detection. A frozen batch is never rebuilt.
- `op: "resolve"` records always include `outcome` (`"put"` with
  non-null `body`, or `"delete"` with `body: null`) and `resolvedFps`
  (sorted conflicting fps) in addition to `anc`. Choosing **Delete note**
  emits `op: "resolve", outcome: "delete", body: null` — never plain
  `op: "del"`.
- Full domain wire bodies, snapshot-part envelopes, canonical
  fingerprint rules, and Room mappings are specified in
  `docs/DRIVE_SYNC.md`.

Versioning: a `schemaVersion` above the build's pauses that device's
whole stream without downloading it and shows "Update Liseur to read
changes from <deviceLabel>". A domain version above the build's skips
only that domain: `drive_cursor.paused_schema_version` is set for that
`(device, domain)`, the cursor stays, and the stream is re-read after an
upgrade. Writers emit the lowest version that can represent the data.

Covers: custom covers (`metadata` override field `cover`,
`cover_override = 1`) sync for **all** catalog entries, including
`DownloadState.REMOTE` `drive:<workKey>` rows without a locally
downloaded EPUB. Re-encode to WebP, long edge ≤ 1200 px, file ≤ 512 KiB,
lowering quality stepwise and refusing the cover if it still does not
fit. The sha256 is of the uploaded bytes; dedupe on
`name='cover-<sha>.webp'`. Upload the cover blob before publishing the
metadata delta that references it. Non-custom embedded EPUB covers are
extracted locally when the EPUB is downloaded. No cover garbage
collection in v1.

### Compaction

A device compacts at 20 deltas since its last snapshot or 2 MiB of them.
Each snapshot part carries two sections (`docs/DRIVE_SYNC.md`):

- `records`: for every `(d, k)` the device authored, its latest record
  (`put`, `del`, or `resolve`) with its `anc` (up to 64 entries) and
  uncapped `resRefs` (winning resolution fps it causally descends from),
  plus all of its append-only records (`session`, `vocabReview`
  including review `del`s, and `furthest` resets). It never copies other
  devices' records and never drops `del` records in v1.
- `resolutions`: **all** durable completed resolution facts authored by
  this device (`{d, k, resolutionFp, resolvedFps, outcome, hlc}`),
  stored outside the 64-entry `anc` cap and never pruned in v1.

Open conflicts survive because each conflicting version remains its
author's latest record until resolved; completed resolutions survive
indefinitely — even after >64 successor edits and multiple compactions —
because `resolutions` carries every closed conflict's `resolvedFps`,
`outcome`, and `hlc` directly in every snapshot, while successor records
carry the winning `resolutionFp` in `resRefs` as durable causal proof of
descent (never relying on HLC comparisons).

Pruning lags one generation. At compaction `k`: upload `S_k`, rewrite the
manifest (`snapshot = S_k`, `previousSnapshot = S_{k-1}`), then delete
`S_{k-2}` and every delta with `seq ≤ T_{k-1}`. A reader that gets a 404
re-reads the manifest; a reader whose cursor is below the oldest
surviving delta applies the snapshot, which is idempotent.

### Change feed

1. First connection: store `changes.getStartPageToken()` as pending
   first; list manifests
   (`appProperties has {key='kind' and value='manifest'}`); per device
   read snapshot parts, then list deltas by `deviceId` and apply in seq
   order; list `Liseur` folders and EPUBs in `spaces=drive` (with
   `drive.file`, only app-created files are visible); commit the token.
2. Incremental: `changes.list(pageToken, spaces="appDataFolder,drive",
   includeRemoved=true, pageSize=1000, fields="nextPageToken,newStartPageToken,changes(fileId,removed,file(id,name,parents,trashed,appProperties,size,md5Checksum,createdTime))")`.
   Route by `appProperties.kind`. Apply each device's deltas only in
   contiguous seq order; a gap waits or falls back to the snapshot. Commit
   the token only after both database writes for that page.
3. An invalid or expired token (HTTP 400/404 on `pageToken`) triggers the
   full rescan from step 1, which is idempotent.
4. Apply order within a run: `workAlias` → `catalog` → `metadata` /
   `series` → `evaluation` → `annotation` → `position` / `furthest` →
   `vocab*` → `session`. A book-scoped record (`metadata`, `series`,
   `evaluation`, `annotation`, `position`) whose `workKey` has no local
   book and no catalog entry yet is deferred: its domain cursor stays put
   and the record replays once the catalog creates the book. If the
   author prunes those deltas meanwhile, the reader falls back to the
   author's snapshot, which carries the record's latest version.
5. Among app-created `Liseur` folders, the one with the lowest
   `(createdTime, fileId)` is canonical. Uploads go only there; EPUBs are
   read from any folder with `liseurKind=epub`.

A periodic manifest re-list (daily, and on foreground after more than 24
h) is the safety net if `changes.list` misses appdata changes with mixed
scopes.

### `drive_sync.db`

`src/play/kotlin/com/chmouel/liseur/drive/DriveSyncDatabase.kt`, `NAME =
"drive_sync.db"`, `version = 1`, `exportSchema = true`, modelled on
`HardcoverDatabase`. It is deliberately in neither backup XML, so a
restored device gets its own `deviceId`.

| Table | PK | Columns |
|---|---|---|
| `drive_install` | `id` (=1) | `device_id`, `created_at` |
| `drive_account` | `permission_id` | `email`, `state` (ACTIVE / PAUSED / NEEDS_RECONNECT), `connected_at`, `last_success_at`, `last_error`, `folder_id`, `start_page_token`, `next_seq`, `manifest_file_id`, `snapshot_through_seq`, `deltas_since_snapshot`, `hlc_wall_ms`, `hlc_counter`, `metered_transfers` |
| `drive_device` | `(permission_id, device_id)` | `label`, `manifest_file_id`, `app_version_code`, `snapshot_through_seq`, `last_seen_at` |
| `drive_cursor` | `(permission_id, device_id, domain)` | `applied_seq`, `paused_schema_version` |
| `drive_file` | `(permission_id, file_id)` | `name`, `kind`, `device_id`, `seq`, `sha256`, `size`, `md5`, `trashed`, `created_time`; index `(permission_id, kind, sha256)` |
| `drive_outbox` | `(permission_id, seq)` | `batch_id`, `payload`, `state` (BUILT / UPLOADED / ACKED), `file_id`, `created_at` |
| `drive_record_state` | `(permission_id, domain, record_key)` | `version_fp`, `version_op`, `published_fp`, `content_fp`, `last_synced_revision`, `origin_device_id`, `origin_seq`, `ancestors_json`, `res_refs_json`, `last_hlc`, `body_json` |
| `drive_resolution` | `(permission_id, domain, record_key, resolution_fp)` | `resolved_fps_json`, `outcome` (`put` / `delete`), `hlc`, `author_device_id`, `created_at` |
| `drive_conflict` | `(permission_id, conflict_id)` | `domain`, `record_key`, `book_url`, `kind`, `local_fp`, `remote_fp`, `remote_json`, `remote_device_id`, `remote_hlc`, `state` (OPEN / RESOLVING / RESOLVED), `choice`, `resolution_fp`, `opened_at`, `resolved_at` |
| `drive_session_ack` | `(permission_id, remote_session_id)` | `batch_seq`, `acked_at` |
| `drive_upload` | `(permission_id, sha256)` | `book_url`, `size_bytes`, `state`, `session_uri`, `bytes_sent`, `file_id`, `selected_at`, `updated_at` |

Batch receipts are `drive_outbox` for own uploads, `drive_session_ack`
for per-account session delivery, and `drive_cursor` for other devices;
there is no incoming-receipt table because apply is idempotent.
`conflict_id` is `sha256(domain|key|sorted(fpA, fpB))`.
Resolving a conflict writes `RESOLVING` and the choice, applies the
choice to `liseur.db`, publishes the `op: "resolve"` record (encoding
`outcome: "put"` or `"delete"` and `resolvedFps`), persists the durable
fact in `drive_resolution`, then marks `RESOLVED`; on restart a
`RESOLVING` row is re-applied idempotently. Acks stay atomic with their
data in `liseur.db` (natively via `acked_fingerprint` or through the
revision-to-fingerprint adapter on `evaluation_sync` and
`metadata_override_sync`); transport, causal ancestry, and resolution
history live here.

### Google authorization, account-switch state machine, and EPUB upload consent

- **Authorization & scopes:** Request authorization only when the reader
  taps **Connect Google Drive**, using Android's `AuthorizationClient`
  (`play-services-auth`). No client secret or embedded Client ID is
  stored in the APK or config; Google Play Services matches the Android
  OAuth clients in `liseur-515611` by `(packageName, SHA-1)`. Request
  only the two non-sensitive scopes:
  - `https://www.googleapis.com/auth/drive.file` (app-created visible
    `Liseur/` folder for selected EPUB uploads), and
  - `https://www.googleapis.com/auth/drive.appdata` (private catalog
    metadata, custom covers, and reading-state batches/snapshots).
- **Account identity:** Immediately after authorization, call
  `about.get?fields=user(permissionId,emailAddress)` and key every local
  queue, cursor, conflict, session ack, upload selection, and `liseur.db`
  peer row by the stable `permissionId` (never by email, which can
  change).
- **Silent background refresh & reconnect:** Because `AuthorizationClient`
  issues short-lived access tokens without a refresh token, background
  `WorkManager` runs call `AuthorizationClient.authorize()` silently. If
  `hasResolution()` is true (revoked grant or interactive consent
  required), transition `drive_account.state` to `NEEDS_RECONNECT`,
  pause background workers, and surface a reconnect prompt in Sync
  Settings.
- **Account state machine:**

| Current state | Trigger | Transition & invariants |
|---|---|---|
| `DISCONNECTED` | Connect Account A (no active Drive account) | Authorize → `about.get` → create/resume `drive_account(permA)` → `ACTIVE` → start reading-data sync (`drive.appdata`) and present **Upload EPUB files** selection before any EPUB upload. |
| `ACTIVE(A)` | Background auth returns `hasResolution()` / 401 after refresh | Transition `A` → `NEEDS_RECONNECT`; pause Drive workers; preserve all queues/cursors. |
| `NEEDS_RECONNECT(A)` | User completes interactive re-auth for same `permA` | Transition `A` → `ACTIVE`; resume workers from saved cursors. |
| `ACTIVE(A)` or `NEEDS_RECONNECT(A)` | User taps **Disconnect** or activates a main `RemoteServer` | Stop Drive workers; transition `A` → `PAUSED`; keep all `permA`-partitioned cursors, outbox, uploads, and conflicts intact. |
| `ACTIVE(A)` or `PAUSED(A)` | User taps **Switch Google account** and authorizes Account B (`permB != permA`) | Enter **`PENDING_SWITCH_CONFIRMATION(A → B)`**. Stop workers for `A` and keep `A` **`PAUSED`** with all queues, cursors, folder mappings, and conflicts intact. **Zero writes to Account B** (no folder creation, no manifest, no delta, no cover, no EPUB upload) are permitted until the user confirms. Show an explicit confirmation dialog naming **Account B** (email/account name) and summarizing the exact counts of local **books**, **notes** (annotations), and **reading sessions** that will be synced to Account B. |
| `PENDING_SWITCH_CONFIRMATION(A → B)` | User cancels | Discard transient Account B token; return to previous state (`A` remains `PAUSED` or resumes `ACTIVE(A)`). Zero Drive writes occurred on B. |
| `PENDING_SWITCH_CONFIRMATION(A → B)` | User confirms | Ensure `A` stays `PAUSED` with its state untouched; create or resume `drive_account(permB)` as `ACTIVE`; begin whole-library reading-data sync (`drive.appdata`) for B; present a **fresh "Upload EPUB files" selection** for B (Account B's `drive_upload` queue is completely independent of Account A's). |

- **EPUB upload consent & transfer independence:**
  - Whole-library reading-data sync (`drive.appdata`: catalog, metadata
    overrides, custom covers, series, reading progress, furthest
    positions, annotations, evaluations, vocabulary, and reading
    sessions) runs automatically for the **entire library**, completely
    independent of which EPUBs (including zero EPUBs) are selected in
    **Upload EPUB files**.
  - Before the first EPUB upload on initial connect or after switching
    Google accounts, show the **Upload EPUB files** selection listing
    locally available books and estimated total bytes.
  - Bulk EPUB uploads and downloads (`drive.file`) default to unmetered
    networks (`metered_transfers = 0`, user-configurable) and run on a
    separate transfer pipeline from `drive.appdata`, so a large EPUB
    transfer never blocks bookmarks, notes, or reading positions.

### Play/FOSS boundary

Drive stays out of `ServerKind`. A new `sync/LibrarySyncBackend.kt` in
`src/main`:

```kotlin
interface LibrarySyncBackend {
    val active: Flow<Boolean>
    suspend fun isActive(): Boolean
    suspend fun deactivate()          // stop workers, mark account PAUSED, keep all state
    fun resume()                      // app start: reschedule workers if active
    val positionPeer: PeerPositionSync?   // answers NotSynced while inactive
    @Composable fun SettingsEntry(onOpen: () -> Unit) {}
    object None : LibrarySyncBackend { /* inactive, null peer */ }
}
```

Later phases add members (book-forgetting hooks, download for `drive:`
URLs) with no-op defaults. The foss `providers/LibrarySyncBackendFactory.kt`
returns `LibrarySyncBackend.None`; the play one returns
`DriveLibrarySync`. In `AppContainer`, `librarySync` is a `lazy` built
with narrow dependencies rather than `this` (`positionSync` is an eager
`val`, so the Drive peer must not depend on it);
`librarySync.positionPeer` is appended after kosync in
`CompositePositionSync`; `librarySync.resume()` is called next to
`readingTracker.resume()`. The Drive `peerId` constant lives in play, not
in `PeerPositionSync.Companion`.

Mutual exclusion invariant: never both `remote_server.id = 1` and a
`drive_account` row in state `ACTIVE`.

- Server → Drive: activation checks `remoteServerDao.get()`. If a main
  server is connected, the UI confirms (explaining that the server will
  be disconnected while remote data stays on the server, browse-only
  catalogs, all downloaded books, and every book with local reading
  history, overrides, custom covers, series choices, or archive state
  are preserved, and only genuinely untouched undownloaded server
  previews are removed from the shelf) and runs a history-preserving
  disconnect (`RemoteAccountRepository.disconnect(preserveHistoryStubs =
  true)`):
  1. **Before** `forgetSyncPeer(server)` clears the retiring server's
     `work_alias` rows, evaluates the preservation predicate below and
     captures retained identity evidence (`books.work_id` /
     `book_metadata` ISBN or DC identifier, retiring server
     `work_alias.edition_sha`, or deterministic `stub:` key per
     `### workKey`) for every preserved fileless stub, upserting its
     Drive `work_alias` (`peer_id = drive:<permissionId>`) so it can
     seed Drive immediately upon activation.
  2. Stops main-server sync workers and runs `forgetSyncPeer(server)`;
     leaves browse-only `RemoteServer` rows (`id > 1`) and their books
     untouched.
  3. Preserves **all** local sideloaded and downloaded books via
     `bookDao.unlinkDownloadedFromRemote()` with their files, notes,
     evaluations, overrides, series choices, progress, and reading
     sessions intact.
  4. Splits undownloaded main-server stubs (`remote_uuid IS NOT NULL AND
     browse_server_id IS NULL AND local_uri IS NULL`) into two sets:
     - **Genuinely untouched preview stubs:** rows with **none** of:
       - reading history (`reading_sessions`, `annotations`,
         `annotation_sync`, `book_evaluation`, or `reading_progress`),
       - reader-authored metadata overrides or custom covers
         (`title_override = 1`, `author_override = 1`,
         `description_override = 1`, `cover_override = 1`, or any
         `metadata_override_revision` row),
       - manual series choices (`series_override = 1`,
         `series_index_override = 1`, `series_claim_pending = 1`,
         `series_claim_reset = 1`, per `SeriesOrderDao` and
         `BookRemoval.blank()`), or
       - reader shelf/status decisions (`archived_at IS NOT NULL`,
         `finished_at IS NOT NULL`, `last_opened_at IS NOT NULL`, or
         `hidden_at IS NOT NULL`).
       Only these genuinely untouched preview stubs are removed via
       `BookRemoval.deleteByUrls(...)`.
     - **Preserved fileless stubs** (any undownloaded stub with reading
       history — e.g., a server book that was downloaded, read, and
       later had its download removed — OR with metadata overrides,
       custom covers, manual series choices, or archive/finished/opened/
       hidden state): **never** passed to `BookRemoval.forget()`.
       Instead, they are unlinked from the server (`unlinkFromRemote`,
       clearing `remote_uuid` and server catalog links while keeping
       `download_state = REMOTE`, `local_uri = NULL`) with their Drive
       `work_alias` already captured in step 1, preserving and seeding
       to Drive every reading session, annotation, evaluation, reading
       position, metadata override, custom cover, manual series choice,
       and archive state.
- Drive → server: a new constructor parameter
  `beforeMainServerChange: suspend () -> Unit = {}` on
  `RemoteAccountRepository`, invoked in `connect()` next to the existing
  `beforePublish`, in `store()`, in `publishCustom` and in
  `restoreBackup(server != null)`. `AppContainer` passes
  `{ librarySync.deactivate() }`.
- kosync stays allowed alongside Drive.

### Answers to open questions

1. Switching from a main server to Drive stops the server's workers and
   disconnects `remote_server.id = 1` using the history-preserving
   disconnect path: browse-only servers, all local/downloaded books, and
   all undownloaded stubs that carry local reading history, metadata
   overrides, custom covers, manual series choices, or archive/status
   state are preserved, acquire Drive `work_alias` rows before server
   peer cleanup, and seed Drive; only genuinely untouched undownloaded
   server-catalog previews are cleared from the shelf. The remote server
   keeps all its data. No dormant server mode.
2. Custom covers (`cover_override = 1`, `cover-<sha256>.webp`) sync for
   **all** catalog entries, including remote-only `drive:<workKey>` rows
   without a local EPUB. Non-custom embedded EPUB covers are extracted
   locally when the EPUB is downloaded.
3. Vocabulary delete versus concurrent review or edit: add-wins, silently.
4. Concurrent differing resolutions: higher HLC wins silently by `(hlc,
   fp)` lexicographical order **only** when both current versions are
   themselves competing `op: "resolve"` records (whether keep-vs-keep or
   keep-vs-delete); if a device has moved on to a subsequent edit
   (`fNew`), it falls through to normal conflict reconciliation so the
   edit is never silently overwritten.
5. A library tombstone reaching a device that has the file locally keeps
   the entry and unlinks it from Drive.
6. No garbage collection of tombstones, durable resolutions, or
   retired-device streams in v1; "Forget device" later.
7. Drive-pulled sessions are never uploaded to liseur-sync (`AND
   origin_device_id IS NULL`).
8. `translation_glossary` is out of scope.
9. `archived_at` syncs, `hidden_at` does not, "finished" travels with
   reading progress.
10. kosync is allowed alongside Drive.
11. Words with `ownerAccount != ''` are published and merged by
    `(language, normalized)`.
12. Progress, phase gates, and change log are tracked in
    `tmp/google-drive.md` per its working rules (`tmp/` stays local and
    gitignored); committed design contracts live in this ADR and
    `docs/DRIVE_SYNC.md`.

## Consequences

- Reconciliation reuses existing per-peer ack tables and domain merge
  logic (`reconcileReadingState`, `VocabularyMerge`, `WorkIdentifiers`)
  with no `liseur.db` schema change except migration 84→85 for session
  columns (`remote_session_id` + unique index, `origin_device_id`).
- Backup restore no longer duplicates device ids or sessions, because
  Drive state lives outside the backup and session ids derive from
  content, while per-account session delivery lives in
  `drive_session_ack` so restored databases and second Google accounts
  seed cleanly.
- FOSS builds contain no Drive code; the audit is that
  `./gradlew :app:dependencies --configuration fossReleaseRuntimeClasspath`
  shows no `play-services-auth` or `drive`.
- Tombstones, durable resolution facts, and custom covers grow without
  bound in v1.
- Switching from a main server to Drive preserves local/downloaded books
  and all fileless stubs carrying reading history, metadata/cover
  overrides, manual series choices, or archive/status state — capturing
  Drive identities before server peer cleanup so they seed Drive — while
  clearing only genuinely untouched undownloaded server-preview stubs.
- `drive:` catalog rows may break code that assumes a `REMOTE` row has
  `remote_uuid` or `download_href`. Phase 3 starts with a spike inserting
  such a row and running library, details and download tests.
- A wrong or shared ISBN can merge two different books. Phase 3 tests two
  EPUBs with the same ISBN and different content and title.
- Fingerprints could drift between app versions (float formatting);
  canonical formatting rules are fixed in `docs/DRIVE_SYNC.md`, acks are
  recomputed locally, and a golden test recomputes fps from stored rows.
- Re-keying `furthest_position.origin` as a device id differs from its
  documented "retention slot" meaning; run `FurthestPositionCaptureTest`
  with a Drive peer before Phase 3 merges.
- `AppContainer` initialisation order (eager `positionSync`, lazy
  backend) needs a Robolectric test with the Play factory.
- Large annotation or session histories can grow snapshots; parts are
  capped at 4 MiB and snapshots use resumable upload.
- Series conflicts are grouped by `seriesKey` to avoid one prompt per
  book; Phase 4 tests a five-book shelf reordered on both devices.
- The Phase 6 deterministic two-device test suite explicitly verifies
  the simultaneous-upload `EDIT_EDIT` repro (both devices upload before
  pulling), delete-vs-edit vs. stale delete, keep-vs-delete competing
  resolutions, competing resolution vs. subsequent edit (`resA → fNew`
  against higher-HLC `resB`), crash recovery between `liseur.db` and
  `drive_sync.db` (including `EvaluationSync`/`MetadataOverrideSync`
  revision-to-fp recovery and incoming `op: "resolve"` replay on both
  Agreement and Stale paths), returning offline device after >64
  successor edits and multiple compactions alongside the independent-
  branch/skewed-clock repro (`fB → fD` without `resRefs` proof), and
  Server → Drive switch preservation plus Drive seeding for both
  history-bearing and metadata-only fileless stubs.

### Rejected alternatives

- A `GOOGLE_DRIVE` entry in `ServerKind`: 36 files switch on it,
  `RemoteServer` is URL and credential shaped, and Drive would enter FOSS
  code and schema.
- An outbox written by every repository: the per-peer ack tables already
  turn "what is owed" into a diff.
- A shared mutable `library.json`: concurrent writers lose updates.
- All acks in `drive_sync.db`: acks would not be atomic with the data, and
  `BookRemoval` keepability and alias cleanup would not see them.
- `DeviceIdentity` or `sessionIdFor(deviceKey, localId)`: id collisions
  after a backup restore.
- Last-writer-wins by wall clock: contradicts reader-resolved conflicts
  and lets clock skew decide.
- Per-record version vectors: more machinery than a capped `anc` list
  needs.
- A dormant server row while Drive is active: every `RemoteRouter.kind()`,
  catalog and live path would need a "paused" check.
- Pulled sessions in `drive_sync.db`: statistics queries run on
  `liseur.db` and SQLite cannot join across database files.
