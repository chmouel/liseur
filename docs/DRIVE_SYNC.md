# Google Drive Sync — Wire Schema & Domain Matrix (v1)

Companion Phase 0 specification to [ADR-0078](adr/0078-google-drive-sync.md).
This document fixes the exact JSON wire schemas, canonical serialization and
fingerprinting rules, Room entity/column mappings, crash-recovery invariants,
and two-device acceptance test matrix for Liseur's Play-only Google Drive sync
(`drive.appdata` + `drive.file`).

---

## 1. Shared Primitives & Canonical Rules

### 1.1 Identifiers & Clocks

| Primitive | Format | Source / Lifetime |
|---|---|---|
| `permissionId` | Opaque Google Drive user string | `about.get?fields=user(permissionId,emailAddress)`; partitions all `drive_sync.db` tables. Never keyed by email. |
| `peerId` | `"drive:" + permissionId` | Used in every per-peer table of `liseur.db` (`work_alias`, `sync_peer_state`, `furthest_position`, `peak_delivery`, `annotation_sync`, `evaluation_sync`, `metadata_override_sync`, `vocabulary_word_sync`, `vocabulary_review_sync`, `vocabulary_staged_review`). |
| `deviceId` | Lowercase UUIDv4 (`36` chars) | Generated once per install in `drive_sync.db` (`drive_install.device_id`), excluded from Android backup/transfer. Never derived from `DeviceIdentity.id`. |
| `hlc` | `"%013d-%04x-%s".format(wallMs, counter, deviceId)` | Monotonic hybrid logical clock persisted in `drive_account(hlc_wall_ms, hlc_counter)`. Compared lexicographically (strict total order because `deviceId` breaks ties). On send: if `nowMs > wallMs`, `wallMs = nowMs; counter = 0`, else `counter += 1`. **Counter overflow:** when `counter` would become `0x10000`, do NOT widen the field — set `wallMs += 1` (a logical tick strictly ahead of any real clock reading) and reset `counter = 0`, so the fixed-width `%04x` stays 4 hex digits and lexicographic ordering stays monotonic across the overflow. On receive: `maxWall = max(localWall, nowMs, remoteWall)`; update `counter` accordingly. |
| `batchId` | `UUID.nameUUIDFromBytes("batch|$deviceId|$seq".toByteArray(UTF_8)).toString()` | Deterministic per `(deviceId, seq)` for idempotent retry deduplication in `appDataFolder`. |
| `workKey` | `"isbn:<isbn13>"` \| `"dc:<sha256hex>"` \| `"sha:<editionSha256>"` \| `"stub:<sha256hex>"` | For local/downloaded books, computed once `book_fingerprint` exists AND `books.file_metadata_checked = 1`. First match wins: (1) normalized ISBN-13 via `hardcover/Isbn.from` on `WorkIdentifiers.dcFrom(books.work_id, ...)` or `book_metadata(source='file').identifiers`; (2) `sha256Hex(trim.lowercase(id))` for any usable `dc:identifier` not in `USELESS_IDENTIFIERS`; (3) `book_fingerprint.sha256`. **Shared-identifier collision:** a local file's ISBN or `dc:` key falls back to `sha:<editionSha>` when that key already names a different edition (another local book, or a catalog entry whose `editions[]` lacks this sha) with a different normalized title+author, so two unrelated EPUBs carrying the same erroneous identifier stay separate. Known v1 limitation: two devices that each mint the same key for different books before either sees the other's catalog publish one catalog entry with two editions, and v1 has no split operation for it. **Fileless preserved stubs on Server → Drive switch** (where `BookRemoval.downloadRemoved()` cleared `book_fingerprint` and `file_metadata_checked = 0`, or the book was never downloaded): captured **before** `forgetSyncPeer(server)` deletes server `work_alias` rows in priority order: (1) ISBN-13 from `books.work_id` or `book_metadata(source='catalog'\|'file').identifiers`; (2) usable `dc:identifier` from `books.work_id` or `book_metadata(source='catalog'\|'file').identifiers`; (3) `sha:<edition_sha>` from the retiring server's `work_alias.edition_sha`; (4) deterministic portable fallback `stub:<sha256Hex("liseur-stub\|v1\|" + book.url)>` when none of (1)–(3) exist (later mergeable with a downloaded EPUB via title+author confirmed matching emitting a `workAlias` `merge:` record). Upserted into `work_alias(peer_id = drive:<permissionId>, confidence = 'high', confirmed = 1, seeded = 0)` so preserved fileless books seed Drive immediately. Frozen in `work_alias.work_id` once published; a stronger identifier found later emits a `workAlias` record instead of rekeying. |
| `conflictId` | `sha256Hex("$domain|$recordKey|${minOf(fpA, fpB)}|${maxOf(fpA, fpB)}")` | Primary key (with `permission_id`) in `drive_conflict`. |

### 1.2 Canonical JSON & Number Formatting

All fingerprinted objects and wire payloads use deterministic canonical JSON:

1. **Encoding:** UTF-8, no BOM, no insignificant whitespace, no trailing commas.
2. **Object keys:** Sorted lexicographically by UTF-16 code units (identical to
   ASCII/UTF-8 order for all ASCII schema keys).
3. **Nulls vs. omitted fields:** Every schema-declared field in a domain `body`
   is **always emitted** (with JSON `null` when nullable and absent) so two
   implementations never disagree on omitted-vs-null keys. Optional envelope
   fields (`outcome`, `resolvedFps`) are present **only** when `op == "resolve"`.
4. **Strings:** Standard RFC 8259 JSON escaping (`\"`, `\\`, `\b`, `\f`, `\n`,
   `\r`, `\t`, and `\u00XX` lowercase hex for other control characters `< 0x20`).
   Non-ASCII UTF-8 characters are written unescaped.
5. **Integers (`Int`, `Long`):** Base-10 decimal, no leading zeros except `0`,
   no decimal point.
6. **Floating-point (`Double`):**
   - Reject `NaN` and `±Infinity` before serialization.
   - Normalize `-0.0` to `"0"`.
   - Format via `BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()`,
     so `0.50` → `0.5`, `1.0` → `1`, `0.0` → `0`, `7.50` → `7.5` (matches
     `seriesIndexLabel` in `Series.kt`).
   - Progressions (`totalProgression`, `startProgression`, `endProgression`,
     `progression`) must lie in `[0.0, 1.0]`.
7. **Embedded JSON strings (`locatorJson`, `locator`):** Canonicalized recursively
   with sorted object keys before embedding (or `""` for anchorless `BOOK_NOTE`).
8. **Arrays with set semantics:** Sorted lexicographically before serialization
   (`resolvedFps`, `resRefs`, `identifiers` sorted by `(scheme, value)`,
   `editions` sorted by `sha256`).

### 1.3 Canonical Fingerprints (`fp`)

All fingerprints are 64-char lowercase hex SHA-256 digests of canonical JSON:

| Operation | Formula | Notes |
|---|---|---|
| `op: "put"` | `contentFp(body) = sha256Hex(canonicalJson({"op":"put","body":<canonicalBody>}))` | Depends only on content fields (never `editionSha` for annotations, never `position`, never local-only columns). Recomputable directly from the committed `liseur.db` row. |
| `op: "del"` | `delFp(d, k, hlc) = sha256Hex(canonicalJson({"op":"del","d":<d>,"k":<k>,"hlc":<hlc>}))` | Including `hlc` makes every delete event unique, so deleting a record after keeping it in an earlier `DELETE_EDIT` resolution never collides with the old delete in `anc`. |
| `op: "resolve"` | `resolveFp(outcome, resolvedFps, hlc, body) = sha256Hex(canonicalJson({"op":"resolve","outcome":<outcome>,"resolvedFps":<sorted>,"hlc":<hlc>,"body":<bodyOrNull>}))` | Distinct from both conflicting fps even when the reader keeps a local version verbatim (`outcome: "put"`), guaranteeing strict causal supersession over both sides. |

**Golden test invariant:** Phase 4–6 unit tests include a golden fingerprint
suite that writes representative rows to `liseur.db`, reads them back, computes
`contentFp`, and verifies exact SHA-256 equality against fixed fixtures.

---

## 2. Wire Envelopes (`appDataFolder` & `Liseur/`)

### 2.1 File Inventory & `appProperties`

Every `appProperties` key and value is strictly `≤ 124` UTF-8 bytes (Google
Drive v3 limit).

| File Name | Space | Writer | Mutability | `appProperties` |
|---|---|---|---|---|
| `device-<deviceId>-manifest.json` | `appDataFolder` | Owning device | Updated in place | `liseur=1`, `kind=manifest`, `schemaVersion=1`, `deviceId=<deviceId>` |
| `device-<deviceId>-seq-<%010d>.json` | `appDataFolder` | Owning device | Immutable | `liseur=1`, `kind=delta`, `schemaVersion=1`, `deviceId=<deviceId>`, `seq=<seq>`, `batchId=<batchId>` |
| `device-<deviceId>-snapshot-<%010d throughSeq>-part-<%03d>.json` | `appDataFolder` | Owning device | Immutable (`≤ 4 MiB`) | `liseur=1`, `kind=snapshot`, `schemaVersion=1`, `deviceId=<deviceId>`, `throughSeq=<throughSeq>`, `partIndex=<idx>` |
| `cover-<sha256>.webp` | `appDataFolder` | Any device | Immutable, content-addressed | `liseur=1`, `kind=cover`, `schemaVersion=1`, `sha256=<sha256>` |
| `Liseur/<sanitized-filename>.epub` | `drive` (`drive.file`) | Any device | Immutable per edition upload | `liseur=1`, `liseurKind=epub`, `sha256=<editionSha>`, `workKey=<workKey>` |

*Folder resolution:* Among app-created `Liseur` folders visible under
`drive.file`, the folder with the lexicographically lowest `(createdTime,
fileId)` is canonical. New EPUB uploads go only to the canonical folder; reads
discover EPUBs from any app-created folder whose files carry `liseurKind=epub`.

### 2.2 Manifest Envelope (`kind: "manifest"`)

```json
{
  "schemaVersion": 1,
  "kind": "manifest",
  "deviceId": "550e8400-e29b-41d4-a716-446655440000",
  "deviceLabel": "Google Pixel 8",
  "appVersionCode": 123,
  "updatedHlc": "0017450000000-0001-550e8400-e29b-41d4-a716-446655440000",
  "snapshot": {
    "throughSeq": 40,
    "parts": [
      {
        "name": "device-550e8400-e29b-41d4-a716-446655440000-snapshot-0000000040-part-000.json",
        "fileId": "1AbCdEfGh...",
        "sha256": "<64-hex of part UTF-8 bytes>",
        "records": 812,
        "resolutions": 4
      }
    ]
  },
  "previousSnapshot": null,
  "domains": {
    "workAlias": 1,
    "catalog": 1,
    "metadata": 1,
    "series": 1,
    "position": 1,
    "furthest": 1,
    "annotation": 1,
    "evaluation": 1,
    "vocabWord": 1,
    "vocabOccurrence": 1,
    "vocabReview": 1,
    "session": 1
  },
  "epubFolder": {
    "fileId": "1XyZ...",
    "createdTime": "2026-04-01T12:00:00.000Z"
  },
  "appliedVector": {
    "661f9511-f3ac-52e5-b827-557766551111": 120
  }
}
```

- `snapshot` and `previousSnapshot` are `null` until the first compaction runs.
- `epubFolder` is `null` until the user selects at least one EPUB and the
  `Liseur/` folder is created (never created before Account B switch
  confirmation or before EPUB consent).
- `domains` declares the highest domain schema version emitted by this device.

