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
| `peerId` | `"drive:" + permissionId` | Used in every per-peer table of `liseur.db` (`work_alias`, `sync_peer_state`, `furthest_position`, `peak_delivery`, `annotation_sync`, `evaluation_sync`, `metadata_override_sync`, `vocabulary_word_sync`, `vocabulary_review_sync`, `vocabulary_staged_review`, `drive_apply_receipt`). |
| `deviceId` | Lowercase UUIDv4 (`36` chars) | Generated once per install in `drive_sync.db` (`drive_install.device_id`), excluded from Android backup/transfer. Never derived from `DeviceIdentity.id`. |
| `hlc` | `String.format(Locale.ROOT, "%013d-%04x-%s", wallMs, counter, deviceId)` | Monotonic hybrid logical clock persisted in `drive_account(hlc_wall_ms, hlc_counter)`. Compared lexicographically (strict total order because `deviceId` breaks ties). On send: if `nowMs > wallMs`, `wallMs = nowMs; counter = 0`, else `counter += 1`. **Counter overflow:** when `counter` would become `0x10000`, do NOT widen the field — set `wallMs += 1` (a logical tick strictly ahead of any real clock reading) and reset `counter = 0`, so the fixed-width `%04x` stays 4 hex digits and lexicographic ordering stays monotonic across the overflow. On receive: `maxWall = max(localWall, nowMs, remoteWall)`; update `counter` accordingly. |
| `batchId` | `UUID.nameUUIDFromBytes("batch|$deviceId|$seq".toByteArray(UTF_8)).toString()` | Deterministic per `(deviceId, seq)` for idempotent retry deduplication in `appDataFolder`. |
| `workKey` | `"isbn:<isbn13>"` \| `"dc:<sha256hex>"` \| `"sha:<editionSha256>"` \| `"stub:<sha256hex>"` | For local/downloaded books, computed once `book_fingerprint` exists AND `books.file_metadata_checked = 1`. First match wins: (1) normalized ISBN-13 via `hardcover/Isbn.from` on `WorkIdentifiers.dcFrom(books.work_id, ...)` or `book_metadata(source='file').identifiers`; (2) `sha256Hex(trim.lowercase(id))` for any usable `dc:identifier` not in `USELESS_IDENTIFIERS`; (3) `book_fingerprint.sha256`. **Shared-identifier collision:** a local file's ISBN or `dc:` key falls back to `sha:<editionSha>` when that key already names a different edition (another local book, or a catalog entry whose `editions[]` lacks this sha) with a different normalized title+author, so two unrelated EPUBs carrying the same erroneous identifier stay separate. Known v1 limitation: two devices that each mint the same key for different books before either sees the other's catalog publish one catalog entry with two editions, and v1 has no split operation for it. **Fileless preserved stubs on Server → Drive switch** (where `BookRemoval.downloadRemoved()` cleared `book_fingerprint` and `file_metadata_checked = 0`, or the book was never downloaded): captured **before** `forgetSyncPeer(server)` deletes server `work_alias` rows in priority order: (1) ISBN-13 from `books.work_id` or `book_metadata(source='catalog'\|'file').identifiers`; (2) usable `dc:identifier` from `books.work_id` or `book_metadata(source='catalog'\|'file').identifiers`; (3) `sha:<edition_sha>` from the retiring server's `work_alias.edition_sha`; (4) deterministic portable fallback `stub:<sha256Hex("liseur-stub\|v1\|" + book.url)>` when none of (1)–(3) exist (later mergeable with a downloaded EPUB via title+author confirmed matching emitting a `workAlias` `merge:` record). Upserted into `work_alias(peer_id = drive:<permissionId>, confidence = 'high', confirmed = 1, seeded = 0)` so preserved fileless books seed Drive immediately. Frozen in `work_alias.work_id` once published; a stronger identifier found later emits a `workAlias` record instead of rekeying. |
| `conflictId` | `sha256Hex("$domain|$recordKey|${minOf(fpA, fpB)}|${maxOf(fpA, fpB)}")` | Primary key (with `permission_id`) in `drive_conflict`. |

**Locale-independent wire numbers.** Every number written into a wire string
(the HLC, the `%010d` and `%03d` file-name parts, and numeric `appProperties`
values such as `seq`, `throughSeq` and `partIndex`) is formatted with
`String.format(Locale.ROOT, ...)` or an equivalent that emits only ASCII
digits `0-9` and lowercase hex `a-f`; the default locale is never used, because
`%d` can emit non-ASCII digits (for example Arabic-Indic) that sort
differently. Readers parse these strings with ASCII-only digits and treat an
HLC or file name that contains any other digit as invalid, so every device
orders the same HLC strings identically.

### 1.2 Canonical JSON & Number Formatting

All fingerprinted objects and wire payloads use deterministic canonical JSON:

1. **Encoding:** UTF-8, no BOM, no insignificant whitespace, no trailing commas.
2. **Object keys:** Sorted lexicographically by UTF-16 code units (identical to
   ASCII/UTF-8 order for all ASCII schema keys).
3. **Nulls vs. omitted fields:** Every schema-declared field in a domain `body`
   is **always emitted** (with JSON `null` when nullable and absent) so two
   implementations never disagree on omitted-vs-null keys. Optional envelope
   fields (`outcome`, `resolvedFps`) are present **only** when `op == "resolve"`;
   `observed` and `restore` only on a `vocabWord` `del`.
4. **Strings:** Standard RFC 8259 JSON escaping (`\"`, `\\`, `\b`, `\f`, `\n`,
   `\r`, `\t`, and `\u00XX` lowercase hex for other control characters `< 0x20`).
   Non-ASCII UTF-8 characters are written unescaped.
5. **Integers (`Int`, `Long`):** Base-10 ASCII decimal (`Locale.ROOT`, §1.1),
   no leading zeros except `0`, no decimal point.