### 2.3 Delta Batch Envelope (`kind: "delta"`)

Limits: at most **2,000 records** and **1 MiB** (1,048,576 UTF-8 bytes) per
batch; larger queues split into consecutive `seq` values (`1, 2, 3, ...`).

```json
{
  "schemaVersion": 1,
  "kind": "delta",
  "batchId": "3d8c4a12-...",
  "deviceId": "550e8400-e29b-41d4-a716-446655440000",
  "seq": 58,
  "createdHlc": "0017450000000-0002-550e8400-e29b-41d4-a716-446655440000",
  "domains": {
    "annotation": 1,
    "position": 1
  },
  "records": [
    {
      "d": "annotation",
      "k": "5b1e...",
      "op": "put",
      "hlc": "0017450000000-0001-550e8400-e29b-41d4-a716-446655440000",
      "fp": "<64-hex contentFp>",
      "anc": ["<prevFp>"],
      "resRefs": ["<winningResolveFp>"],
      "body": { "...": "..." }
    },
    {
      "d": "annotation",
      "k": "7c2a...",
      "op": "del",
      "hlc": "0017450000000-0002-550e8400-e29b-41d4-a716-446655440000",
      "fp": "<64-hex delFp>",
      "anc": ["<prevFp>"],
      "resRefs": [],
      "body": null
    },
    {
      "d": "annotation",
      "k": "9f0b...",
      "op": "resolve",
      "outcome": "delete",
      "resolvedFps": ["<fpA>", "<fpB>"],
      "hlc": "0017450000000-0003-550e8400-e29b-41d4-a716-446655440000",
      "fp": "<64-hex resolveFp>",
      "anc": ["<fpA>", "<fpB>", "<olderAnc>"],
      "resRefs": ["<priorWinningResolveFp>"],
      "body": null
    }
  ]
}
```

Record envelope fields:

| Field | Type | Required | Description |
|---|---|---|---|
| `d` | `String` | Always | Domain tag (`workAlias`, `catalog`, `metadata`, `series`, `position`, `furthest`, `annotation`, `evaluation`, `vocabWord`, `vocabOccurrence`, `vocabReview`, `session`). |
| `k` | `String` | Always | Domain wire key. |
| `op` | `String` | Always | `"put"`, `"del"`, or `"resolve"`. |
| `outcome` | `String` | Only when `op == "resolve"` | `"put"` (body is non-null chosen state) or `"delete"` (`body` is `null`). |
| `resolvedFps` | `Array<String>` | Only when `op == "resolve"` | Sorted, deduplicated list of the exact conflicting fps closed by this resolution (at least 2 fps). |
| `hlc` | `String` | Always | Authoring HLC for this record. |
| `fp` | `String` | Always | Canonical 64-hex SHA-256 fingerprint per Section 1.3. |
| `anc` | `Array<String>` | Always | Superseded version fps, newest first, deduplicated, capped at **64** entries (`[]` for append-only domains and new records). |
| `resRefs` | `Array<String>` | Always | Sorted, deduplicated, **uncapped** list of winning resolution fps (`resolveFp`) from which this record causally descends, carried forward across every successor edit, delete, or resolution outside the 64-entry `anc` cap (`[]` for append-only domains and records with no prior resolution in their ancestry; excluded from `contentFp`). |
| `body` | `Object \| null` | Always | Domain payload object for `op == "put"` and `op == "resolve" && outcome == "put"`; strictly `null` for `op == "del"` and `op == "resolve" && outcome == "delete"`. |
| `observed` | `Array<String>` | Only when `d == "vocabWord" && op == "del"` | Sorted, deduplicated `vocabReview.syncId` list the deleting device had observed when it authored the delete — the causal review boundary (§4.9). Absent otherwise. Excluded from `delFp` (§1.3). |

### 2.4 Snapshot-Part Envelope (`kind: "snapshot"`)

Triggered when a device has accumulated `≥ 20` deltas or `≥ 2 MiB` of deltas
since its last snapshot. Each part is capped at **4 MiB** (4,194,304 UTF-8
bytes) and uploaded via resumable upload. Readers verify each part's SHA-256
against `manifest.snapshot.parts[].sha256` before parsing.

```json
{
  "schemaVersion": 1,
  "kind": "snapshot",
  "deviceId": "550e8400-e29b-41d4-a716-446655440000",
  "throughSeq": 40,
  "partIndex": 0,
  "partCount": 2,
  "createdHlc": "0017450000000-0010-550e8400-e29b-41d4-a716-446655440000",
  "domains": {
    "workAlias": 1,
    "catalog": 1,
    "metadata": 1,
    "series": 1,
    "position": 1,
    "furthest": 1,
    "annotation": 1,
    "evaluation": 1,
    "vocabWord": 1,
    "vocabOccurrence": 1,
    "vocabReview": 1,
    "session": 1
  },
  "records": [
    { "d": "catalog", "k": "isbn:9780547928227", "op": "put", "hlc": "...", "fp": "...", "anc": ["..."], "resRefs": [], "body": { "...": "..." } }
  ],
  "resolutions": [
    {
      "d": "annotation",
      "k": "9f0b...",
      "resolutionFp": "<64-hex resolveFp>",
      "resolvedFps": ["<fpA>", "<fpB>"],
      "outcome": "delete",
      "hlc": "0017450000000-0003-550e8400-e29b-41d4-a716-446655440000"
    }
  ]
}
```

**Snapshot retention & pruning invariants:**
1. **`records` section:** Holds, for every `(d, k)` authored by this device:
   - For mutable domains: the device's latest record (`put`, `del`, or `resolve`)
     with its `anc` (capped at 64) and its uncapped `resRefs` (every winning
     resolution fp from which the record causally descends). Tombstones (`del`)
     are never dropped in v1.
   - For append-only domains (`session`, `vocabReview` including review `del`s,
     `furthest` resets): all authored records in full.
   - Never copies records authored by other devices.
2. **`resolutions` section (uncapped):** Holds **every** durable completed
   resolution fact `{d, k, resolutionFp, resolvedFps, outcome, hlc}` authored by
   this device, outside the 64-entry `anc` cap and never pruned in v1. When
   splitting across multiple 4 MiB parts, both `records` and `resolutions` pack
   across parts (either array may be `[]` on a given part). Readers ingest all
   `resolutions` across all parts of a snapshot **before** reconciling `records`,
   and use `res.resolutionFp ∈ R.resRefs` (never HLC comparison) to prove causal
   descent across compaction boundaries.
3. **One-generation pruning lag:** At compaction `k`: upload all parts of `S_k`,
   rewrite manifest with `snapshot = S_k` and `previousSnapshot = S_{k-1}`, and
   only then delete `S_{k-2}` parts and deltas with `seq ≤ T_{k-1}`.
4. **Known v1 limitation (spurious conflict, no data loss):** `anc` keeps only
   the newest 64 superseded fps, and the bridge above covers only resolutions.
   If device B last saw record `f0`, stays offline while A makes more than 64
   sequential edits to that same record, and A's deltas are pruned before B
   returns, B finds neither `f0` in `anc` nor a resolution bridge and opens an
   `EDIT_EDIT` conflict between its unchanged `f0` and A's latest version. Both
   bodies are kept and the reader picks one. A per-record version vector would
   remove this case and is deferred past v1.

### 2.5 Custom Cover Blobs (`cover-<sha256>.webp`)

- Applies to custom covers (`books.cover_override = 1`, `metadata` field
  `cover`) for **all** catalog entries, including `DownloadState.REMOTE`
  `drive:<workKey>` rows with no locally downloaded EPUB.
- Encoding: decode image, scale down preserving aspect ratio so `max(width,
  height) ≤ 1200 px`, encode lossy WebP starting at quality `85` and stepping
  down (`85 → 75 → 65 → 55 → 45`) until `bytes.size ≤ 524,288` (512 KiB). Refuse
  the cover with a non-fatal status warning if it still exceeds 512 KiB.
- Hash `sha256 = sha256Hex(webpBytes)`. Check `drive_file` / `files.list` for
  existing `name = 'cover-<sha256>.webp'` in `appDataFolder`; upload only if
  absent, **before** publishing the `metadata` delta referencing `coverSha256`.

---

## 3. Conflict Detection, Resolution & Crash-Recovery Protocol

### 3.1 Delivery State vs. Causal Supersession

- **Delivery state (`published_fp`, `acked_revision`, `drive_session_ack`)**
  controls outbound upload selection only. Completing an upload sets
  `published_fp := L` so the device stops re-uploading `L`; it **never** causes
  incoming edits to fast-forward over `L`.
- **Causal state** (`L = drive_record_state.version_fp`, `version_op` =
  `"put"` | `"del"` | `"resolve"`, `anc(L)`, `res_refs(L)` = uncapped winning
  resolution fps stored in `drive_record_state.res_refs_json`,
  `(origin_device_id, origin_seq)`, and `drive_resolution`) governs incoming
  conflict detection.

When record `R` arrives from device `R.deviceId` at batch `R.seq` (for a
snapshot, `R.seq = snapshot.throughSeq`):

0. **Mandatory resolution & `resRefs`-bridge settlement pre-step (runs BEFORE
   Steps 1–6 — including `Agreement` and `Stale` replay paths — and BEFORE
   advancing `drive_cursor`):**
   - **When `R.op == "resolve"`:** ALWAYS idempotently persist
     `(permission_id, d, k, R.fp, R.resolvedFps, R.outcome, R.hlc, R.deviceId)`
     into `drive_resolution`, settle/close any `OPEN` (or `RESOLVING`) row in
     `drive_conflict` for `(permission_id, d, k)` whose conflicting fps are
     covered by `R.resolvedFps` (marking it `RESOLVED` with `resolution_fp =
     winningResolutionFp`, the highest `(hlc, fp)` among overlapping
     resolutions in `drive_resolution`, and `resolved_at`), and collect
     `R.resRefs` (plus `R.fp` if `R` is the winning resolution) as candidate
     resolution refs.
   - **When any incoming record `R` (`put`, `del`, or `resolve`) carries
     proven resolution descent in `R.resRefs`:** for every winning
     `res ∈ drive_resolution` for `(permission_id, d, k)` with
     `res.resolutionFp ∈ R.resRefs`, idempotently settle/close any `OPEN` (or
     `RESOLVING`) row in `drive_conflict` whose conflicting fps are covered by
     `res.resolvedFps` (marking it `RESOLVED` with `resolution_fp =
     res.resolutionFp` and `resolved_at`) and collect `res.resolutionFp` as a
     candidate resolution ref.
   - **Candidate refs join `res_refs(L)` only with proven descent.** They are
     merged into `drive_record_state.res_refs_json` only by a case below that
     makes the resulting `L` adopt or descend from `R`: Unseen, Agreement, an
     applied incoming resolution, causal or resolution-bridged supersession,
     or Stale because `R.fp ∈ anc(L)`. When `R` opens a conflict or is
     Stale only because a resolution retired it, `res_refs(L)` stays unchanged: otherwise a later local edit
     would advertise descent from a resolution it never adopted, and a peer
     could use that false bridge to silently supersede a conflicting branch.
     The conflict settlements and `drive_resolution` rows above are still
     committed in every case.
   Transaction 2 MUST commit these resolution side effects before `drive_cursor`
   may advance past `R.seq` — including when crash recovery routes replay of a
   compacted successor `put`/`del` to `Agreement` or `Stale`.

1. **Unseen (`L == none`):** Apply `R` silently. Set `L := R.fp`, `version_op
   := R.op`, `published_fp := R.fp`, `origin_device_id = R.deviceId`,
   `origin_seq = R.seq`, `anc(L) := R.anc`, and `res_refs(L) := R.resRefs ∪
   ({R.fp} if R.op == "resolve" else ∅)`.
2. **Agreement (`R.fp == L`, OR both `L` and `R` are deletions (`del`, or a
   `del` paired with a `resolve` whose `outcome` is `"delete"`; two **distinct**
   `op: "resolve"` records never agree here — they are routed to the
   incoming-resolution rule in Step 3 first), OR `R.op == "resolve" && R.outcome ==
   "put"` and the committed `liseur.db` row already matches `contentFp(R.body)`
   with a matching per-peer ack after crash recovery):** No conflict. Merge
   `anc(L) := cap64(anc(L) ∪ R.anc)` and `res_refs(L) := res_refs(L) ∪
   R.resRefs`. For concurrent `del`s, adopt the higher `(hlc, fp)` as `L`; for
   incoming `op: "resolve"` replay agreement (including fingerprint-native
   tables like `annotation_sync` where `acked_fingerprint` holds `contentFp` /
   `"DELETED"`), adopt `L := R.fp`, `version_op := "resolve"`, `published_fp :=
   R.fp`, and add `R.fp` to `res_refs(L)`. (Step 0 has already persisted
   `drive_resolution` and closed any covered `OPEN` conflict before advancing
   the cursor.)
3. **Incoming `op: "resolve"`:** (Step 0 has already persisted `drive_resolution`
   and settled covered `OPEN` conflicts.)
   - If `R.fp ∈ anc(L)`: Stale; ignore body (side effects already committed by
     Step 0).
   - **Competing resolutions — restricted strictly to current `op: "resolve"`
     versions:** silent `(hlc, fp)` arbitration applies **ONLY** when **both**
     the **current** local version `L` is itself an `op: "resolve"` record
     (`drive_record_state.version_op == "resolve"`, matching a `drive_resolution`
     row `L_res` with `L_res.resolutionFp == L`) AND incoming `R` is
     `op: "resolve"`, with **equivalent `resolvedFps` sets** (`set(L_res.resolvedFps)
     == set(R.resolvedFps)` — both resolve exactly the same conflicting fps) and
     neither in the other's `anc`. Compare `(R.hlc, R.fp)` vs. `(L_res.hlc,
     L_res.resolutionFp)`: higher wins **silently**. If `R` wins, apply
     `R.outcome`, set `L := R.fp`, `version_op := "resolve"`, `published_fp :=
     R.fp`, `res_refs(L) := res_refs(L) ∪ R.resRefs ∪ {R.fp}`, and update the
     settled `resolution_fp` to `R.fp`; if local wins, keep local and add
     `R.fp` to `anc(L)`.
   - **Partially overlapping `resolvedFps` sets:** when the two `resolvedFps`
     sets intersect but are NOT equivalent, the overlapping fps are covered by
     both choices and may be applied, but every independently chosen branch
     behind an fps present in only one set is **preserved** — never silently
     discarded. The overlapping fps are arbitrated as above, the uncovered
     local branch keeps `L` as the local version, `R`'s uncovered outcome is
     kept, and a conflict is opened for the uncovered fps so the reader decides
     which independent resolution survives: `EDIT_EDIT` if both outcomes are
     `"put"`, `DELETE_EDIT` if one is `"put"` and the other `"delete"` (the
     live body is the keep option). If both are `"delete"`, no live body exists
     to offer, so no conflict is invented: the union of the resolved branches
     is kept.
   - **Subsequent local edit (`version_op != "resolve"`):** If local previously
     resolved the conflict as `resA` and then moved on to a subsequent edit
     `fNew` (so `L = fNew`, `version_op == "put"` or `"del"`), **do NOT**
     silently arbitrate against the historical `resA`. Fall through to the
     remaining causal/conflict checks below so `fNew` is never silently
     overwritten.
   - Else if `L ∈ R.resolvedFps` or an `OPEN` conflict (just settled by Step 0)
     covered `L`: apply `R.outcome` silently (`put` writes `R.body`; `delete`
     deletes local row while retaining its per-peer sync row), set `L := R.fp`,
     `version_op := "resolve"`, `published_fp := R.fp`, `origin_device_id =
     R.deviceId`, `origin_seq = R.seq`, merge `anc` and `res_refs(L) :=
     res_refs(L) ∪ R.resRefs ∪ {R.fp}`.
   - Else if `L ∈ R.anc` (with no uncovered concurrent edit): causal
     supersession; apply `R.outcome` silently and update `L`, `version_op :=
     "resolve"`, `anc`, and `res_refs`.
   - Else if **proven resolution descent** carries `R` past local: `∃` winning
     `res ∈ drive_resolution` for `(d, k)` with `L ∈ res.resolvedFps` **AND
     `res.resolutionFp ∈ R.resRefs`** (the same durable bridge Step 4 requires —
     never merely `R.hlc >= res.hlc`). Close any `OPEN` `drive_conflict` covered
     by `res.resolvedFps`, apply `R.outcome` silently, and set `L := R.fp`,
     `version_op := "resolve"`, `published_fp := R.fp`, `origin_device_id =
     R.deviceId`, `origin_seq = R.seq`, merging `anc` and `res_refs(L) :=
     res_refs(L) ∪ R.resRefs ∪ {R.fp}. This check runs inside the
     incoming-resolution rule BEFORE falling through to conflict: a `resolve`
     that provably descends from the winning resolution supersedes an `L` that
     resolution retired, so it must not open a spurious conflict.
   - Else: local has an independent or subsequent edit (such as `fNew`) not
     covered by `R.resolvedFps`; open a conflict (`EDIT_EDIT` if `R.outcome ==
     "put"`, `DELETE_EDIT` if `R.outcome == "delete"`) preserving both.
4. **Causal supersession (fast-forward silently):** Triggered if ANY of:
   - No `OPEN` conflict exists on `(d, k)` and `L` is not retired in
     `drive_resolution`, AND (`L ∈ R.anc` OR `L` was applied unmodified from
     the same author with `origin_device_id == R.deviceId && origin_seq < R.seq
     && local_row_matches(content_fp)`), OR
   - **Resolution-bridged supersession:** `∃ res ∈ drive_resolution` for
     `(d, k)` such that (a) `L ∈ res.resolvedFps`, (b) `res` is the winning
     resolution among overlapping resolutions in `drive_resolution` (highest
     `(hlc, resolutionFp)`), AND (c) **`res.resolutionFp ∈ R.resRefs`**
     (durable causal proof that `R` descends from the winning resolution —
     **NEVER** merely `R.hlc >= res.hlc`). Also closes any `OPEN`
     `drive_conflict` covered by `res.resolvedFps`.
   Apply `R` silently, update `L := R.fp`, `version_op := R.op`, `published_fp
   := R.fp`, `origin_device_id = R.deviceId`, `origin_seq = R.seq`, merge `anc`
   and `res_refs(L) := res_refs(L) ∪ R.resRefs`.
5. **Stale (ignore body):** Triggered if `R.fp ∈ anc(L)` OR `∃ res ∈
   drive_resolution` for `(d, k)` with `R.fp ∈ res.resolvedFps`. (If `R.op ==
   "resolve"`, Step 0 has already persisted `drive_resolution` and closed
   covered conflicts before ignoring the body.)
6. **Concurrent divergence (Conflict):**
   - Both `L` and `R` live `put` → open `EDIT_EDIT` in `drive_conflict` (keep
     local row in `liseur.db`, store `R.body` in `drive_conflict.remote_json`).
   - One live `put`, one `del` → open `DELETE_EDIT` in `drive_conflict` (surface
     **Keep edited note** / **Delete note**).

### 3.2 Two-Database Commit & Crash-Recovery Matrix

Every apply and outbox ACK commits **Transaction 1 (`liseur.db`) first**, then
**Transaction 2 (`drive_sync.db`)**.

| Domain / Table | Atomic `liseur.db` Ack | Recovery if Crash Between Tx 1 and Tx 2 |
|---|---|---|
| `annotation` (`annotations` + `annotation_sync`) | `annotation_sync.acked_fingerprint = contentFp` (or `"DELETED"` on delete/delete-resolution). | Recompute `contentFp` from committed `annotations` row (or `"DELETED"` if absent). On replay: if `rowContentFp == R.contentFp` → Agreement (repair `drive_record_state`, advance cursor). If user edited after crash (`rowContentFp != acked_fingerprint == R.contentFp`) → record `R.fp` in `anc`, preserve new local edit as dirty. |
| `evaluation` (`book_evaluation` + `evaluation_sync`) | Revision-to-fingerprint adapter: `acked_revision = rev`, `sent_revision = rev`, `sent_json = {"vfp": "<fp>", "cfp": "<contentFp>", "rev": <rev>}`. | When `drive_record_state.last_synced_revision < sync.acked_revision`: if `row.revision == sync.acked_revision`, recompute `contentFp` from committed `book_evaluation` row (or `"DELETED"` if `value == null`), pair with `sent_json.vfp`, and repair `drive_record_state`. If `row.revision > sync.acked_revision`, seed `anc` with `sent_json.vfp` and keep new edit dirty (`row.owed(sync) == true`). |
| `metadata` (`books` + `metadata_override_revision` + `metadata_override_sync`) | Same revision-to-fingerprint adapter on `metadata_override_sync` (`acked_revision`, `sent_revision`, `sent_json`), plus `remote_cover_sha256` & `adopted` for `cover`. | Identical to `evaluation`: recompute `contentFp` from committed `books` override columns when `rev.revision == sync.acked_revision`; seed `anc` from `sent_json.vfp` if reader edited again before recovery ran. |
| `vocabWord` + `vocabOccurrence` (`vocabulary_*` + `vocabulary_word_sync`) | `vocabulary_word_sync.acked_fingerprint` + `acked_base_json`. | Recompute merged word+occurrence `contentFp` from committed `vocabulary_words`/`vocabulary_occurrences` and repair `drive_record_state`. |
| `vocabReview` (`vocabulary_reviews` + `vocabulary_review_sync`) | `UNIQUE(wordId, syncId)` + `vocabulary_review_sync(peer_id, review_id, acked_deleted)`. | `INSERT OR IGNORE` + idempotent `reconcileSchedule`; replay inserts 0 rows and produces identical SRS state. |
| `session` (`reading_sessions` + `drive_session_ack`) | `UNIQUE INDEX` on `reading_sessions.remote_session_id`. | Incoming pull uses `INSERT OR IGNORE` — replay after crash inserts 0 rows, so session counts once. Outbound upload tracks per-account delivery in `drive_session_ack`. |
| `series` / `catalog` (`books`) | Idempotent row writes; `drive_record_state` stores `version_fp`, `published_fp`, `content_fp`. | Recovery runs local diffing **before** pulling, so a post-crash edit is first recorded as a new local version whose `anc` holds the pre-crash `L`, never `R`. Replay recomputes `contentFp(book)` from committed `books` row; matches `contentFp(R.body)` → Agreement, repairs `drive_record_state`, advances cursor. These domains have no atomic per-peer receipt, so if the reader edited the row after the crash and before replay, replay cannot prove the edit descends from `R`: for `series` it opens a reader-resolved conflict (both kept); for `catalog` it applies the domain's automatic field merge (§4.2), which never drops either side's editions or identifiers. |
| `position` / `furthest` (`reading_progress` + `sync_peer_state` + `furthest_position`) | `sync_peer_state.acked_revision`, `agreed_*`, `pending_*`; `FurthestPositionDao.observe` (monotonic max). | `reconcileReadingState` and `observe` / `deleteUpToForOrigin` are strictly idempotent on replay. |
| Local conflict resolution (`drive_conflict` `RESOLVING`) | Local choice applied to `liseur.db` in Tx 1; `drive_conflict.state` moves `RESOLVING → RESOLVED` after publish. | On restart, any `RESOLVING` row checks committed `liseur.db` state, ensures the `op: "resolve"` batch is frozen/uploaded, persists `drive_resolution` and `res_refs_json`, and marks `RESOLVED`. |
| Incoming `op: "resolve"` replay (`drive_conflict` `OPEN`) | Remote resolution outcome + per-peer ack/`sent_json` committed to `liseur.db` in Tx 1; `drive_resolution`, `drive_conflict` (`RESOLVED`), `res_refs_json`, and `drive_cursor` committed in Tx 2. | If crash occurs after Tx 1 and before Tx 2, recovery repairs `drive_record_state` from `liseur.db` while the conflict stays `OPEN` and `drive_cursor` stays un-advanced. On replay of `R` (`op: "resolve"`), **Step 0 runs BEFORE Agreement (unedited) and BEFORE Stale (post-crash local edit)**, idempotently persisting `R` in `drive_resolution`, closing the `OPEN` conflict in `drive_conflict` as `RESOLVED`, updating `res_refs_json`, and only then advancing `drive_cursor`. |