6. **Floating-point (`Double`):**
   - Reject `NaN` and `±Infinity` before serialization.
   - Normalize `-0.0` to `"0"`.
   - Format via `BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()`,
     so `0.50` → `0.5`, `1.0` → `1`, `0.0` → `0`, `7.50` → `7.5` (matches
     `seriesIndexLabel` in `Series.kt`). `toPlainString()` is
     locale-independent and always emits ASCII digits and `.`.
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
| `op: "put"` | `contentFp(body) = sha256Hex(canonicalJson({"op":"put","body":<canonicalBody>}))` | Depends only on content fields (never `editionSha` for annotations, never `position`, never local-only columns). Recomputable directly from the committed `liseur.db` row for every domain whose body is fully stored there. `catalog` is not (`archiveHlc`, edition availability and `updatedAt` have no `books` column), so its recovery uses the apply receipt (§3.3). |
| `op: "del"` | `delFp(d, k, hlc) = sha256Hex(canonicalJson({"op":"del","d":<d>,"k":<k>,"hlc":<hlc>}))` | Including `hlc` makes every delete event unique, so deleting a record after keeping it in an earlier `DELETE_EDIT` resolution never collides with the old delete in `anc`. |
| `op: "put"` revival (`vocabWord` only, §4.9) | `reviveFp(delFp, restoreFp) = sha256Hex(canonicalJson({"op":"revive","delFp":<delFp>,"restoreFp":<restoreFp>}))` | A revival is a wire `op: "put"` whose `fp` is `reviveFp`, not `contentFp(body)`. Its body is `restore.body`, so `drive_record_state.content_fp` (and `acked_fingerprint`) stay `contentFp(restore.body)` and local diffing compares the row against `content_fp`, never against `version_fp`. Every device that revives from the same `del` computes the same `reviveFp`, so concurrent revivals agree (§3.1 Step 2). |
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
| `observed` | `Object<deviceId, seq>` | Only when `d == "vocabWord" && op == "del"` | Vector of the highest delta `seq` the deleting device had applied from each other device when it authored the delete — the causal boundary for the word's reviews and occurrences (§4.9). A value that is not an object decodes to `{}`, which keeps data. Absent otherwise. Excluded from `delFp` (§1.3). Snapshots keep it on the `del` record. |
| `restore` | `Object` | Only when `d == "vocabWord" && op == "del"` (required there) | `{"fp": <contentFp of the last live word body>, "hlc": <record hlc of that version>, "body": <that full vocabWord body, §4.9>}`: the parent content a receiver needs to restore the word when a review or occurrence it did not cover arrives later. It is the last live version the deleter held before deleting, so `fp` is normally `anc[0]`. The record's own `body` stays `null`, so the general `op: "del"` contract is unchanged. Excluded from `delFp`. Snapshots keep it on the `del` record, so a device rebuilt from snapshots has it. A `del` without a valid `restore` is malformed: it is not applied (no cascade, nothing is deleted), the cursor advances, and the receiver logs it. |
| `seq` | `Long` | Snapshot `vocabOccurrence` / `vocabReview` records only | The author's delta `seq` that first published this version. Readers store it as the record's provenance instead of `snapshot.throughSeq`, so a later `vocabWord` `del` whose `observed` vector covers that seq still counts the record as seen. `throughSeq` only advances cursors. A record with no known provenance counts as unseen. |
| `revives` | `Object` | Only when `d == "vocabWord" && op == "put"` and the put is a revival (§4.9) | `{"del": <delFp>, "restore": <restore.fp>}`. Required exactly when `fp` is a `reviveFp` (§1.3); a receiver recomputes `reviveFp(revives.del, revives.restore)` and rejects the record as malformed if it differs from `fp`. `anc` is `[revives.del, revives.restore]` followed by the revived version's other ancestry. Excluded from `fp`. |

A `vocabWord` `del` record, with `body` still `null`:

```json
{
  "d": "vocabWord",
  "k": "2:fr:ephemere",
  "op": "del",
  "hlc": "0017450000000-0004-550e8400-e29b-41d4-a716-446655440000",
  "fp": "<64-hex delFp>",
  "anc": ["<restore.fp>"],
  "resRefs": [],
  "body": null,
  "observed": { "661f9511-f3ac-52e5-b827-557766551111": 8 },
  "restore": {
    "fp": "<64-hex contentFp of the body below>",
    "hlc": "0017450000000-0002-550e8400-e29b-41d4-a716-446655440000",
    "body": { "language": "fr", "normalized": "ephemere", "word": "éphémère", "...": "..." }
  }
}
```

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
     are never dropped in v1, and a `vocabWord` `del` keeps its `observed` vector and `restore` payload.
   - For append-only domains (`session`, `vocabReview` including review `del`s,
     `furthest` resets): all authored records in full, so a review `put` stays in
     the snapshot beside the `del` that undid it.
   - `vocabOccurrence` and `vocabReview` records also carry `seq`, their original
     publication seq (Section 2.3), so delete observed vectors still apply to them.
   - Never copies records authored by other devices.
   - **Source: `drive_authored_record`, not `drive_record_state`.**
     `drive_record_state` holds one *current* version per key and is
     overwritten when the device adopts another device's update or the reader
     undoes a review, so it cannot supply an author's history. Each record this
     device authors is also written to `drive_authored_record` (ADR 0078,
     `drive_sync.db`) in the **freeze transaction**: the same `drive_sync.db`
     transaction that allocates `seq` from `drive_account.next_seq`, inserts the
     `drive_outbox` row and inserts one authored row per record with that
     `seq`. Provenance (`seq`, the `observed` vector, `restore`, `revives`) is
     therefore never written without its batch, and a batch is never frozen
     without it. A snapshot with `throughSeq = T` takes, per `(d, k)`, the
     authored row with the greatest `seq ≤ T` (mutable domains) or every
     authored row with `seq ≤ T` (append-only domains), ordered by `(d, k,
     seq)`. Adopting a remote version never touches these rows. Mutable-domain
     rows with `seq ≤ T` that another authored row with `seq ≤ T` supersedes
     are deleted in the same transaction that commits the new manifest;
     append-only rows and `del` heads are never deleted in v1. Delta files
     may be pruned only after a manifest-listed snapshot built this way
     covers them.
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
     covered by `R.resolvedFps`; classify the local version's effective
     outcome (`put` or `del`) against `R.outcome`: both `put` opens
     `EDIT_EDIT`, exactly one `put` opens `DELETE_EDIT`, and both delete is
     agreement, so no conflict opens. Open conflicts preserve both versions.
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
| `annotation` (`annotations` + `annotation_sync`) | `annotation_sync.acked_fingerprint = contentFp` (or `"DELETED"` on delete/delete-resolution), plus a `drive_apply_receipt` row (§3.3) in the same Tx 1. | `acked_fingerprint` alone cannot name the applied wire version: a keep-body resolution leaves it unchanged, so an edit made after the crash looks like one made before the resolution. Use the receipt. Receipt `version_fp == R.fp` and row unchanged since the apply (`row_fp` equal) → Agreement (repair `drive_record_state`, advance cursor). Row edited after the apply → post-apply edit rule (§3.3): the edit stays dirty, `R.fp` joins its `anc`, replay is Stale. No receipt for `R.fp` → Tx 1 never committed; apply normally. |
| `evaluation` (`book_evaluation` + `evaluation_sync`) | Revision-to-fingerprint adapter: `acked_revision = rev`, `sent_revision = rev`, `sent_json = {"vfp": "<fp>", "cfp": "<contentFp>", "rev": <rev>}`. | When `drive_record_state.last_synced_revision < sync.acked_revision`: if `row.revision == sync.acked_revision`, recompute `contentFp` from committed `book_evaluation` row (or `"DELETED"` if `value == null`), pair with `sent_json.vfp`, and repair `drive_record_state`. If `row.revision > sync.acked_revision`, seed `anc` with `sent_json.vfp` and keep new edit dirty (`row.owed(sync) == true`). |
| `metadata` (`books` + `metadata_override_revision` + `metadata_override_sync`) | Same revision-to-fingerprint adapter on `metadata_override_sync` (`acked_revision`, `sent_revision`, `sent_json`), plus `remote_cover_sha256` & `adopted` for `cover`. | Identical to `evaluation`: recompute `contentFp` from committed `books` override columns when `rev.revision == sync.acked_revision`; seed `anc` from `sent_json.vfp` if reader edited again before recovery ran. |
| `vocabWord` + `vocabOccurrence` (`vocabulary_*` + `vocabulary_word_sync`) | `vocabulary_word_sync.acked_fingerprint` + `acked_base_json`. | Recompute the word's `contentFp` from committed `vocabulary_words` and repair `drive_record_state`. Occurrences are a separate domain; their merge is commutative and idempotent (§4.10), so replay reproduces the same result without a receipt. A word `del` also stores its `restore` and `observed` payloads (`restore_json`, `observed_json`, §4.9). |
| `vocabReview` (`vocabulary_reviews` + `vocabulary_review_sync`) | `UNIQUE(wordId, syncId)` + `vocabulary_review_sync(peer_id, review_id, acked_deleted)`. | `INSERT OR IGNORE` + idempotent `reconcileSchedule`; replay inserts 0 rows and produces identical SRS state. |
| `session` (`reading_sessions` + `drive_session_ack`) | `UNIQUE INDEX` on `reading_sessions.remote_session_id`. | Incoming pull uses `INSERT OR IGNORE` — replay after crash inserts 0 rows, so session counts once. Outbound upload tracks per-account delivery in `drive_session_ack`. |
| `series` / `catalog` (`books`) | `drive_apply_receipt` row written in the same Tx 1 as the `books` writes (§3.3). `drive_record_state` stores `version_fp`, `published_fp`, `content_fp`, `body_json`. | Receipt recovery (§3.3) and local diffing run **before** pulling. The bare `books` row cannot reproduce a catalog wire fp (it has no `archiveHlc`, edition availability or `updatedAt`), so recovery compares a row projection with the receipt and never `contentFp(book)` with `contentFp(R.body)`. Row unchanged since the apply → restore `drive_record_state` from the receipt, advance the cursor on replay. Row edited after the apply → the edit becomes a new local version whose `anc` holds `R.fp`, so replay is Stale and no spurious `series` conflict opens. |
| `position` / `furthest` (`reading_progress` + `sync_peer_state` + `furthest_position`) | `sync_peer_state.acked_revision`, `agreed_*`, `pending_*`; `FurthestPositionDao.observe` (monotonic max). | `reconcileReadingState` and `observe` / `deleteUpToForOrigin` are strictly idempotent on replay. |
| Local conflict resolution (`drive_conflict` `RESOLVING`) | Local choice applied to `liseur.db` in Tx 1; `drive_conflict.state` moves `RESOLVING → RESOLVED` after publish. | On restart, any `RESOLVING` row checks committed `liseur.db` state, ensures the `op: "resolve"` batch is frozen/uploaded, persists `drive_resolution` and `res_refs_json`, and marks `RESOLVED`. |
| Incoming `op: "resolve"` replay (`drive_conflict` `OPEN`) | Remote resolution outcome + per-peer ack/`sent_json` committed to `liseur.db` in Tx 1; `drive_resolution`, `drive_conflict` (`RESOLVED`), `res_refs_json`, and `drive_cursor` committed in Tx 2. | If crash occurs after Tx 1 and before Tx 2, recovery repairs `drive_record_state` from `liseur.db` and the apply receipt (§3.3) while the conflict stays `OPEN` and `drive_cursor` stays un-advanced. On replay of `R` (`op: "resolve"`), **Step 0 runs BEFORE Agreement (unedited) and BEFORE Stale (post-crash local edit)**, idempotently persisting `R` in `drive_resolution`, closing the `OPEN` conflict in `drive_conflict` as `RESOLVED`, updating `res_refs_json`, and only then advancing `drive_cursor`. |

### 3.3 Apply Receipt & Transaction Order

Equal row content never proves which wire version was applied: a keep-body
resolution can leave the row and `acked_fingerprint` untouched, and a catalog
wire body cannot be recomputed from `books`. Domains without a per-peer ack
that carries the applied version fp therefore get a receipt. `annotation`,
`series` and `catalog` write one row per record in a new `liseur.db` table
`drive_apply_receipt`, in the same Tx 1 as the data. The protocol is
unreleased, so the table ships with the first schema that contains Drive and
needs no migration from earlier data. `evaluation` and `metadata` already
satisfy the rule: `sent_json.vfp` is the applied `R.fp` and `rev` increases
with every local edit.