---

## 4. Per-Domain Wire Schema & Room Mapping Matrix

Apply order within every sync run:
`workAlias` → `catalog` → `metadata` / `series` → `evaluation` → `annotation` → `position` / `furthest` → `vocabWord` → `vocabOccurrence` → `vocabReview` → `session`.

A `metadata`, `series`, `evaluation`, `annotation` or `position` put whose
`workKey` has no local book and no catalog entry yet is deferred: its domain
cursor stays put, so the record replays once the catalog creates the book.
A book-scoped sync publishes the book's catalog entry along with its other
records, so a receiver never waits on a catalog record that was not sent.

---

### 4.1 `workAlias` (Domain v1)

- **Purpose:** Converge manual fuzzy-match merges and rejections across devices,
  and propagate stronger identifiers discovered after initial publication.
- **Wire keys `k`:**
  - Merge: `"merge:" + fromWorkKey`
  - Rejection: `"reject:" + minOf(a, b) + "|" + maxOf(a, b)`
- **`put` body (merge):**
  ```json
  {
    "kind": "merge",
    "from": "sha:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    "to": "isbn:9780547928227",
    "updatedAt": 1745000000000
  }
  ```
  *Invariant:* `to < from` lexicographically (enforced at publish time), so
  alias chains are strictly acyclic and reach a unique fixpoint.
- **`put` body (reject):**
  ```json
  {
    "kind": "reject",
    "a": "dc:1111...",
    "b": "sha:2222...",
    "updatedAt": 1745000000000
  }
  ```
  *Invariant:* `a < b` lexicographically.
- **`del` semantics:** Not used (`merge` supersedes `reject` if the reader later
  explicitly merges a previously rejected pair).
- **Conflict rule:** Monotonic convergence (follow alias chains to minimum `to`;
  `merge` overrides `reject`). Confirmed merges form an equivalence relation:
  when two devices concurrently publish `merge:C` with different targets
  (`C→B` and `C→A`, `A < B < C`), the record keeps the minimum target and
  every device that sees both derives and publishes `merge:B` → `A` (losing
  target → winning target, unless both already reach the same fixpoint), so
  `A`, `B` and `C` converge to one work instead of leaving `B` stranded.
- **Room mapping (`liseur.db`):**
  - `work_alias`: `book_url`, `peer_id = drive:<permissionId>`, `work_id`,
    `confidence` (`high` / `low` / `rejected`), `confirmed`, `seeded`,
    `edition_sha`, `resolved_at`.
  - `work_ambiguity`: cleared once resolved.

---

### 4.2 `catalog` (Domain v1)

- **Purpose:** Whole-library catalog entries, edition SHA-256 registry, EPUB
  Drive availability, and archive state.
- **Wire key `k`:** `workKey`
- **`put` body:**
  ```json
  {
    "workKey": "isbn:9780547928227",
    "title": "The Hobbit",
    "author": "J.R.R. Tolkien",
    "identifiers": [
      { "scheme": "isbn", "value": "9780547928227" }
    ],
    "editions": [
      {
        "sha256": "a3b2c1...",
        "sizeBytes": 1234567,
        "driveFileId": "1AbCdEf...",
        "fileName": "The Hobbit.epub",
        "uploadedAt": 1745000000000
      }
    ],
    "fileSeriesName": "Middle-earth",
    "fileSeriesIndex": 1,
    "addedAt": 1745000000000,
    "archivedAt": null,
    "updatedAt": 1745000000000
  }
  ```