| Column | Meaning |
|---|---|
| `receipt_id` | UUIDv4 generated in the Tx 1 that writes the row, unique per apply. A replay after a crash reuses the id already stored for `R.fp`. |
| `peer_id`, `domain`, `record_key` | Primary key. One row per record; the next apply of the same record overwrites it. Local publishes never touch it. |
| `version_fp`, `version_op`, `outcome` | `R.fp`, `R.op`, and `R.outcome` (`NULL` unless `R.op == "resolve"`). |
| `hlc`, `origin_device_id`, `origin_seq` | `R.hlc`, `R.deviceId`, and `R.seq` (`throughSeq` for a snapshot record). |
| `anc_json`, `res_refs_json` | The `anc` and `res_refs` that `L` takes after the apply. |
| `body_json` | Canonical body as materialized, which for the `catalog` automatic merge is the merged body and can differ from `R.body`. `NULL` when the apply deleted the row. |
| `row_fp` | Fingerprint of the row projection after the apply, or `"DELETED"`. The projection is the set of body fields the domain stores in `liseur.db`: `annotation` and `series` use the whole body (minus `editionSha` for annotations); `catalog` uses `title`, `author`, `fileSeriesName`, `fileSeriesIndex`, `addedAt` and `archivedAt` only. |
| `republish` | `1` when `body_json` differs from `R.body`, so the merged body is owed as a new version. |

**Tx 2 acknowledgement.** The Tx 2 of every remote apply writes
`drive_record_state.acked_receipt_id := receipt_id` in the same
transaction as the rest of the record's state. Local publishes, local
versions and `anc` truncation never change it; only the next remote apply
or a recovery repair does. It is a watermark, not ancestry, so it survives
any number of later local versions and the 64-entry `anc` cap.

**Recovery** runs before local diffing and before any pull. For every
receipt whose `receipt_id` differs from `acked_receipt_id` of the matching
`drive_record_state` row (a missing row counts as `NULL`), Tx 2 did not
commit. A receipt whose id matches is never re-applied, however old. Each
repair below sets `acked_receipt_id := receipt_id` in its own Tx 2. Recompute the row projection and compare it with `row_fp`:

- **Unchanged since the apply:** set `L := version_fp`, `version_op`,
  `anc`, `res_refs`, `origin_*` and `body_json` from the receipt, and
  `published_fp := version_fp`. If `republish == 1`, instead make
  `L := contentFp(body_json)` a new local version with
  `anc := [version_fp, <old L>] ∪ anc_json` and leave it unpublished, so
  the merged body is sent next. Replay of `R` then reaches Agreement.
- **Changed since the apply (post-crash edit):** the row projection differs
  from `row_fp`.
  - **Row still present:** build the new body from `body_json` with the
    projection fields replaced by the row's values, set
    `L := contentFp(<new body>)`, `anc := [version_fp, <old L>] ∪ anc_json`,
    `res_refs := res_refs_json`, and keep it dirty. Replay of `R` then
    reaches Stale because `R.fp ∈ anc(L)`. For `catalog`, only the
    row-backed fields are replaced; every edition, its `availabilityHlc`,
    identifiers and `updatedAt` stay as in `body_json`. `archiveHlc` is
    minted (a fresh HLC tick persisted in `drive_account`, before
    fingerprinting) **only when the row's `archivedAt` differs from
    `body_json.archivedAt`**; otherwise the receipt's `archiveHlc` is kept.
  - **Row deleted (`annotation` and `series`, where a local delete is a
    `del`, §4.4, §4.7):** `body_json` is the last applied body but the
    projection is `DELETED`, so there is nothing to put. Mint a fresh HLC
    `h`, set `L := delFp(d, k, h)`, `version_op := "del"`,
    `anc := [version_fp, <old L>] ∪ anc_json`, `res_refs := res_refs_json`,
    `body_json := NULL`, the per-peer `acked_fingerprint` to `"DELETED"` where
    the domain keeps one, and keep it dirty. Replay of `R` then reaches Stale
    because `R.fp ∈ anc(L)`, the delete is published, and the incoming record
    never resurrects the row. A receipt with `row_fp == "DELETED"` and a row
    still absent is the *Unchanged* case above.
- In both cases Step 0 (§3.1) still runs on replay before the cursor
  advances.

A receipt whose `version_fp` differs from `R.fp` on replay, or no receipt,
means Tx 1 for `R` never committed. `R` is processed from scratch.

**Transaction order for a remote apply.** Each step either commits entirely
or leaves the previous state intact, so a crash at any point replays
without loss:

1. Run receipt recovery, then local diffing (so a post-crash edit is a
   recorded version), then pull.
2. Decide `R` against `L` (§3.1). Tx 1 (`liseur.db`) is one transaction:
   verify the row still has the fingerprint the decision was computed
   against, else abort and re-decide so a local edit that landed in
   between is never overwritten; then write the row or deletion, the
   per-peer ack, and the receipt.
3. Tx 2 (`drive_sync.db`) is one transaction: `drive_resolution`,
   `drive_conflict` settlements, `drive_record_state` (including
   `res_refs_json`, `body_json`, `observed_json`, `restore_json`, `held`,
   `acked_receipt_id`), and `drive_cursor` last.
   The cursor never moves before every Tx 2 effect of that record is
   committed.
4. A word delete that cascades moves its covered children to non-live
   history instead of discarding them (§4.9): reviews become `COVERED`
   rows in Tx 1, occurrences keep their `drive_record_state` row with
   `held = 1` in Tx 2. The `restore` payload (§4.9) is written in Tx 1 with
   the delete.

---

## 4. Per-Domain Wire Schema & Room Mapping Matrix

Apply order within every sync run:
`workAlias` → `catalog` → `metadata` / `series` → `evaluation` → `annotation` → `position` / `furthest` → `vocabWord` → `vocabOccurrence` → `vocabReview` → `session`.

A `metadata`, `series`, `evaluation`, `annotation` or `position` put whose
`workKey` has no local book and no catalog entry yet is deferred: its domain
cursor stays put, so the record replays once the catalog creates the book.
A book-scoped sync publishes the book's catalog entry along with its other
records, so a receiver never waits on a catalog record that was not sent.

A catalog tombstone counts as a known catalog entry, so it never causes a
deferral. A snapshot after **Delete from synced library** can hold the
catalog `del` next to retained records and no later catalog `put` is
guaranteed. Because `catalog` applies first in a run, the receiver already
holds the tombstone (`drive_record_state.version_op` is `del`, or `resolve`
with outcome `delete`) when it reaches those records:

- `annotation` records apply against `book_id = "drive:<workKey>"` as hidden
  history, with their `annotation_sync` row and receipt, and create no
  `books` row (`BookAnnotation` has no foreign key to `books`, and
  annotations already outlive their book on removal). They never recreate the
  shelf entry, and later edits, deletes and resolutions apply normally.
- `metadata`, `series`, `evaluation` and `position` records for that
  `workKey` are **held**: the record is decided against `L` with the normal
  §3.1 cases, but Tx 1 writes nothing to `liseur.db`. Tx 2 stores the
  version in `drive_record_state` with `held = 1` (`version_fp`,
  `version_op`, `body_json` or `NULL`, `ancestors_json`, `res_refs_json`,
  `origin_*`, `last_hlc`; a resolution's outcome is in `drive_resolution`),
  and the cursor advances. A held version is never discarded or overwritten
  except by a later version of the same record that §3.1 accepts, and a
  concurrent one opens a conflict in `drive_conflict` that the UI shows only
  once the book exists. Materialization: at the start of each run, after the
  `catalog` pass, every held record whose `workKey` now has a local `books`
  row (for `drive:<workKey>` or an alias target) is run through §3.1 as an
  incoming `R` built from the stored version, against whatever the restored
  book already has locally (`L = none` if nothing). Tx 1 applies it, Tx 2
  clears `held`. Nothing in this path creates a `books` row, so arriving
  history never returns the book to the shelf; only the reader (or a
  later catalog version that §3.1 accepts) does that. Snapshots carry the
  authors' latest records, so a device rebuilt from snapshots re-holds them.
- Only a `workKey` with neither a local book nor any catalog state
  (`put` or tombstone) is deferred, and only that case holds the cursor.

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
        "uploadedAt": 1745000000000,
        "availabilityHlc": "0017450000000-0001-550e8400-e29b-41d4-a716-446655440000"
      }
    ],
    "fileSeriesName": "Middle-earth",
    "fileSeriesIndex": 1,
    "addedAt": 1745000000000,
    "archivedAt": null,
    "archiveHlc": null,
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
    - `availabilityHlc`: `String?` (HLC of the last availability change of
      this edition, minted by the device that verified the upload or
      detected the Drive-side trash/delete; `null` until the first such
      change, and then `driveFileId`, `fileName` and `uploadedAt` are all
      `null`). `driveFileId`, `fileName`, `uploadedAt` and
      `availabilityHlc` form one availability group that is only ever
      taken together. A cleared edition keeps its entry with the three
      fields `null` and the clearing `availabilityHlc`, which is the
      tombstone. Any catalog update that does not change availability
      (archive choice, another edition's upload, a series change) carries
      every edition's group over unchanged from the merged state and mints
      no new `availabilityHlc`. A non-null `driveFileId` with a `null`
      `availabilityHlc` is invalid and rejected.
  - `fileSeriesName`: `String?` (`books.file_series_name`)
  - `fileSeriesIndex`: `Double?` (`books.file_series_index`)
  - `addedAt`: `Long` (epoch ms; `minOf` on merge)
  - `archivedAt`: `Long?` (epoch ms; `null` = on shelf; non-null = archived)
  - `archiveHlc`: `String?` (HLC of the archive or unarchive choice that set
    `archivedAt`, minted only when the publishing device's archive state
    changes; `null` until the first such choice)
  - `updatedAt`: `Long` (epoch ms)
- **Merge / Conflict rule:**
  - Automatic merge (no prompt), commutative so both devices compute the same
    body from the same two inputs: `editions` union by `sha256`, taking each
    sha's availability group from the side with the higher
    `availabilityHlc` (`null` is lowest; on equal values prefer the greater
    `driveFileId`, `null` lowest), so a newer record that merely carries an
    old `driveFileId` never resurrects a link another device cleared, and a
    later re-upload (new `availabilityHlc`) replaces the tombstone;
    `sizeBytes` prefers non-null, then the greater value; `identifiers` union; `addedAt = minOf`; `archivedAt` comes
    from the body with the higher `archiveHlc` (the record HLC only when
    neither has one), so a newer record that merely carries an old archive
    state, such as an edition upload, never overrides a later archive choice;
    `title`, `author`, `fileSeriesName`, `fileSeriesIndex` and `updatedAt`
    come from the body whose record has the higher `(hlc, fp)`.
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
    it emits a catalog `put` that clears that edition's `driveFileId`,
    `fileName` and `uploadedAt` and stamps a new `availabilityHlc`, retaining
    catalog history, sessions, and annotations.
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
  - Not stored in `books`: `archiveHlc`, each edition's availability group,
    `identifiers` and `updatedAt`. The merged catalog body lives in
    `drive_record_state.body_json` (`drive_sync.db`) and, for crash recovery,
    in `drive_apply_receipt.body_json` (`liseur.db`, §3.3). The receipt's
    `row_fp` covers only the `books`-backed fields `title`, `author`,
    `fileSeriesName`, `fileSeriesIndex`, `addedAt` and `archivedAt`.

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
  - **Delivery is not agreement.** `sync_peer_state.acked_revision` records
    that a local change reached this device's own Drive stream. It is not
    evidence that any other device saw that change, so it must not make a
    local change look reconciled before a pull from another device. A local
    change counts as clean only against the agreed baseline (`agreed_*`)
    established with the pulled device, so concurrent positions on two
    devices open `Conflict` whether the local upload or the pull happens
    first. A fresh device rebuilding state from several streams reaches the
    same outcome in either stream enumeration order. Required tests: upload
    before pull, and fresh-device reconstruction in both enumeration orders.
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
  - `seq`: `Long` (strictly increasing per account and authoring `deviceId`
    across every work and edition). It comes from a per-device counter kept in
    the local-only `_furthest_peak_seq` record state, never from a book's
    `peak_revision`, which restarts per book and so cannot order peaks against
    work-wide floors. A peak is bound to its allocated `seq` in the same
    transaction that allocates it, so retries re-send the same `seq`; the
    binding is dropped when the book's peak changes.
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
  - Forgetting applies to the whole work, not one edition. The reset record
    and its floors (the authoring device's floor is its highest allocated
    `seq`) are persisted in `drive_sync.db` first. Local effects follow: delete
    the covered `furthest_position` rows, reset the local `reading_progress`
    peak of every local edition of the work whose bound `seq` is at or below
    its device floor, and clear that edition's `peak_delivery`. Resetting the
    local peak is required: otherwise a book that was at 80% and is now at 20%
    could not record a new peak at 30%, because capture needs a strict
    increase. A peak that is unsent or newer than the floor is preserved. An
    interrupted forget is completed on the next sync by replaying the persisted
    reset before pulling.
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
  - `drive_apply_receipt` (§3.3): one row per annotation id, written with
    every remote apply, so crash recovery can tell an applied resolution
    from a later local edit.
- **Catalog tombstone:** annotations whose `workKey` is tombstoned in
  `catalog` are retained as hidden history under `book_id =
  "drive:<workKey>"` with no `books` row (§4 intro). They never recreate the
  shelf entry and never hold the device cursor.

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
    incoming. The merge is **commutative**: for the same `base`, swapping
    `local` and `remote` yields a byte-identical canonical body, so two
    devices that exchange concurrent edits compute the same merged body, both
    publish (or skip) a put with the same `fp`, and meet in Agreement. Equal
    timestamps are broken by value, never by which side is local:
    - `notes`: later `notesUpdatedAt` wins; on equal timestamps the greater
      `notes` string (UTF-16 code-unit order) wins and keeps the shared
      timestamp.
    - Definition group (`definition`, `translationLanguage`,
      `definitionEdited`, `definitionUpdatedAt`) moves as one: `definitionEdited
      = true` beats `false`, then the later `definitionUpdatedAt`, then the
      greater `(definition, translationLanguage)` compared field by field with
      `null` lower than every string including `""`.
    - `word` when both sides changed it (or there is no base): the greater
      non-blank string. `addedAt` is `minOf`; `baseline` uses
      `mergeBaseline`'s total order.
  - `del` on untouched local word (`L ∈ R.anc`): silent delete of local word
    (cascading occurrences and reviews) and record
    `vocabulary_word_sync.acked_fingerprint = "DELETED"` — but only when the
    causal boundary proves no live review or occurrence is uncovered (next bullet).
  - **Causal boundary (`observed` vector):** a `vocabWord` `del` records
    the highest delta `seq` the deleting device had applied from each other
    device (`observed`, §2.3) alongside `anc`. The children of a word are its
    reviews and its occurrences, and each child version has provenance
    `(deviceId, seq)`: a review's author batch; an occurrence's
    `(origin_device_id, origin_seq)`, which is the other device and batch it
    was applied from (for a snapshot record, the record's own `seq`, never
    `throughSeq`), or this device and the batch that published its current
    version. A child is **covered** when `observed[deviceId] >= seq`, or when
    `deviceId` is the deleter itself and `seq` is at or before the delete's
    own batch. It is **uncovered** in every other case, including no recorded
    provenance, a snapshot record without `seq`, and a local review or
    occurrence edit that is still unpublished (no batch `seq` yet). Reviews
    are append-only and an occurrence's edits live in its own domain, so
    neither is part of `anc(L)`, and `anc(L)` alone cannot prove the
    deleter's word version descends from the current children.
  - **Child history states.** A review or occurrence of a word is in one of
    three states: **live** (a row in `vocabulary_reviews` /
    `vocabulary_occurrences`), **staged** (parent absent, not yet judged) or
    **covered** (a delete's `observed` vector covers it, so it is non-live
    history). Staged and covered children are never shown, counted,
    searched or fed to `reconcileSchedule`, and nothing in the UI changes
    while the delete is in force. Reviews are kept in
    `vocabulary_staged_review`, which gains `state` (`STAGED` / `COVERED`),
    `origin_device_id`, `origin_seq` and the full review fields. Occurrences
    are kept as `drive_record_state` versions with `held = 1` (version,
    `body_json`, `origin_*`). A review whose `vocabulary_review_sync.acked_deleted`
    is set (final undo) is never stored in either state and is removed from
    both when the undo arrives.
  - **Delete vs. uncovered child:** before any cascade delete (Tx 1), every
    live local review and every live local occurrence of the word must be
    covered. If any is uncovered, the delete is a concurrent write and
    **add-wins silently**: keep the word with *all* its children, then
    republish a `put` carrying the `del` fp in `anc` plus every retained
    review and occurrence as fresh delta records (same content, new `hlc`).
    If all are covered, the cascade does not discard them: Tx 1 moves each
    live review to `vocabulary_staged_review` as `COVERED`, and Tx 2 marks
    each occurrence's `drive_record_state` `held = 1`, so covered children
    stay recoverable.
  - **Restore payload:** every `vocabWord` `del` carries `restore`
    (`{fp, hlc, body}`, §2.3), the last live word body the deleter held, and
    `observed`. The deleter fills it from its word row in the same Tx 1 that
    deletes it. A receiver applying the `del` stores it in Tx 1 by keeping
    (or, on a device that never had the word, creating) the
    `vocabulary_word_sync` row with `acked_fingerprint = "DELETED"` and
    `acked_base_json = restore.body`, and in Tx 2 by writing
    `drive_record_state.restore_json = restore` and `observed_json =
    observed`. It always uses the `del`'s `restore`, not its own last body,
    so every device holds the same parent baseline. The payload is
    replicated through deltas and snapshots like any other record field, so a
    device that reconstructs only from snapshots has it.
  - **Child arrives:** the same coverage test against the tombstone's
    `observed_json` applies on every arrival path (delta or snapshot).
    - Word live: attach as normal.
    - Word absent, no tombstone known: store the child `STAGED` / `held`.
    - Tombstone known, child covered: store it `COVERED` / `held`. The word is
      not restored.
    - Tombstone known, child uncovered: **revive** (below).
  - **Tombstone arrives:** after storing `restore_json` and `observed_json`
    (above), judge the children already held for the word. The check
    includes `STAGED` children, so a child received before the tombstone is
    treated the same as one received after it. If the word is live, an
    uncovered live or staged child keeps it (add-wins, republish). If the word
    is absent, every `STAGED` child is re-labelled `COVERED` or, if any is
    uncovered, the word is **revived** in the same Tx 1.
  - **Revive:** one Tx 1 restores the word row from `restore_json.body`,
    then attaches the **eligible child set**: every `STAGED` or `COVERED`
    review and occurrence of the word that is not undone, plus the arriving
    child. It re-runs `reconcileSchedule` and sets `acked_fingerprint`. Tx 2
    clears `held` on the attached occurrences, records a new word `put`
    version with `fp = reviveFp(delFp, restore.fp)` (§1.3), body
    `restore.body`, `revives = {del: delFp, restore: restore.fp}` and `anc =
    [delFp, restore.fp]`, sets `content_fp := contentFp(restore.body)`, and
    the device publishes it and the attached children. The distinct `fp`
    means the original `del` is in the revived version's `anc` but the
    revived `fp` is in no `del`'s `anc`, so a replay of that `del` (for
    example after the reader undoes the uncovered review that triggered the
    revival) is Stale under §3.1 Step 5 and can never supersede the revival
    in Step 4. A later `del` of the revived word has `restore.fp =
    contentFp(<the body it deleted>)` and `anc[0] = reviveFp` as usual. The same set is
    attached whichever order the `del`, covered children and uncovered child
    arrived in. A word `put` received later that supersedes the tombstone
    (`del` fp in its `anc`) attaches the same set on the receiver.
  - **Retention for a snapshot-only device:** covered and staged children
    stay reconstructable because their authors' records are never pruned for
    being covered. Snapshots build `records` from `drive_authored_record`
    (§2.4), not from `drive_record_state` or live rows, so a child that is
    hidden locally, adopted over by another device, or undone still appears
    in its author's snapshot: every review `put` and review `del` in full, and
    the author's latest occurrence record with its original publication `seq`. A word `del` never
    removes another author's child records, and compaction and pruning
    (§2.4) may delete only delta files already covered by a snapshot that
    contains them. A device rebuilt from snapshots therefore receives the
    `del` with `restore` and `observed`, and every child with its `seq`, and
    reaches the same state in any order.
  - A `del` with no valid `restore` was never applied (§2.3), so the word
    and its children are still present locally and there is nothing to
    restore.
  - `del` vs. concurrent edit of the word: **add-wins silently**, keeping the
    local word and children, and republishing a fresh `put` with the `del` fp
    in `anc`.
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
  via `VocabularyMerge.mergeOccurrences`. Hidden as non-live history (§4.9) when parent
  `vocabWord` is deleted, but only when the delete's `observed` vector covers
  the occurrence's current version (§4.9); an uncovered local occurrence edit
  keeps the word and the occurrence.
  - **Commutative merge:** for the same `base`, `mergeOccurrencePair(base, a, b)`
    and `(base, b, a)` give byte-identical canonical bodies. Equal timestamps
    break by value:
    - `sentence`, `sentenceEdited`, `sentenceUpdatedAt` move as one group:
      `sentenceEdited = true` beats `false`, then the later
      `sentenceUpdatedAt`, then the greater `sentence` (UTF-16 code-unit
      order, `null` lower than every string including `""`).
    - `locator`: the greater non-null string; `null` is lowest.
    - `bookTitle`, `workId`: when both sides changed it (or there is no
      base), the greater non-blank string; blank or `null` is lowest.
    - `lastSeenAt` and `encounters`: `maxOf`. `occurrenceKey` and
      `locationKey` are part of the record key and equal on both sides.
  - **Provisional/canonical dedupe** keeps the canonical key when one exists,
    otherwise the lesser key, and merges the colliding versions with the
    commutative rule above, so the result does not depend on iteration
    order.
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
    `vocabulary_staged_review` (`state = STAGED`) and attached when the word
    arrives. A review
    whose parent is a tombstone follows the late-child rule (§4.9): covered
    reviews are ignored, uncovered ones restore the word. Staged reviews are
    never dropped, and a re-sent review (revival republish) is insert-if-absent
    by `syncId`, except when that `syncId` was undone.
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
  - `vocabulary_staged_review`: non-live review history (`state` `STAGED` or `COVERED`, with `origin_device_id`, `origin_seq` and the review fields), §4.9.

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
| `furthest` | A reads to 80%, turns back to 20% → B sees 80% peak in `furthest_position`. | Both A and B record peaks on different editions → both peaks retained per `(edition, origin=deviceId)`. | A triggers furthest reset (`floors:{devA:14, devB:9}`) → deletes peaks `≤ floor` on B; late peak from B with `seq ≤ 9` is dropped; new peak from B with `seq = 10` survives. After forgetting on A while reading at 20% (peak was 80%), A's local peak is cleared and a new 30% peak is captured and published. | Replaying a reset snapshot is idempotent; `FurthestPositionCaptureTest` passes with Drive peer. |
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