- **Field types & constraints:**
  - `workKey`: non-empty `String`
  - `title`: `String` (source title `books.stableTitle` — never reader's override)
  - `author`: `String?` (`books.stableAuthor`)
  - `identifiers`: `Array<{scheme: String, value: String}>` sorted by `(scheme, value)` from `book_metadata(source='file').identifiers`
  - `editions`: `Array<Edition>` sorted by `sha256` (may be `[]` for fileless
    preserved stubs with no retained `edition_sha`):
    - `sha256`: 64-hex lowercase `String` (`book_fingerprint.sha256`, or
      captured `work_alias.edition_sha` for a fileless preserved stub)
    - `sizeBytes`: `Long?` (`book_fingerprint.file_size` or `books.size_bytes`;
      `null` when unknown on a fileless preserved stub)
    - `driveFileId`: `String?` (populated **only after** EPUB upload finishes and SHA-256 is verified; cleared to `null` if Drive-side EPUB is trashed/deleted)
    - `fileName`: `String?` (display filename in `Liseur/`)
    - `uploadedAt`: `Long?` (epoch ms; `null` when `driveFileId == null`)
  - `fileSeriesName`: `String?` (`books.file_series_name`)
  - `fileSeriesIndex`: `Double?` (`books.file_series_index`)
  - `addedAt`: `Long` (epoch ms; `minOf` on merge)
  - `archivedAt`: `Long?` (epoch ms; `null` = on shelf; non-null = archived)
  - `updatedAt`: `Long` (epoch ms)
- **Merge / Conflict rule:**
  - Automatic merge (no prompt): `editions` union by `sha256` (preferring latest
    HLC's `driveFileId` per `sha256` so Drive-side EPUB deletion clears
    `driveFileId`); `identifiers` union; `addedAt = minOf`; `archivedAt` takes
    the higher HLC.
  - **`hidden_at` is excluded** (local-only).
- **`del` semantics:**
  - Published **only** by explicit **Delete from synced library** (never by
    **Remove download** or watched-folder removal).
  - Receiver behavior:
    - If local row is `drive:<workKey>` with `local_uri == null`: remove row via
      `BookRemoval` (**annotations are kept**).
    - If local row has a local file (`local_uri != null` or
      `download_state == DOWNLOADED`): keep local file and book entry, unlink
      from Drive (delete `work_alias` for `drive:<permissionId>` and clear Drive
      upload links).
  - Deleting an EPUB directly in Google Drive does **not** emit catalog `del`;
    it emits a catalog `put` with `driveFileId = null`, retaining catalog
    history, sessions, and annotations.
- **Room mapping (`liseur.db`):**
  - `books`: remote-only catalog row created with `url = "drive:$workKey"`,
    `download_state = DownloadState.REMOTE`, `local_uri = null`,
    **`remote_uuid = null`** (invariant: never set `remote_uuid`, so server
    `disconnect()` cannot touch Drive rows), `browse_server_id = null`,
    `title`, `author`, `file_title`, `file_author`, `file_series_name`,
    `file_series_index`, `series_name`, `series_index`, `size_bytes`,
    `added_at`, `archived_at`, `work_id`.
  - `book_fingerprint`: `book_url`, `sha256`, `partial_md5`, `file_size`.
  - `work_alias`: `book_url`, `peer_id = drive:<permissionId>`, `work_id =
    workKey`, `edition_sha`, `confidence = 'high'`, `confirmed = 1`.

---

### 4.3 `metadata` (Domain v1 — Metadata Overrides & Custom Covers)

- **Purpose:** Reader-authored overrides for `title`, `author`, `description`,
  and custom `cover` across the **entire library** (both downloaded books and
  `DownloadState.REMOTE` `drive:<workKey>` catalog rows).
- **Wire key `k`:** `"<workKey>/<field>"`, where `field ∈ {"title", "author",
  "description", "cover"}` (`MetadataOverrideFields.ALL_FIELDS`).
- **`put` body (text fields `title`, `author`, `description`):**
  ```json
  {
    "workKey": "isbn:9780547928227",
    "field": "title",
    "value": "The Hobbit (Illustrated)",
    "updatedAt": 1745000000000
  }
  ```
  - `value`: non-null `String` (clearing an override uses `op: "del"`).
  - `updatedAt`: `Long` (epoch ms, `metadata_override_revision.updated_at`).
- **`put` body (`cover` field):**
  ```json
  {
    "workKey": "isbn:9780547928227",
    "field": "cover",
    "coverSha256": "<64-hex of cover-<sha>.webp bytes>",
    "width": 800,
    "height": 1200,
    "sizeBytes": 148230,
    "updatedAt": 1745000000000
  }
  ```
  - Blob `cover-<coverSha256>.webp` MUST exist in `appDataFolder` before this
    record is published.
  - On receive, download `cover-<coverSha256>.webp` into app-private cover
    storage, verify SHA-256, and set `user_cover_path`, `user_cover_sha256`,
    `cover_override = 1` on the book row — even when `download_state == REMOTE`
    and `local_uri == null`.
- **`del` semantics:** Published when the reader resets the override flag back
  to `0` (`title_override = 0`, `author_override = 0`,
  `description_override = 0`, or `cover_override = 0`). Applying `del` clears
  the override columns and restores the effective value from file/catalog
  metadata.
- **Conflict rule:** Reader-resolved per `(workKey, field)` (`EDIT_EDIT` and
  `DELETE_EDIT`) via Section 3.1, closed with `op: "resolve"` (`outcome: "put"`
  or `"delete"`).
- **Room mapping (`liseur.db`):**
  - `books`: `user_title`, `title_override`, `title`, `user_author`,
    `author_override`, `author`, `user_description`, `description_override`,
    `user_cover_path`, `user_cover_sha256`, `cover_override`.
  - `metadata_override_revision`: `book_url`, `field`, `revision`,
    `retired_revision`, `updated_at`.
  - `metadata_override_sync`: `peer_id`, `book_url`, `field`, `work_id`,
    `acked_revision`, `sent_json` (adapter `{"vfp","cfp","rev"}`),
    `sent_revision`, `remote_cover_sha256`, `remote_cover_updated_at`,
    `adopted`.

---

### 4.4 `series` (Domain v1)

- **Purpose:** Reader-authored series membership and shelf ordering.
- **Wire key `k`:** `workKey`
- **`put` body:**
  ```json
  {
    "workKey": "isbn:9780547928227",
    "seriesOverridden": true,
    "userSeriesName": "Middle-earth",
    "indexOverridden": true,
    "userSeriesIndex": 1,
    "seriesKey": "middle earth",
    "updatedAt": 1745000000000
  }
  ```
- **Field types & null semantics:**
  - `seriesOverridden`: `Boolean` (`books.series_override`)
  - `userSeriesName`: `String?` (`books.user_series_name`; `null` when
    `seriesOverridden == true` means "reader explicitly filed under no series")
  - `indexOverridden`: `Boolean` (`books.series_index_override`)
  - `userSeriesIndex`: `Double?` (`books.user_series_index`; canonical number,
    `null` when unnumbered)
  - `seriesKey`: `String` (`com.chmouel.liseur.domain.seriesKey(effectiveName)`,
    used to group shelf conflicts in UI; excluded from conflict prompt wording
    if empty)
  - `updatedAt`: `Long` (epoch ms, `books.user_series_updated_at`)
- **`del` semantics:** Published when the reader resets both overrides
  (`series_override = 0 AND series_index_override = 0`) so the book reverts to
  its file/catalog series.
- **Conflict rule:** Reader-resolved via Section 3.1. In Settings UI, open
  `series` conflicts sharing the same `seriesKey` (or old/new `seriesKey` of a
  reordered shelf) are grouped into a single shelf-level choice ("Keep this
  device's order" vs. "Keep other device's order"), which emits one
  `op: "resolve"` record per affected `workKey`.
- **Room mapping (`liseur.db`):**
  - `books`: `user_series_name`, `user_series_index`, `series_override`,
    `series_index_override`, `user_series_updated_at`, `series_name`,
    `series_index`, `file_series_name`, `file_series_index`.
  - `SeriesOrderDao` (`renumber`, `clearOrder`).

---

### 4.5 `position` (Domain v1)

- **Purpose:** Per-device reading position, exact locator, reading status,
  explicit status overrides, and unread resets.
- **Wire key `k`:** `"<workKey>/<deviceId>"` (each device writes only its own
  key).
- **`put` body:**
  ```json
  {
    "workKey": "isbn:9780547928227",
    "deviceId": "550e8400-e29b-41d4-a716-446655440000",
    "editionSha": "a3b2c1...",
    "locatorJson": "{\"href\":\"ch04.xhtml\",\"locations\":{\"progression\":0.5,\"totalProgression\":0.425},\"type\":\"application/xhtml+xml\"}",
    "totalProgression": 0.425,
    "status": "Reading",
    "finishedOverride": "automatic",
    "coverage": null,
    "unreadResetAt": null,
    "readAt": 1745000000000,
    "updatedAt": 1745000000000
  }
  ```
- **Field types & enum values:**
  - `editionSha`: `String?` (64-hex `book_fingerprint.sha256` of the file where
    `locatorJson` was captured; `null` for status-only acts without a local file)
  - `locatorJson`: `String` (canonicalized Readium Locator JSON, or `"{}"` when
    no saved place)
  - `totalProgression`: `Double?` (`[0.0, 1.0]` or `null`)
  - `status`: `String` — `ReadingStatus.wireName` (`"ReadyToRead"`, `"Reading"`,
    `"Finished"`)
  - `finishedOverride`: `String` — `ReadingStatusChoice.wireName`
    (`"automatic"`, `"finished"`, `"unread"`, `"want_to_read"`, `"reading"`,
    `"paused"`, `"did_not_finish"`)
  - `coverage`: `String?` — `NarrativeCoverage.wireName` (`"full"`, `"partial"`,
    or `null`)
  - `unreadResetAt`: `Long?` (epoch ms when `reading_progress.unread_reset_revision
    > sync_peer_state.acked_revision`, else `null`)
  - `readAt`: `Long?` (epoch ms when reading occurred; `reading_progress.read_at
    ?: reading_progress.updated_at`)
  - `updatedAt`: `Long` (epoch ms)
- **Merge / Conflict rule:**
  - On pull from `<otherDeviceId>`, run `reconcileReadingState(...)` using local
    `reading_progress` and `sync_peer_state(book_url, drive:<permissionId>)`:
    - `InSync`: update baseline (`agreed_progression`, `agreed_status`).
    - `Pull`: apply `totalProgression`, `read_at`, `status`/`finished_override`,
      and `books.finished_at`. Apply `locatorJson` **only when**
      `editionSha != null && editionSha == localEditionSha`; otherwise update
      `totalProgression` while keeping/deriving the local edition's locator.
    - `AdoptStatus`: apply `finishedOverride` / `status` without overwriting the
      local locator.
    - `Push`: keep local state.
    - `Conflict`: call `SyncPeerStateDao.persistPending(...)` (and
      `persistPendingStatus(...)` for conflicting status acts) so Liseur's
      existing position-choice UI resolves the conflict on next book open.
  - Intentional backward moves are preserved because `sync_peer_state.agreed_*`
    tracks the last agreed baseline.
- **`del` semantics:** Not used ("Mark unread" is a `put` with
  `finishedOverride = "unread"` and non-null `unreadResetAt`).
- **Room mapping (`liseur.db`):**
  - `reading_progress`: `book_url`, `locator_json`, `total_progression`,
    `updated_at`, `read_at`, `status`, `local_revision`, `finished_override`,
    `status_revision`, `position_revision`, `unread_reset_revision`, `coverage`,
    `status_retired_revision`.
  - `sync_peer_state`: `book_url`, `peer_id = drive:<permissionId>`,
    `acked_revision`, `acked_status_revision`, `agreed_progression`,
    `agreed_status`, `pending_progression`, `pending_locator_json`,
    `pending_edition_sha`, `pending_status`, `pending_coverage`,
    `pending_updated_at`, `has_pending`, `has_pending_status`,
    `pending_status_choice`, `pending_status_updated_at`, `remote_updated_at`,
    `synced_at`.
  - `books.finished_at`.

---

### 4.6 `furthest` (Domain v1 — Peaks & Resets)

- **Purpose:** Track per-device furthest reading peaks and propagate explicit
  furthest-position resets ("Start furthest over from here").
- **Sub-record A — Peak (`k = "peak/<workKey>/<editionSha>/<deviceId>"`):**
  ```json
  {
    "kind": "peak",
    "workKey": "isbn:9780547928227",
    "editionSha": "a3b2c1...",
    "deviceId": "550e8400-e29b-41d4-a716-446655440000",
    "seq": 14,
    "progression": 0.812,
    "locatorJson": "{...}",
    "coverage": null,
    "peakAt": 1745000000000
  }
  ```
  - `seq`: `Long` (strictly monotonic per authoring `deviceId`, using the
    delta batch `seq` or `peak_revision`).
  - Merge rule: ignore if `seq <= maxFloor[workKey][deviceId]`; otherwise apply
    via `FurthestPositionDao.observe` (keeps max `progression` per
    `(peer_id, work_id, edition, origin = deviceId)`).
- **Sub-record B — Reset (`k = "reset/<workKey>/<resetId>"`, append-only):**
  ```json
  {
    "kind": "reset",
    "workKey": "isbn:9780547928227",
    "resetId": "c9a1...",
    "floors": {
      "550e8400-e29b-41d4-a716-446655440000": 14,
      "661f9511-f3ac-52e5-b827-557766551111": 9
    },
    "resetAt": 1745000000000
  }
  ```
  - `floors`: map of `originDeviceId -> floorSeq` observed at reset time.
  - Apply rule: for every `(originDeviceId, floorSeq)` in `floors`, delete
    `furthest_position` rows matching `(peer_id = drive:<permissionId>,
    work_id = workKey, origin = originDeviceId, seq <= floorSeq)` (requires new
    DAO query `FurthestPositionDao.deleteUpToForOrigin(peer, work, origin,
    floor)`), and persist `maxFloor[workKey][originDeviceId]` in `drive_sync.db`
    so late-arriving peaks with `seq <= floorSeq` are ignored. Resets are
    retained forever in snapshots.
- **`del` semantics:** Not used.
- **Room mapping (`liseur.db`):**
  - `furthest_position`: `peer_id = drive:<permissionId>`, `work_id = workKey`,
    `edition = editionSha`, `origin = deviceId`, `progression`, `seq`,
    `payload = JSON({locatorJson, coverage, peakAt})`.
  - `peak_delivery`: `peer_id`, `book_url`, `work_id`, `revision`,
    `acknowledged`.
  - `reading_progress`: `peak_progression`, `peak_locator`, `peak_revision`,
    `peak_at`, `peak_edition`, `peak_coverage`.

---

### 4.7 `annotation` (Domain v1)

- **Purpose:** Highlights, passage notes, anchorless book notes, and bookmarks.
- **Wire key `k`:** `annotations.id` (UUID `String`, `≤ 64` UTF-8 bytes).
- **`put` body:**
  ```json
  {
    "id": "5b1e...",
    "workKey": "isbn:9780547928227",
    "editionSha": "a3b2c1...",
    "kind": "HIGHLIGHT",
    "locatorJson": "{\"href\":\"ch01.xhtml\",\"locations\":{\"progression\":0.012,\"totalProgression\":0.012},\"text\":{\"highlight\":\"In a hole in the ground\"},\"type\":\"application/xhtml+xml\"}",
    "text": "In a hole in the ground there lived a hobbit.",
    "note": "Classic opening",
    "tint": "YELLOW",
    "chapter": "Chapter 1",
    "totalProgression": 0.012,
    "createdAt": 1745000000000,
    "noteCreatedAt": 1745000001000,
    "noteUpdatedAt": 1745000002000000,
    "updatedAt": 1745000002000000
  }
  ```
- **Field types, units & constraints (verified against `BookAnnotation.kt`):**
  - `kind`: `String` — exact `AnnotationKind.name`: `"HIGHLIGHT"`, `"NOTE"`,
    `"BOOK_NOTE"`, or `"BOOKMARK"`.
  - `locatorJson`: `String` — canonicalized JSON string; `""` ONLY for
    `BOOK_NOTE`.
  - `text`: `String?` (passage excerpt; `null` for `BOOK_NOTE` / `BOOKMARK`).
  - `note`: `String?` (reader's note text).
  - `tint`: `String?` — exact `HighlightTint.name` (`"YELLOW"`, `"GREEN"`,
    `"BLUE"`, `"PINK"`, `"PURPLE"`, `"ORANGE"`) or `null`.
  - `chapter`: `String?`.
  - `totalProgression`: `Double?` (`[0.0, 1.0]` or `null`).
  - `createdAt`: `Long` — epoch **milliseconds** (`annotations.created_at`).
  - `noteCreatedAt`: `Long?` — epoch **milliseconds**
    (`annotations.note_created_at`).
  - `noteUpdatedAt`: `Long?` — epoch **microseconds**
    (`annotations.note_updated_at`).
  - `updatedAt`: `Long` — epoch **microseconds** (`annotations.updated_at`).
  - `editionSha`: `String?` — anchor edition hint on the wire; **excluded from
    `contentFp`** so two devices with different editions of the same work never
    churn fingerprints.
  - `annotations.position` (rendered page number): **excluded from wire and
    `contentFp`** (per `BookAnnotation.kt:132`).
- **`contentFp` input:** Canonical JSON of `body` **minus** `editionSha`.
- **`del` semantics:** When the reader deletes a mark, `annotations` loses the
  row while `annotation_sync(id, drive:<permissionId>)` remains; Drive emits
  `op: "del"` with `anc = [prevFp, ...]` and sets
  `annotation_sync.acked_fingerprint = "DELETED"`. Book removal via
  `BookRemoval.forget` does NOT emit annotation `del`s.
- **Conflict rule:** Reader-resolved via Section 3.1 (`EDIT_EDIT` keeps local
  in `annotations` and remote in `drive_conflict.remote_json`; `DELETE_EDIT`
  prompts **Keep edited note** / **Delete note**). Closed via `op: "resolve"`
  (`outcome: "put"` or `"delete"`).
- **Room mapping (`liseur.db`):**
  - `annotations`: `id`, `book_id` (resolved from `work_alias` or fallback
    `"drive:$workKey"`), `kind`, `locator_json`, `text`, `note`, `tint`,
    `chapter`, `total_progression`, `created_at`, `note_created_at`,
    `note_updated_at`, `updated_at` (`position` stays local `NULL` until
    computed on open).
  - `annotation_sync`: `id`, `peer_id = drive:<permissionId>`, `book_id`,
    `work_id = workKey`, `acked_fingerprint`.

---

### 4.8 `evaluation` (Domain v1)

- **Purpose:** Personal star rating and book review.
- **Wire key `k`:** `"<workKey>/<field>"`, where `field ∈ {"rating", "review"}`
  (`PersonalEvaluation.RATING`, `PersonalEvaluation.REVIEW`).
- **`put` body:**
  ```json
  {
    "workKey": "isbn:9780547928227",
    "field": "rating",
    "value": "9",
    "updatedAt": 1745000000000
  }
  ```
- **Field types & validation (`PersonalEvaluation.valid`):**
  - `field`: `"rating"` or `"review"`.
  - `value`: non-null `String`:
    - For `rating`: integer string `"2".."10"` (1.0–5.0 stars in half-star
      steps).
    - For `review`: non-blank, no `\u0000`, `≤ 8192` UTF-8 bytes.
  - `updatedAt`: `Long` (epoch ms, `book_evaluation.updated_at`).
  - `book_evaluation.title` and `author` are local display snapshots, excluded
    from wire and `contentFp` (populated from `books` / catalog on apply).
- **`del` semantics:** Clearing a rating or review locally sets
  `book_evaluation.value = null` and increments `revision`; Drive publishes
  `op: "del"`. Applying remote `del` sets `book_evaluation.value = null` and
  atomically updates `evaluation_sync.acked_revision` + `sent_json`.
- **Conflict rule:** Reader-resolved per `(workKey, field)` via Section 3.1
  (`EDIT_EDIT` and `DELETE_EDIT`), closed via `op: "resolve"`.
- **Room mapping (`liseur.db`):**
  - `book_evaluation`: `book_url`, `field`, `value`, `title`, `author`,
    `revision`, `retired_revision`, `updated_at`.
  - `evaluation_sync`: `peer_id = drive:<permissionId>`, `book_url`, `field`,
    `work_id = workKey`, `acked_revision`, `sent_json` (adapter
    `{"vfp","cfp","rev"}`), `sent_revision`.
  - `retained_evaluation`: preserved across book removals.

---

### 4.9 `vocabWord` (Domain v1)

- **Purpose:** Vocabulary words, definitions, notes, and legacy SRS baselines.
- **Wire key `k`:** `"<language.length>:<language>:<normalized>"` (length-prefixed
  so colons in language tags cannot collide).
- **`put` body:**
  ```json
  {
    "language": "fr",
    "normalized": "ephemere",
    "word": "éphémère",
    "definition": "short-lived",
    "translationLanguage": "en",
    "notes": "From chapter 3",
    "definitionEdited": true,
    "definitionUpdatedAt": 1745000000000,
    "notesUpdatedAt": 1745000001000,
    "addedAt": 1745000000000,
    "baseline": {
      "dueAt": 1745086400000,
      "reviewedAt": 1745000000000,
      "streak": 2,
      "intervalMinutes": 1440,
      "repetitions": 2
    }
  }
  ```
- **Field types & `VocabularyMerge.reconcileSchedule` baseline contract:**
  - `language`, `normalized`, `word`: non-empty `String`.
  - `definition`, `translationLanguage`: `String?`.
  - `notes`: `String` (default `""`).
  - `definitionEdited`: `Boolean`.
  - `definitionUpdatedAt`, `notesUpdatedAt`, `addedAt`: `Long` (epoch ms).
  - **`baseline`:** `Object?` — populated from `VocabularyWord.baselineOrNull()`
    (`null` when `baselineDueAt == null || baselineRepetitions <= 0`; otherwise
    `{dueAt: Long, reviewedAt: Long?, streak: Int, intervalMinutes: Int,
    repetitions: Int}`).
  - **Excluded live SRS fields:** `dueAt`, `reviewedAt`, `streak`,
    `intervalMinutes`, `repetitions`, `searchText`, and `ownerAccount` are
    **NEVER** placed on the wire.
  - **Schedule derivation invariant:** After every word merge, baseline merge, or
    review add/undo, compute the live schedule deterministically via:
    ```kotlin
    val reconciled = VocabularyMerge.reconcileSchedule(
        addedAt = mergedWord.addedAt,
        baseline = mergedWord.baselineOrNull(), // merged via VocabularyMerge.mergeBaseline
        reviews = liveReviewsForWord,           // sorted by (reviewedAt, syncId)
    )
    ```
    Write `reconciled.{dueAt, reviewedAt, streak, intervalMinutes, repetitions}`
    to `vocabulary_words` and update `vocabulary_reviews.firstReview =
    (syncId == reconciled.firstReviewSyncId)`. Because `baseline` is synced and
    merged via `VocabularyMerge.mergeBaseline`, pre-event-log migrated schedules
    and live event-logged reviews produce byte-identical schedules on every
    device.
- **Merge / Delete rule:**
  - Concurrent `put` vs. `put`: automatic three-way merge using
    `VocabularyMerge.mergeWordMetadata(base, local, remote)` (with `base` from
    `vocabulary_word_sync.acked_base_json`); republish if merged differs from
    incoming.
  - `del` on untouched local word (`L ∈ R.anc`): silent delete of local word
    (cascading occurrences and reviews) and record
    `vocabulary_word_sync.acked_fingerprint = "DELETED"` — but only when the
    causal review boundary proves no live review is uncovered (next bullet).
  - **Causal review boundary (`observed` vector):** a `vocabWord` `del` records
    the sorted, deduplicated `syncId` list of every review the deleting device
    had observed (`observed`, §2.3) alongside `anc`. Because reviews are
    append-only and `vocabulary_review_sync` is not part of `anc`, `anc(L)`
    alone cannot prove the deleter's word version descends from the current
    review set. Before any cascade delete of `vocabulary_reviews` (or their
    `vocabulary_occurrences`), the receiver computes the live local reviews for
    the word and requires every one of them to be covered (`syncId ∈ R.observed`,
    with legacy reviews first normalized to §4.11's deterministic review IDs
    before `observed` is built or checked, or a review restored from a snapshot part whose provenance `seq` the deleting
    device had already applied — §2.3). If any live review is uncovered, the delete is a concurrent
    review: **add-wins silently** — keep the word and its reviews, and
    republish a fresh `put` carrying the `del` fp in `anc`.
  - `del` vs. concurrent edit or concurrent live review: **add-wins silently** —
    keep the local word and live reviews, and republish a fresh `put` with the
    `del` fp in `anc`.
  - `ownerAccount`: Drive reads the visible vocabulary set (matching
    `VocabularyDao.exportWords()`, including `ownerAccount = ''` and retired
    `liseur-sync` rows), deduplicated by `(language, normalized)`. Newly pulled
    words insert with `ownerAccount = ''`.
- **Room mapping (`liseur.db`):**
  - `vocabulary_words`: `word`, `normalized`, `language`, `definition`,
    `translationLanguage`, `notes`, `addedAt`, `definitionEdited`,
    `definitionUpdatedAt`, `notesUpdatedAt`, `baselineDueAt`,
    `baselineReviewedAt`, `baselineStreak`, `baselineIntervalMinutes`,
    `baselineRepetitions`, plus derived `dueAt`, `reviewedAt`, `streak`,
    `intervalMinutes`, `repetitions`, `searchText`, `ownerAccount`.
  - `vocabulary_word_sync`: `peer_id = drive:<permissionId>`, `language`,
    `normalized`, `synced_word_id`, `lifecycle`, `acked_fingerprint`,
    `acked_base_json`.

---

### 4.10 `vocabOccurrence` (Domain v1)

- **Purpose:** Context sentences and book locations where a vocabulary word was
  encountered.
- **Wire key `k`:** `"<wordKey>/<occurrenceKey>"`, where `occurrenceKey` is
  `VocabularyMerge.canonicalOccurrenceKey(workKey, locationKey)` whenever
  `workKey != null` (computed at publish time), or
  `VocabularyMerge.provisionalOccurrenceKey(bookUrl, bookTitle, locationKey)`
  if the book has no `workKey`.
- **`put` body:**
  ```json
  {
    "wordKey": "2:fr:ephemere",
    "occurrenceKey": "<base64url-sha256>",
    "workKey": "isbn:9780547928227",
    "bookTitle": "Le Petit Prince",
    "locationKey": "ch03#p12",
    "locator": "{\"href\":\"ch03.xhtml\",\"locations\":{\"progression\":0.25}}",
    "sentence": "Cette fleur est éphémère.",
    "sentenceEdited": false,
    "sentenceUpdatedAt": 1745000000000,
    "lastSeenAt": 1745000000000,
    "encounters": 2
  }
  ```
- **Field types:** `locationKey` normalized via
  `VocabularyMerge.wireLocationKey` (`≤ 128` UTF-8 bytes or SHA-256 base64url);
  `workKey`, `locator`, `sentence` nullable `String?`; `sentenceEdited`
  `Boolean`; `sentenceUpdatedAt`, `lastSeenAt` `Long` (epoch ms); `encounters`
  `Int` (`≥ 1`).
- **Merge / Delete rule:** Automatic merge via
  `VocabularyMerge.mergeOccurrencePair` and provisional/canonical deduplication
  via `VocabularyMerge.mergeOccurrences`. Deleted by cascade when parent
  `vocabWord` is deleted.
- **Room mapping (`liseur.db`):**
  - `vocabulary_occurrences`: `wordId`, `bookUrl` (resolved via `work_alias` or
    `"drive:$workKey"`), `bookTitle`, `locationKey`, `locator`, `sentence`,
    `lastSeenAt`, `encounters`, `searchSentence` (derived locally), `workId =
    workKey`, `occurrenceKey`, `sentenceEdited`, `sentenceUpdatedAt`.

---

### 4.11 `vocabReview` (Domain v1)

- **Purpose:** Immutable vocabulary review events and final review undos.
- **Wire key `k`:** `syncId` (UUID `String`). For legacy rows where
  `vocabulary_reviews.syncId IS NULL`, generate and persist once before
  publishing:
  `UUID.nameUUIDFromBytes("vocab-review|v1|$language|$normalized|$reviewedAt|$rating".toByteArray(UTF_8)).toString()`.
- **`put` body:**
  ```json
  {
    "syncId": "a1b2c3d4-...",
    "wordKey": "2:fr:ephemere",
    "language": "fr",
    "normalized": "ephemere",
    "rating": "GOOD",
    "reviewedAt": 1745000000000,
    "intervalMinutes": 1440,
    "dueAt": 1745086400000
  }
  ```
  - `rating`: `Recall.name` (`"AGAIN"`, `"HARD"`, `"GOOD"`, `"EASY"`).
  - `reviewedAt`, `dueAt`: `Long` (epoch ms); `intervalMinutes`: `Int`.
  - `firstReview` is **excluded** from the wire and derived locally by
    `VocabularyMerge.reconcileSchedule`.
- **Merge / `del` rule:**
  - Append-only set deduplicated by `syncId`. Replaying a review `put` is a
    no-op and can never advance the schedule twice.
  - Reviews arriving before their parent `vocabWord` are staged in
    `vocabulary_staged_review` and attached when the word arrives.
  - `op: "del"` (undo review): **final tombstone**. Deletes the matching
    `vocabulary_reviews` row and any `vocabulary_staged_review` row, sets
    `vocabulary_review_sync.acked_deleted = true`, and re-runs
    `reconcileSchedule` on the parent word. Any subsequent replay of the undone
    `syncId`'s `put` is ignored permanently.
- **Room mapping (`liseur.db`):**
  - `vocabulary_reviews`: `wordId`, `rating`, `reviewedAt`, `intervalMinutes`,
    `dueAt`, `firstReview` (derived), `syncId`.
  - `vocabulary_review_sync`: `peer_id = drive:<permissionId>`, `review_id =
    syncId`, `local_review_id`, `language`, `normalized`, `acked_deleted`.
  - `vocabulary_staged_review`: staging for out-of-order arrivals.

---

### 4.12 `session` (Domain v1)

- **Purpose:** Closed reading sittings for local cross-device statistics.
- **Wire key `k`:** `remote_session_id` (UUID `String`).
  - Generated **once if `NULL`** and persisted in `reading_sessions.remote_session_id`:
    `UUID.nameUUIDFromBytes("liseur-drive-session|v1|$workKey|$startedAt|$endedAt|$durationMs|${canonDouble(startProgression)}|${canonDouble(endProgression)}".toByteArray(UTF_8)).toString()`.
- **`put` body:**
  ```json
  {
    "sessionId": "f47ac10b-...",
    "workKey": "isbn:9780547928227",
    "startedAt": 1745000000000,
    "endedAt": 1745001800000,
    "durationMs": 1750000,
    "idleMs": 50000,
    "startProgression": 0.1,
    "endProgression": 0.185,
    "originDeviceId": "550e8400-e29b-41d4-a716-446655440000"
  }
  ```
- **Field types:**
  - `startedAt`, `endedAt`, `durationMs`, `idleMs`: `Long` (epoch ms / ms;
    `idleMs = session.idleMs ?: 0L`).
  - `startProgression`, `endProgression`: `Double` (`[0.0, 1.0]`, canonical
    number format).
  - `originDeviceId`: `String` (authoring device's `deviceId`).
- **Upload eligibility & multi-account invariant:**
  - Eligible local sessions for account `permissionId` are rows in
    `reading_sessions` matching ALL of:
    1. `ended_at IS NOT NULL`
    2. `imported_at IS NULL`
    3. `origin_device_id IS NULL` (excludes all Drive-pulled sessions)
    4. `start_progression IS NOT NULL AND end_progression IS NOT NULL`
    5. Book has a usable `work_alias` for `drive:<permissionId>` (for
       preserved fileless stubs on Server → Drive switch, captured before
       `forgetSyncPeer(server)` per Section 1.1 so their eligible sessions seed
       Drive cleanly)
    6. No delivery acknowledgement exists in `drive_sync.db`
       (`drive_session_ack(permission_id, remote_session_id)`).
  - **NEVER filter by `remote_session_id IS NULL`:** `remote_session_id` is a
    global sitting identity generated once when absent, while upload delivery is
    per-account in `drive_session_ack`. Switching to Account B or restoring
    `liseur.db` without `drive_sync.db` therefore seeds all eligible local
    sessions cleanly.
- **Apply & deduplication rule:**
  - Append-only; `del` is not used in v1.
  - On pull: `INSERT OR IGNORE INTO reading_sessions (book_url, started_at,
    ended_at, last_checkpoint_at, duration_ms, start_progression,
    end_progression, idle_ms, uploaded_at, legacy_evidence_unknown,
    imported_at, remote_session_id, origin_device_id) VALUES (:bookUrl,
    :startedAt, :endedAt, :endedAt, :durationMs, :startProg, :endProg,
    :idleMs, NULL, 0, NULL, :sessionId, :originDeviceId)`.
  - Guarded by `UNIQUE INDEX index_reading_sessions_remote_session_id` on
    `reading_sessions(remote_session_id)`.
  - `ReadingSessionDao.awaitingUpload` and `awaitingUploadTo` add
    `AND origin_device_id IS NULL` so `liseur-sync` never uploads Drive-pulled
    rows.
  - Cross-device statistics read directly from `reading_sessions` (`observeAll`,
    `observeTotalDuration`), combining local and Drive-pulled sessions with zero
    double counting.
- **Room mapping (`liseur.db` Migration 84→85 + `drive_sync.db`):**
  - `reading_sessions`: adds `remote_session_id TEXT NULL` (with `UNIQUE INDEX`)
    and `origin_device_id TEXT NULL`.
  - `drive_session_ack` (`drive_sync.db`): `(permission_id, remote_session_id,
    batch_seq, acked_at)`.

---

## 5. Two-Device Acceptance Test Matrix

### 5.1 Per-Domain Two-Device Matrix

Every in-scope domain must pass its two-device tests before its phase gate
closes.

| Domain | Happy Path (A → B) | Concurrent Edit / Merge | Delete / Tombstone | Replay / Edge Case |
|---|---|---|---|---|
| `workAlias` | Same book with ISBN on A and title+author match confirmed on B merges under `min(a,b)` and shares notes/position. | Concurrent merges `C→B` and `B→A` converge to fixpoint `A` without cycles; concurrent `C→B` and `C→A` also derive `B→A` so all three converge to `A`; `reject` suppresses prompt on B. | N/A (`merge` supersedes `reject`). | Two different EPUBs sharing an erroneous ISBN with different title+author stay separable when unmerged (the later one is keyed by `sha:`); late stronger identifier emits alias without rekeying existing rows. |
| `catalog` + EPUBs | A connects, syncs catalog entry for whole library; selects 1 book in **Upload EPUB files** → B sees whole catalog (`REMOTE`), downloads selected EPUB on open and verifies SHA-256. | Concurrent `archived_at` toggle resolves silently to higher HLC; concurrent edition uploads union cleanly in `editions[]`. | **Remove download** frees local file only; **Delete from synced library** removes `drive:` row on B (keeping annotations) and unlinks local-file copy on C; Drive-side EPUB trash clears `driveFileId` while keeping history. | `hidden_at` stays local; `drive:` row never sets `remote_uuid`; concurrent first connection creating two `Liseur` folders picks lowest `(createdTime, fileId)` and reads EPUBs from both. |
| `metadata` + Custom Covers | A overrides title, author, description, and sets custom cover → B applies all 4 overrides, including displaying the custom cover on a `REMOTE` catalog entry with no local EPUB. | Both A and B edit `title` offline and upload before pulling → both open per-field `EDIT_EDIT`, reader resolves on A, B closes conflict and converges. | A resets `author` override while B untouched → silent reset on B; A resets while B edits → `DELETE_EDIT` prompt (**Keep** / **Delete**). | Cover > 1200 px / > 512 KiB scales down via WebP ladder; uncompressable cover refused gracefully; crash between `liseur.db` and `drive_sync.db` recovers via `MetadataOverrideSync` revision-to-fp adapter. |
| `series` | A drags 5-book shelf into custom order → B displays identical 1…5 order. | A and B reorder the same 5-book shelf offline → grouped into **one** shelf conflict by `seriesKey`; resolving emits per-book `op:"resolve"` and converges all 5 books. | A clears series order while B untouched → B reverts to file/catalog series; concurrent clear + reorder → `DELETE_EDIT` shelf prompt. | Book explicitly filed under "no series" (`seriesOverridden=true, userSeriesName=null`) stays un-shelved on B despite file metadata. |
| `position` | A reads to 42%, B pulls exact locator (matching edition) and opens at exact passage. | A reads forward to 60%, B reads to 55% offline → `SyncDecision.Conflict` persists in `sync_peer_state` and triggers existing position-choice UI; intentional backward reread on A from agreed 50% → 20% pulls cleanly onto untouched B. | "Mark unread" on A emits `finishedOverride="unread"` + `unreadResetAt`, resetting untouched B and outranking stale `Finished`. | Different editions of same work apply `totalProgression` without corrupting local `locatorJson`; skewed device clocks cannot overwrite deliberate rereads. |
| `furthest` | A reads to 80%, turns back to 20% → B sees 80% peak in `furthest_position`. | Both A and B record peaks on different editions → both peaks retained per `(edition, origin=deviceId)`. | A triggers furthest reset (`floors:{devA:14, devB:9}`) → deletes peaks `≤ floor` on B; late peak from B with `seq ≤ 9` is dropped; new peak from B with `seq = 10` survives. | Replaying a reset snapshot is idempotent; `FurthestPositionCaptureTest` passes with Drive peer. |
| `annotation` | A creates highlight, passage note, book note, and bookmark → B receives all 4 with identical IDs, anchors, tints, and microsecond/millisecond timestamps. | Simultaneous-upload `EDIT_EDIT` on same note → both preserved, reader chooses on A, `op:"resolve"(outcome:"put")` closes conflict on B; `position` (page number) and `editionSha` never cause false conflicts. | Stale untouched B receives A's `del` → silent delete; concurrent delete on A + edit on B → `DELETE_EDIT` (**Keep edited note** / **Delete note**); choosing **Delete note** emits `op:"resolve"(outcome:"delete")` and closes conflict on both devices. | Book removal via `BookRemoval.forget` emits zero annotation tombstones; delete → keep-via-resolution → delete again produces a distinct `delFp` that is not ignored as stale. |
| `evaluation` | A sets 4.5-star rating (`"9"`) and review → B displays identical rating and review. | Both A and B edit `review` offline and upload → per-field `EDIT_EDIT`; resolving propagates via `op:"resolve"`. | Clearing rating on A emits `op:"del"` and clears untouched B; concurrent clear + edit prompts `DELETE_EDIT`. | Crash between `book_evaluation`/`evaluation_sync` commit and `drive_sync.db` commit recovers via revision-to-fp adapter (both unedited and followed by immediate local edit). |
| `vocabWord` + `vocabOccurrence` | A collects word with definition, notes, legacy `baseline`, and book sentence → B receives word, occurrence, and identical derived SRS schedule. | Concurrent edits to definition/notes/sentence merge automatically via `VocabularyMerge.mergeWordMetadata` / `mergeOccurrencePair`; provisional + canonical occurrences deduplicate. | Word `del` on untouched B deletes silently; word `del` concurrent with edit or live review on B → add-wins silently and republishes. | Words with retired `liseur-sync` `ownerAccount != ''` publish and merge by `(language, normalized)`; `baseline` merges via `mergeBaseline` so pre-event-log schedules match exactly. |
| `vocabReview` | A reviews word (`GOOD`), B pulls review and computes identical `dueAt`, `streak`, `intervalMinutes`, `repetitions`, and `firstReview`. | Concurrent reviews on A and B union by `syncId` and replay in `(reviewedAt, syncId)` order via `reconcileSchedule`. | Undo review on A emits `op:"del"` (final tombstone) → B deletes review, recomputes schedule, and ignores any later replay of that `syncId`. | Review arriving before parent `vocabWord` stages in `vocabulary_staged_review` and attaches when word arrives; duplicate replay never double-advances schedule. |
| `session` | A finishes 30-min reading session → B pulls row with `remote_session_id` + `origin_device_id` and displays combined cross-device stats. | Concurrent sessions on A and B both appear in `reading_sessions` with distinct `remote_session_id`s; combined duration equals exact sum. | N/A (append-only in v1). | Duplicate/reordered replay inserts 0 rows; Drive-pulled rows are excluded from `liseur-sync` (`origin_device_id IS NULL`) and never republished; switching to Account B seeds all local sessions via missing `drive_session_ack` despite existing `remote_session_id`. |

### 5.2 Mandatory Deterministic Resilience Test Suite (Phase 6)

All scenarios below run deterministically in-JVM with a fake in-memory Drive
transport, controllable clocks, and fault-injection hooks:

1. **Simultaneous-upload `EDIT_EDIT` repro (P1 #1):**
   - Devices A and B start converged on annotation `f0`.
   - Both go offline; A edits to `fA` and B edits to `fB`.
   - **Both upload their delta batches to Drive before either pulls** (so both
     advance their delivery acks: `published_fp == L`).
   - Both then pull each other's delta.
   - **Assert:** Neither device fast-forwards or overwrites silently. Both A and
     B open an `EDIT_EDIT` conflict preserving `{fA, fB}`. Resolving on A emits
     `op: "resolve"` (`outcome: "put"`), which closes the conflict on B and
     converges both devices identically.
2. **Delete-vs-edit vs. stale delete, delete-resolution wire tag, and competing
   resolutions vs. subsequent edits (P1 #3 & Round 2 P1 #2):**
   - *Stale delete:* A and B share `f0`. A deletes (`op: "del"`, `anc: [f0]`);
     B is untouched (`L = f0`). B pulls → silent delete, no conflict prompt.
   - *Delete-vs-edit:* A and B share `f0`. A deletes (`delFp`) while B edits to
     `fB` (`anc: [f0]`). Both upload, then pull → `DELETE_EDIT` opens on both
     with **Keep edited note** / **Delete note**.
   - Choosing **Delete note** on A emits `op: "resolve", outcome: "delete",
     resolvedFps: [delFp, fB], body: null`. B pulls → closes conflict as
     `RESOLVED` and deletes local row.
   - *Competing offline resolutions (both current versions are `op: "resolve"`):*
     A chooses **Keep edited note** at `hA` while B chooses **Delete note** at
     `hB > hA`. Both pull while still at their respective `op: "resolve"`
     versions → both converge silently to B's delete outcome (higher HLC wins)
     with no second prompt.
   - *Competing resolution vs. subsequent edit (`resA → fNew` against higher-HLC
     `resB`):* A and B both hold conflict `{fA, fB}`. A resolves as `resA` and
     then edits the chosen body to `fNew` (`L = fNew`, `version_op = "put"`). B
     independently resolves `{fA, fB}` as `resB` with `resB.hlc > resA.hlc`. A
     pulls `resB` (and B pulls `fNew`). **Assert:** A does **not** run silent
     competing-resolution arbitration against historical `resA` and does **not**
     silently overwrite `fNew`; A opens a conflict preserving both `fNew` and
     `resB.outcome` (and B likewise preserves both). A's `res_refs(fNew)` does
     **not** gain `resB.fp`, so a further edit `fNew2` made while that conflict
     is open does not carry `resB.fp` in its wire `resRefs`. A third device C
     that has ingested the durable `resB` fact but still holds one of the
     originally conflicting versions (`L = fB ∈ resB.resolvedFps`) receives
     `fNew2` and opens a conflict instead of taking the resolution-bridged
     supersession through `resB`.
3. **Returning offline device after >64 successor edits and multiple compactions,
   plus independent-branch / skewed-clock repro (P1 #4 & Round 2 P1 #1):**
   - *Legitimate >64-successor compaction bridge:* A, B, and C share `f0`. A
     edits to `fA`, B edits to `fB`; C pulls both so C holds open conflict
     `{fA, fB}` (and local `L = fB`), then C goes offline. A resolves
     `{fA, fB}` via `op: "resolve"` (`res1`), then performs **70** subsequent
     edits (`f1..f70`, each carrying `res1.fp` in its uncapped `resRefs`),
     triggering **≥ 3 compactions** (pruning `S_0`, `S_1`, and all deltas
     `seq ≤ T_{k-1}` so neither `fA`, `fB`, nor `res1` appears in `f70.anc`).
     C reconnects, downloads A's latest snapshot (ingesting `snapshot.resolutions`
     before `snapshot.records`), and applies it. **Assert:** C verifies
     `res1.fp ∈ f70.resRefs`, closes open conflict `{fA, fB}` as `RESOLVED`,
     fast-forwards cleanly to `f70`, and has zero open conflicts.
   - *Compacted-successor crash-between-transactions repro (unedited & local-edit
     variants):* In the 70-successor scenario above, C applies `f70` (`op:
     "put"`, `res1.fp ∈ f70.resRefs`) through the bridge, commits `f70`'s body
     and per-peer ack to `liseur.db` (Tx 1), and **crashes before committing
     `drive_sync.db` (Tx 2)** so `{fA, fB}` remains `OPEN`. Test both restart
     variants: (a) *unedited replay* (annotation/override recovery routes `f70`
     to `Agreement`), and (b) *immediate post-crash local edit before replay*
     (routes `f70` to `Stale`). **Assert in both variants:** Step 0's
     `resRefs`-bridge settlement runs before `Agreement`/`Stale`, closes
     `{fA, fB}` as `RESOLVED` with `resolution_fp = res1.fp`, preserves any
     post-crash local edit, leaves zero open conflicts, and advances
     `drive_cursor` only after Tx 2 commits.
   - *Independent-branch / skewed-clock repro (must NOT bridge):* C remains on
     open conflict `{fA, fB}` (`L = fB`). A resolves `{fA, fB}` via `res1`.
     Device D never sees `res1` and independently edits `fB → fD` with a skewed
     higher clock `fD.hlc > res1.hlc` (so `res1.fp ∉ fD.resRefs`). C ingests
     A's durable resolution fact `res1` into `drive_resolution`, then receives
     D's `fD`. **Assert:** C does **not** fast-forward to `fD` or close C's
     conflict (neither via resolution bridge, because `res1.fp ∉ fD.resRefs`,
     nor via `fB ∈ fD.anc`, because `fB` is in an open/retired conflict); `fD`
     is handled as concurrent divergence preserving both branches without
     silently discarding A's resolution or closing C's conflict.
4. **Crash between `liseur.db` and `drive_sync.db` commits (P1 #2, P1 #3 &
   Round 2 P1 #3):**
   - *Session pull crash:* Kill after committing incoming session to
     `reading_sessions` and before advancing `drive_cursor`. Restart → batch
     replays, `INSERT OR IGNORE` inserts 0 rows, session counts **exactly once**.
   - *Local conflict resolution crash (`RESOLVING`):* Kill after committing
     local choice (`outcome: "put"` or `"delete"`) to `liseur.db` and before
     updating `drive_conflict` / `drive_resolution`. Restart → `RESOLVING`
     recovery ensures the `op: "resolve"` batch is published, persists
     `drive_resolution`, and marks `RESOLVED`.
   - *Incoming `op: "resolve"` crash between transactions (`OPEN` conflict):* B
     holds an `OPEN` conflict `{fA, fB}`. B receives A's `op: "resolve"`
     (`res1`, tested for both `outcome: "put"` and `outcome: "delete"`), commits
     the chosen body/deletion and per-peer ack (`acked_fingerprint` or
     `acked_revision` + `sent_json`) to `liseur.db` (Tx 1), then **crashes
     before committing `drive_sync.db` (Tx 2)** so `drive_conflict` remains
     `OPEN` and `drive_resolution` lacks `res1`. Test both variants on restart:
     (a) *unedited replay* (hits `Agreement`), and (b) *immediate local edit
     before replay* (hits `Stale`). **Assert in both variants:** Step 0 runs
     before Agreement/Stale, persists `res1` in `drive_resolution`, closes the
     `OPEN` conflict in `drive_conflict` as `RESOLVED`, updates `res_refs_json`,
     preserves any post-crash local edit, and advances `drive_cursor` only
     after Tx 2 commits.
   - *`EvaluationSync` / `MetadataOverrideSync` crash (unedited):* Kill after
     committing remote apply (with `acked_revision` + `sent_json`) to
     `liseur.db` and before updating `drive_record_state` / `drive_cursor`.
     Restart → revision-to-fp adapter recomputes `contentFp` from committed
     `liseur.db` row, repairs `drive_record_state`, and advances cursor.
   - *`EvaluationSync` / `MetadataOverrideSync` crash (followed by immediate
     local edit before replay):* Reader edits the field locally after restart
     before the un-advanced batch replays. Replay sees `R.fp ∈ anc(L)` (seeded
     from atomic `sent_json.vfp`), preserves the reader's new edit, and
     publishes it with `R.fp` in `anc`.
5. **Google account switch (`A → B → A`), pre-write consent, and session
   seeding (P1 #5 & #6):**
   - Sync books, annotations, and reading sessions to Account A (verifying
     `remote_session_id` is populated and `drive_session_ack` has rows for A).
   - Initiate switch to Account B → assert **zero Drive write HTTP calls** to B
     prior to user confirmation; verify confirmation receives exact local counts
     of books, notes, and sessions for B.
   - Confirm switch → assert Account A stays `PAUSED` with all cursors/queues
     intact; assert B presents a fresh empty **Upload EPUB files** selection;
     assert whole-library reading data and **all** previously synced local
     sessions upload to B (selected by missing `drive_session_ack(permB)`).
   - Switch back to Account A → assert A resumes cleanly from saved cursors
     without duplicating sessions or reopening resolved conflicts.
6. **Server → Drive switch preservation, fileless identity capture, and Drive
   seeding (P2 #8 & Round 2 P1 #4, #5):**
   - Start on Device A with main `RemoteServer` (`id = 1`), one browse-only
     server (`id = 2`), and five main-server books:
     1. a downloaded book with reading sessions;
     2. a genuinely untouched undownloaded preview stub (zero reading history,
        zero metadata/cover overrides, zero manual series choices, and
        `archived_at`, `finished_at`, `last_opened_at`, `hidden_at` all `NULL`);
     3. a previously downloaded book whose download was removed via
        `BookRemoval.downloadRemoved()` (`local_uri = NULL`, `book_fingerprint`
        deleted, `file_metadata_checked = 0`) that still carries reading
        sessions and annotations (with identity evidence in retiring server
        `work_alias.edition_sha` / `book_metadata` / `books.work_id`);
     4. a **metadata-only fileless stub** (never downloaded, zero sessions,
        annotations, evaluations, or reading progress) carrying reader-authored
        title override, custom cover (`cover_override = 1`), manual series
        order (`SeriesOrderDao`, `series_index_override = 1`), and
        `archived_at != NULL`, with no ISBN/DC/SHA (exercising the
        deterministic `stub:` fallback);
     5. an undownloaded stub with `archived_at != NULL` only (verifying archive
        alone protects the stub).
   - Switch Device A to Google Drive and complete initial Drive sync, then
     connect fresh Device B to the same Drive account.
   - **Assert:**
     - Main server (`id = 1`) is disconnected; browse-only server (`id = 2`)
       and its books are untouched; downloaded book (1) is preserved.
     - Only genuinely untouched preview stub (2) is removed.
     - Fileless stubs (3), (4), and (5) are **unlinked rather than forgotten**,
       acquire Drive `work_alias` rows **before** `forgetSyncPeer(server)`
       deletes server aliases, and **actually upload/seed Drive** (`catalog`,
       `metadata` + custom cover blob, `series`, `annotation`, `session`,
       `archivedAt`).
     - Device B receives from Drive: book (1), history-bearing stub (3) with
       100% of its annotations and reading sessions, metadata-only stub (4)
       with its custom title, custom cover, manual series order, and archived
       state, and archived stub (5) — and never receives deleted preview (2).
7. **Transport & schema resilience:**
   - Duplicate batch delivery and out-of-order delta delivery (gap waits or
     falls back to snapshot).
   - Expired `pageToken` (HTTP 400/404) triggers idempotent full rescan.
   - Process death after delta upload before outbox ACK deduplicates via
     `appProperties.batchId` without creating duplicate delta files.
   - Unsupported `schemaVersion` pauses device stream with upgrade notice;
     unsupported domain version pauses only that `(device, domain)` cursor.
   - Clean reinstall on a fresh device reconstructs catalog, custom covers,
     metadata overrides, series, positions, annotations, evaluations,
     vocabulary, and reading stats from Drive alone.