### 5.3 Contract Acceptance Cases

These cases test the contracts in §1.1, §2.4, §3.3, §4.2, §4.9–4.11 and the §4 intro. Each
runs on the §5.2 harness. Cases marked "both orders" run once per delivery
order and must end in the same state.

| # | Setup and action | Required result |
|---|---|---|
| C1 | Edition X has `driveFileId = F1`, `availabilityHlc = h5` on A and B. A clears X at `h10`. B, which has not seen that, archives the book at `h11` and publishes a body that still carries `F1` with `h5`. Both orders. | Both devices end with X cleared (`driveFileId = null`, `availabilityHlc = h10`) and B's archive choice kept. |
| C2 | Continue C1: B uploads X again at `h12` as `F2`. | `F2`/`h12` wins everywhere. A stale body carrying `F1`/`h5` or `h10` never changes it. |
| C3 | A deletes word W with `observed = {B: 8}`. B authored review `r` in batch `B:9`. Both orders. | `r` uncovered: the word and `r` survive on both devices; the republished `put` has the `del` fp in `anc`. With `observed = {B: 9}` the delete wins and `r` is gone on both. |
| C4 | A deletes W with `observed = {B: 11}`. B edits an occurrence sentence of W and publishes it in batch `B:12`. | Uncovered: W and the edited occurrence survive on both devices. With `observed = {B: 12}` they are covered and deleted. An unpublished local edit, or provenance missing, also keeps W. |
| C5 | A fresh device has only A's snapshot, which holds A's `del` of W (with `observed = {B: 8}` and `restore`), and B's snapshot, which holds review `r` (`seq = 9`). Apply the `del` first, then `r`. Repeat with `r` first. | The device creates the `DELETED` sync row from `restore`, then on `r` (uncovered) restores W from `restore.body`, inserts `r`, and publishes a `put` with `anc = [delFp, restore.fp]`. Same result in both orders. With `observed = {B: 9}`, or an occurrence covered the same way, W is not restored and `r` is ignored. A `del` without `restore` is not applied and deletes nothing. |
| C5b | A fresh device first ingests B's snapshot (review `r1` at `seq = 8`, concurrent `r2` at `seq = 9`, no word), then A's snapshot with the `del` (`observed = {B: 8}`, `restore`). Repeat with A's snapshot first, and with the order `del`, `r1`, `r2` and `del`, `r2`, `r1`. | In every order W is restored from `restore.body` with both `r1` and `r2` attached and the schedule derived from both; a published `put` has `anc = [delFp, restore.fp]`. If only `r1` exists, W stays deleted, `r1` is `COVERED`, and nothing is visible. An undone `r1` is never reattached. |
| C5c | Same as C5b but both snapshots are compacted and the deltas pruned. | Identical result: the covered `r1` is still present in B's snapshot. |
| C6 | A and B edit `notes`, then separately the definition group, an occurrence sentence and an occurrence locator, with equal timestamps and different values, then exchange them. Run merge with arguments swapped, with a provisional and a canonical occurrence in both iteration orders. | Merged canonical bodies and fps are byte-identical in every order. Neither device republishes a differing body: they meet in Agreement. |
| C7 | A snapshot holds a catalog `del` for K, annotation puts and a `del` for K, and `evaluation`, `series`, `metadata` and `position` records for K. Apply on a fresh device. | The annotations exist under `drive:<K>` with no `books` row and no shelf entry. The other four are `held` in `drive_record_state` with body, op and lineage, and no cursor stalls. A later annotation edit applies. |
| C7b | Continue C7: the reader later restores K (a `books` row for `drive:<K>` appears). A newer `evaluation` for K also arrives while it is held. | On the next run the held records are applied through §3.1 and `held` clears; the newer `evaluation` replaced the held one before that, losing nothing. Before the restore, no `books` row exists and K is not on the shelf. A concurrent held `evaluation` conflict stays hidden until the book exists. |
| C8 | Incoming keep-body resolution `R` for an annotation: Tx 1 commits, crash before Tx 2, the reader edits the annotation, restart. | Recovery builds `L` from the edit with `R.fp` in `anc`; replay is Stale; the edit stays dirty and is published; the conflict closes as `RESOLVED`; the cursor advances after Tx 2. Without the edit, replay is Agreement. |
| C9 | Catalog merge applied (new edition, `archiveHlc`) then crash before Tx 2. Variant (a) no edit, (b) the reader unarchives before restart. | (a) `drive_record_state.body_json` is restored from the receipt including `archiveHlc` and every `availabilityHlc`, with no republish unless `republish = 1`. (b) The unarchive becomes a new version with `R.fp` in `anc` and a new `archiveHlc`; no edition or identifier is lost. |
| C10 | A local edit lands between the apply decision and Tx 1. | The apply aborts, re-decides against the edited row, and the edit is never overwritten. |
| C11 | Crash before Tx 1, no receipt for `R.fp`. | Replay processes `R` from scratch with no receipt-based repair. |
| C12 | Apply a catalog record `R` fully (Tx 1 and Tx 2). Then make 65 or more local catalog versions, including new upload and clear availability groups, keeping the `books`-backed fields unchanged. Restart. | `R.fp` is no longer in `anc(L)`, but `receipt_id == acked_receipt_id`, so no recovery runs and the newer availability groups are kept. A crash between Tx 1 and Tx 2 of a later apply still recovers, because its `receipt_id` differs. |
| C13 | Device A runs with a default locale whose `%d` emits non-ASCII digits (for example `ar-EG`). A creates an HLC, a delta file name and a snapshot file name. | All three contain only ASCII digits, and equal byte-for-byte the output under `Locale.ROOT`. B orders A's HLC against its own by plain string comparison; a hand-built HLC with non-ASCII digits is rejected as invalid. |
| C14 | C8 with the reader **deleting** the annotation (not editing it) before restart. Variants: incoming `R` is a `put`; incoming `R` is a keep-body `resolve`. | Recovery sees projection `DELETED` against a live `row_fp`, mints a fresh HLC `h` and builds `L = delFp(d, k, h)` with `R.fp` (receipt `version_fp`) in `anc`. Replay of `R` is Stale, the annotation stays deleted, the `del` (`body: null`) is published, and the cursor advances after Tx 2. |
| C15 | C9 (b) variants: (a) the reader unarchives and a post-crash edition upload happens; (b) the reader only changes `title` after the crash. | (a) `archiveHlc` is minted once for the unarchive and every other edition, `availabilityHlc` and identifier is unchanged. (b) the receipt's `archiveHlc` is kept unchanged; no new `archiveHlc` is minted. |
| C16 | A deletes W, B's review `r` is uncovered, so W is revived on A with `reviveFp`. The reader then undoes `r`. The original `del` D (and the original `put` P) are replayed to A, and delivered late to B, in both orders. | `fp(revival) ≠ restore.fp`; D is Stale on every device (`D.fp ∈ anc(revival)`), P is Stale, and W stays live with the same body everywhere. Two devices that revive concurrently from D produce the same `reviveFp` and agree without a conflict. A record with `revives` whose `fp` is not `reviveFp(revives.del, revives.restore)` is rejected as malformed. |
| C17 | B authors review `r` (published, `seq = 5`), adopts A's later update to an occurrence B had also authored, then undoes `r` (final `del` at `seq = 7`). B compacts and prunes its deltas. A fresh device D reads only B's snapshot, with a word `del` from A that covers `r` (`observed = {B: 5}`) and another that does not (`observed = {B: 4}`). | The snapshot holds `r`'s `put` (`seq = 5`) and `del` (`seq = 7`) and B's authored occurrence record with its original `seq`, although `drive_record_state` holds only the adopted or deleted version. D reaches the same state as a device that read the deltas. Crashing between seq allocation and the outbox write leaves neither an authored row nor an outbox row. |
