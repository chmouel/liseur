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
| `peerId` | `"drive:" + permissionId` | Used in every per-peer table of `liseur.db` (`work_alias`, `sync_peer_state`, `furthest_position`, `peak_delivery`, `annotation_sync`, `evaluation_sync`, `metadata_override_sync`, `vocabulary_word_sync`, `vocabulary_review_sync`, `vocabulary_staged_review`, `drive_apply_receipt`). Position baselines also use per-device rows `"drive:" + permissionId + "/" + deviceId` in `sync_peer_state` (§4.5); disconnecting the account deletes both kinds. |
| `deviceId` | Lowercase UUIDv4 (`36` chars) | Generated once per install in `drive_sync.db` (`drive_install.device_id`), excluded from Android backup/transfer. Never derived from `DeviceIdentity.id`. |
| `hlc` | `String.format(Locale.ROOT, "%013d-%04x-%s", wallMs, counter, deviceId)` | Monotonic hybrid logical clock persisted in `drive_account(hlc_wall_ms, hlc_counter)`. Compared lexicographically (strict total order because `deviceId` breaks ties). On send: if `nowMs > wallMs`, `wallMs = nowMs; counter = 0`, else `counter += 1`. **Counter overflow:** when `counter` would become `0x10000`, do NOT widen the field — set `wallMs += 1` (a logical tick strictly ahead of any real clock reading) and reset `counter = 0`, so the fixed-width `%04x` stays 4 hex digits and lexicographic ordering stays monotonic across the overflow. On receive: `maxWall = max(localWall, nowMs, remoteWall)`; update `counter` accordingly. |
| `batchId` | `UUID.nameUUIDFromBytes("batch|$deviceId|$seq".toByteArray(UTF_8)).toString()` | Deterministic per `(deviceId, seq)` for idempotent retry deduplication in `appDataFolder`. |
| `workKey` | `"isbn:<isbn13>"` \| `"dc:<sha256hex>"` \| `"sha:<editionSha256>"` \| `"stub:<sha256hex>"` | For local/downloaded books, computed once `book_fingerprint` exists AND `books.file_metadata_checked = 1`. First match wins: (1) normalized ISBN-13 via `hardcover/Isbn.from` on `WorkIdentifiers.dcFrom(books.work_id, ...)` or `book_metadata(source='file').identifiers`; (2) `sha256Hex(trim.lowercase(id))` for any usable `dc:identifier` not in `USELESS_IDENTIFIERS`; (3) `book_fingerprint.sha256`. **Shared-identifier collision:** a local file's ISBN or `dc:` key falls back to `sha:<editionSha>` when that key already names a different edition (another local book, or a catalog entry whose `editions[]` lacks this sha) with a different normalized title+author, so two unrelated EPUBs carrying the same erroneous identifier stay separate. When two devices each mint the same key for different books before either sees the other's catalog, the receiver re-keys after the fact: on pulling a catalog entry under that key whose editions lack its local sha and whose normalized title+author differ, the device whose own entry was published later (higher HLC) moves its book to `sha:<editionSha>` and drops its edition from the shared entry, while the earlier publisher keeps the key. Known v1 limitations: this re-key happens only on the two devices holding the colliding files; a device holding neither (a catalog-only `drive:` stub) or a fresh install that reads both original entries can keep both editions under the shared key, because v1 has no durable split record; and book-scoped records the re-keyed device had already published under the shared key are not migrated. **Fileless preserved stubs on Server → Drive switch** (where `BookRemoval.downloadRemoved()` cleared `book_fingerprint` and `file_metadata_checked = 0`, or the book was never downloaded): captured **before** `forgetSyncPeer(server)` deletes server `work_alias` rows in priority order: (1) ISBN-13 from `books.work_id` or `book_metadata(source='catalog'\|'file').identifiers`; (2) usable `dc:identifier` from `books.work_id` or `book_metadata(source='catalog'\|'file').identifiers`; (3) `sha:<edition_sha>` from the retiring server's `work_alias.edition_sha`; (4) deterministic portable fallback `stub:<sha256Hex("liseur-stub\|v1\|" + book.url)>` when none of (1)–(3) exist (later mergeable with a downloaded EPUB via title+author confirmed matching emitting a `workAlias` `merge:` record). Upserted into `work_alias(peer_id = drive:<permissionId>, confidence = 'high', confirmed = 1, seeded = 0)` so preserved fileless books seed Drive immediately. Frozen in `work_alias.work_id` once published; a stronger identifier found later emits a `workAlias` record instead of rekeying. |
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
| `op: "put"` | `putFp(body, hlc) = sha256Hex(canonicalJson({"op":"put","body":<canonicalBody>,"hlc":<hlc>}))` | The record's authoring `hlc` (§1.1) is unique per version, so a version keeps its own identity even when its complete body recurs: a note changed X → Y → X while the wall clock returns to X's original `updatedAt` yields a third `fp`, never X's, so X and Y never become each other's ancestors. The HLC never regresses with the wall clock. `body` follows the same exclusions as `contentFp` (below). The `hlc` is minted when local diffing records the version and is stored with it (`drive_record_state.last_hlc`), so a retry or a restart re-sends the same `fp`. A put's `fp` cannot be recomputed from the row: it lives in `drive_record_state.version_fp`, in `sent_json.vfp` for `evaluation` and `metadata` (§3.2), or in the apply receipt (§3.3). |
| content identity (not a wire `fp`) | `contentFp(body) = sha256Hex(canonicalJson({"op":"put","body":<canonicalBody>}))` | Never sent as `fp`. Depends only on content fields (never `editionSha` for annotations, never `position`, never local-only columns). Recomputable directly from the committed `liseur.db` row for every domain whose body is fully stored there. `catalog` is not (`archiveHlc`, edition availability and `updatedAt` have no `books` column), so its recovery uses the apply receipt (§3.3). It is stored as `drive_record_state.content_fp` (and `acked_fingerprint`). It detects local edits (row against `content_fp`) and decides whether two live puts carry the same body (Agreement by content, §3.1 Step 2); it never identifies a version. |
| `op: "del"` | `delFp(d, k, hlc) = sha256Hex(canonicalJson({"op":"del","d":<d>,"k":<k>,"hlc":<hlc>}))` | Including `hlc` makes every delete event unique, so deleting a record after keeping it in an earlier `DELETE_EDIT` resolution never collides with the old delete in `anc`. |
| `op: "put"` revival (`vocabWord` only, §4.9) | `reviveFp(delFp, restoreFp) = sha256Hex(canonicalJson({"op":"revive","delFp":<delFp>,"restoreFp":<restoreFp>}))` | A revival is a wire `op: "put"` whose `fp` is `reviveFp`, not `putFp`. Its body is `restore.body`, so `drive_record_state.content_fp` (and `acked_fingerprint`) stay `contentFp(restore.body)` and local diffing compares the row against `content_fp`, never against `version_fp`. Because the row cannot reproduce `reviveFp`, the Tx 1 that makes a revival current also writes its `fp`, `anc` and `revives` to `drive_apply_receipt` (§3.3). Every device that revives from the same `del` computes the same `reviveFp`, so concurrent revivals agree (§3.1 Step 2). |
| `op: "resolve"` | `resolveFp(outcome, resolvedFps, hlc, body) = sha256Hex(canonicalJson({"op":"resolve","outcome":<outcome>,"resolvedFps":<sorted>,"hlc":<hlc>,"body":<bodyOrNull>}))` | Distinct from both conflicting fps even when the reader keeps a local version verbatim (`outcome: "put"`), guaranteeing strict causal supersession over both sides. |

**Golden test invariant:** Phase 4–6 unit tests include a golden fingerprint
suite that writes representative rows to `liseur.db`, reads them back, computes
`contentFp` and `putFp` (for a fixed `hlc`), and verifies exact SHA-256 equality against fixed fixtures. A
second fixture writes the same body at two different HLCs and asserts two
different `putFp` and one `contentFp`.

---

## 2. Wire Envelopes (`appDataFolder` & `Liseur/`)

### 2.1 File Inventory & `appProperties`

Every `appProperties` entry is strictly `≤ 124` UTF-8 bytes for its key and
value combined (Google Drive v3 limit).

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

*Change routing:* incoming changes route by `appProperties.kind`, or
`liseurKind=epub` for EPUBs. A removed change carries no `appProperties` and
routes by the `kind` cached in `drive_file` for its `fileId`. A removed or
trashed EPUB clears that edition's availability group (§4.2). A full rescan
after an invalid token reconciles cached and advertised EPUB IDs against the
whole inventory, treating each missing one as removed, before it commits the
new token (ADR 0078, Change feed).

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
batch; larger queues split into consecutive `seq` values (`1, 2, 3, ...`). A
single record can still exceed the size limit by itself (see **Oversized
records** below).

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
      "fp": "<64-hex putFp>",
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
| `resRefs` | `Array<String>` | Always | Sorted, deduplicated, **uncapped** list of winning resolution fps (`resolveFp`) from which this record causally descends, carried forward across every successor edit, delete, or resolution outside the 64-entry `anc` cap (`[]` for append-only domains and records with no prior resolution in their ancestry; excluded from `fp`). |
| `body` | `Object \| null` | Always | Domain payload object for `op == "put"` and `op == "resolve" && outcome == "put"`; strictly `null` for `op == "del"` and `op == "resolve" && outcome == "delete"`. |
| `observed` | `Object<deviceId, seq>` | Only when `d == "vocabWord" && op == "del"` (per record; a delta batch may also carry a batch-level `observed` object, used when the record has none) | Vector of the highest delta `seq` the deleting device had applied from each other device when it authored the delete — the causal boundary for the word's reviews and occurrences (§4.9) — **plus the deleter's own entry**: `observed[<deleter deviceId>]` is the `seq` of the batch that first carries the `del`, written in the freeze transaction that allocates that `seq` (§2.3). The own entry is part of the stored vector, so a child published by the deleter at or before that batch is covered by the same `observed[deviceId] >= seq` test as any other device's, and one published later (`A:6` after a delete at `A:5`) is not. No receiver derives the deleter or its batch from the envelope, a snapshot's `throughSeq`, or a republisher's batch. A value that is not an object decodes to `{}`, which keeps data, and a vector with no entry for the deleter covers none of the deleter's own children. Absent otherwise. Excluded from `delFp` (§1.3). Snapshots and `revives.delRecord` keep it unchanged. |
| `restore` | `Object` | Only when `d == "vocabWord" && op == "del"` (required there) | `{"fp": <version `fp` of the last live word version>, "hlc": <record hlc of that version>, "body": <that full vocabWord body, §4.9>}`: the parent content a receiver needs to restore the word when a review or occurrence it did not cover arrives later. It is the last live version the deleter held before deleting, so `fp` is `anc[0]`. The record's own `body` stays `null`, so the general `op: "del"` contract is unchanged. Excluded from `delFp`. Snapshots keep it on the `del` record, so a device rebuilt from snapshots has it. A `del` without a valid `restore` is malformed: it is not applied (no cascade, nothing is deleted), it counts as committed for the batch cursor (§3.3), and the receiver logs it. |
| `seq` | `Long` | Snapshot `vocabOccurrence` / `vocabReview` records only | The author's delta `seq` that first published this version. Readers store it as the record's provenance instead of `snapshot.throughSeq`, so a later `vocabWord` `del` whose `observed` vector covers that seq still counts the record as seen. `throughSeq` only advances cursors. A record with no known provenance counts as unseen. |
| `revives` | `Object` | Only when `d == "vocabWord" && op == "put"` and the put is a revival (§4.9) | `{"del": <delFp>, "restore": <restore.fp>, "delRecord": {...}}`. Required exactly when `fp` is a `reviveFp` (§1.3); a receiver recomputes `reviveFp(revives.del, revives.restore)` and rejects the record as malformed if it differs from `fp`. `delRecord` is the complete originating `del` as its author wrote it: `{"hlc", "anc", "resRefs", "observed", "restore"}` (the `del` fields above, without `d`, `k`, `op`, `body` and `fp`, which are `revives.del`; `observed` is the vector as authored, including the deleter's own entry, so the delete's original batch boundary survives the revival). It lets a receiver that never saw the `del` order it against a later concurrent delete (hlc, ancestry) and judge children against its `observed` vector (§4.9). The record is malformed, and not applied, if `delFp(d, k, delRecord.hlc) != revives.del` or `delRecord.restore.fp != revives.restore`. `anc` is `[revives.del, revives.restore]` followed by the revived version's other ancestry. Excluded from `fp`. Snapshots keep it on the revival record. |
| `origin` | `Object \| null` | `vocabReview` and `vocabOccurrence` records that re-send a version already published (an add-wins keep or a revival, §4.9) | `{"deviceId": <String>, "seq": <Long>}`: the provenance of the child version itself, copied from the republisher's stored provenance, so the version keeps the same `(deviceId, seq)` on every copy and a review first published as `C:5` still reads `C:5` when X re-sends it in batch `X:9`. It is also set when the republisher is the author (`origin = {X, <first seq>}`). `null` means the republisher never knew the provenance, and the copy counts as unseen. Absent on a first publication, whose provenance is the envelope's `deviceId` and the batch `seq` (the `seq` field for a snapshot record). Excluded from `fp`. Snapshots keep it. |

**One version of a `(d, k)` per batch.** A delta holds at most one record for
each `(d, k)`, so the authored-record key `(permission_id, domain,
record_key, seq)` (ADR 0078, `drive_authored_record`) never collides. Usually
only a record's latest version is owed. When a freeze finds two versions of
one `(d, k)` that both must reach the wire, it places them in consecutive
batches in version order, each with its own `seq`, `drive_outbox` row and
authored row, allocated in one freeze transaction. Two cases exist: a
`vocabReview` `put` still unpublished when the reader undoes it (the `put`
goes first, then the `del`, §4.11), and a local resolution awaiting
publication ahead of a later edit of the same record (the `resolve` goes
first, §3.2). A snapshot then holds both envelopes (§2.4) with no version
column, and receivers apply them in `seq` order. The count and size limits
above split between versions, never inside one batch.

**Oversized records.** A record is *oversized* when a batch holding only that
record would exceed 1 MiB, so splitting the queue cannot make it publishable.
The wire sets no length bound on an annotation `note` (§4.7) and `resRefs` is
uncapped on puts, deletes and resolutions alike, so one valid record can get
there. Liseur never truncates, drops or
rewrites such a record, and the local row is untouched. The record is
**withheld**:

- Every freeze skips it without allocating a `seq`, so unrelated records,
  including other records of the same domain, publish as usual and the
  oversized one never blocks a batch or a cursor. Its `drive_record_state`
  keeps `L` with `published_fp` unchanged, so it stays owed, and nothing
  extra is stored: the size is recomputed at each freeze.
- The sync run reports it as withheld. Because the record never enters
  `drive_authored_record`, snapshots built from that table never hold it
  either, and a published record (at most 1 MiB) always fits a 4 MiB snapshot
  part.
- Peers keep the last version they received. The next version of the same
  `(d, k)` that fits (the reader shortens the note) publishes normally, with
  the withheld versions' fps in its `anc` like any superseded version. A `del`
  has `body: null` and a capped `anc`, so it overflows only through its
  uncapped `resRefs` (or, for `vocabWord`, an unusually large `restore`), and
  the same rule applies to it. Two causes therefore behave differently:
  - **Body-only overflow** (a long note): deleting the record, or shortening
    the note, makes the next version fit, so the `del` publishes as a normal
    record.
  - **Ancestry overflow** (`resRefs` alone exceed 1 MiB): `resRefs` is carried
    forward across every successor, so a shortened body or a `del` inherits
    the same list and is just as oversized. Each such version is withheld the
    same way, stays owed, and is reported. The local row, including a local
    deletion, is unchanged, peers keep the last version they received, and
    nothing in the reader's reach shortens the list. A withheld `del` never
    enters `drive_authored_record`, so no snapshot holds it either.
- A local `resolve` that is oversized is withheld the same way and its
  conflict stays `RESOLVING`. It does not hold back a later version of the
  key whose own record fits: that version publishes alone (the "resolve goes
  first" rule above applies only to a resolution that can be published),
  carries `resolveFp` in `resRefs`, and moves the conflict to `RESOLVED`
  locally. A peer that never received the resolution keeps its own conflict
  open (§3.1 Step 0) and resolves it itself.

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
    "fp": "<64-hex version fp of the body below>",
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
     are never dropped in v1, and a `vocabWord` `del` keeps its `observed` vector (with the deleter's own entry, so its original batch boundary never becomes `throughSeq`) and `restore` payload.
   - For append-only domains (`session`, `vocabReview` including review `del`s,
     `furthest` resets): all authored records in full, so a review `put` stays in
     the snapshot beside the `del` that undid it.
   - `vocabOccurrence` and `vocabReview` records also carry `seq`, their original
     publication seq (Section 2.3), so delete observed vectors still apply to them.
     A republished child also keeps its `origin` (Section 2.3), and a revival
     keeps `revives.delRecord`, so a device rebuilt from snapshots judges
     children and orders deletes exactly as one that read the deltas.
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
   `del` paired with a `resolve` whose `outcome` is `"delete"`) and neither
   `fp` is in the other's `anc` (two **distinct** `op: "resolve"` records never
   agree here — they are routed to the incoming-resolution rule in Step 3
   first; a delete-resolution replayed after a local reset that descends from
   it is not agreement, it is Stale in Step 5 because `R.fp ∈ anc(L)`, so the
   reset keeps its upload debt and `published_fp` does not move), OR both `L` and `R` are live
   `put`s with equal `contentFp` (two devices that made the same edit, or
   merged to the same body, each minted their own `putFp`, §1.3) and neither
   `fp` is in the other's `anc`, OR `R.op == "resolve" && R.outcome ==
   "put"` and the committed `liseur.db` row already matches `contentFp(R.body)`
   with a matching per-peer ack after crash recovery):** No conflict. Merge
   `anc(L) := cap64(anc(L) ∪ R.anc)` and `res_refs(L) := res_refs(L) ∪
   R.resRefs`. For concurrent `del`s, adopt the higher `(hlc, fp)` as `L`,
   together with that `del`'s own `restore`, `observed`, `hlc` and `anc`
   (`restore_json`, `observed_json`, `del_json`, §4.9): the fingerprint,
   `restore` and `observed` of `L` always come from one record, and the losing `del`'s
   payloads are discarded, never copied beside the winning `fp`, so every
   delivery order of two distinct deletes yields the same causal boundary.
   A `del` `fp` embeds its authoring `hlc`, so it is immutable, and
   `last_hlc := max(last_hlc, R.hlc)` in every case, so the next minted
   version cannot reuse a clock the adopted record already used.
   For concurrent live puts with equal bodies, adopt the higher `(hlc, fp)`
   as `L` the same way (the row is unchanged) and keep the other `fp` in
   `anc(L)`; `published_fp := R.fp` only when `R` is adopted, because a
   retained local `L` is still owed. For
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
     local row in `liseur.db`, store the complete remote record envelope `R`
     (`op`, `fp`, `hlc`, `deviceId`, `seq`, `anc`, `resRefs`, `body`) in
     `drive_conflict.remote_json`, never `R.body` alone). Step 0 keeps `R.anc`
     and `R.resRefs` out of `anc(L)` and `res_refs(L)` while the conflict is
     open, so the stored envelope is their only copy.
   - One live `put`, one `del` → open `DELETE_EDIT` in `drive_conflict` (surface
     **Keep edited note** / **Delete note**), storing `R` the same way.
   - **Resolving a conflict locally** mints `op: "resolve"` with `anc :=
     cap64({L, R.fp} ∪ anc(L) ∪ R.anc)` and `resRefs := res_refs(L) ∪
     R.resRefs`, where `R` is read from the stored envelope, so a restart
     between opening and resolving loses neither parent's resolution bridges
     and a settled conflict does not reopen after compaction. A
     `drive_conflict` row whose `remote_json` holds only a body is never
     written.

### 3.2 Two-Database Commit & Crash-Recovery Matrix

Every apply and outbox ACK commits **Transaction 1 (`liseur.db`) first**, then
**Transaction 2 (`drive_sync.db`)**.

| Domain / Table | Atomic `liseur.db` Ack | Recovery if Crash Between Tx 1 and Tx 2 |
|---|---|---|
| `annotation` (`annotations` + `annotation_sync`) | `annotation_sync.acked_fingerprint = contentFp` (or `"DELETED"` on delete/delete-resolution), plus a `drive_apply_receipt` row (§3.3) in the same Tx 1. | `acked_fingerprint` alone cannot name the applied wire version: a keep-body resolution leaves it unchanged, so an edit made after the crash looks like one made before the resolution. Use the receipt. Receipt `version_fp == R.fp` and row unchanged since the apply (`row_fp` equal) → Agreement (repair `drive_record_state`, advance cursor). Row edited after the apply → post-apply edit rule (§3.3): the edit stays dirty, `R.fp` joins its `anc`, replay is Stale. No receipt for `R.fp` → Tx 1 never committed; apply normally. |
| `evaluation` (`book_evaluation` + `evaluation_sync`) | Revision-to-fingerprint adapter: `rev = max(row.revision, sync.acked_revision) + 1` is written to `book_evaluation.revision`, `acked_revision` and `sent_revision`, with `sent_json = {"vfp": "<fp>", "cfp": "<contentFp>", "rev": <rev>}`. Every Tx 1 that changes `sent_json.vfp` (remote apply, unchanged-outcome resolution, local resolution, outgoing ACK) strictly raises `acked_revision`. | When `drive_record_state.last_synced_revision < sync.acked_revision` (always true after a Tx 1-only commit, because of the strict raise): if `row.revision == sync.acked_revision`, recompute `contentFp` from committed `book_evaluation` row (or `"DELETED"` if `value == null`), pair with `sent_json.vfp`, and repair `drive_record_state`. If `row.revision > sync.acked_revision`, the post-crash edit becomes a new local version with `sent_json.vfp` first in its `anc`, kept dirty (`row.owed(sync) == true`). Its op follows the row: `value != null` is a put (mint a fresh HLC `h`, `L := putFp(<body built from the row>, h)`, `content_fp := contentFp(<body>)`); `value == null` (the reader cleared the rating or review) is a **delete** (mint `h`, `L := delFp(d, k, h)`, `version_op := "del"`, `body_json := NULL`), and the tombstone is owed. Recovery never builds a put from a null value (invalid, §4.8). |
| `metadata` (`books` + `metadata_override_revision` + `metadata_override_sync`) | Same revision-to-fingerprint adapter on `metadata_override_sync` (`acked_revision`, `sent_revision`, `sent_json`), with `rev` raised the same way on `metadata_override_revision.revision`, plus `remote_cover_sha256` & `adopted` for `cover`. | Identical to `evaluation`: recompute `contentFp` from committed `books` override columns when `rev.revision == sync.acked_revision`. If the reader edited again before recovery ran, the edit is a new local version with `sent_json.vfp` first in its `anc`: an override still set is a put (fresh HLC, `putFp`); an override the reader reset (`*_override = 0`, §4.3) is a **delete** (`delFp(d, k, h)`, `body_json := NULL`, tombstone owed), never a put rebuilt from the fallback file/catalog value, which would restore the cleared override. |
| `vocabWord` + `vocabOccurrence` (`vocabulary_*` + `vocabulary_word_sync`) | `vocabulary_word_sync.acked_fingerprint` + `acked_base_json`, plus a `drive_apply_receipt` row (§3.3) in the same Tx 1 for every word version that Tx 1 makes current: a remote apply, or a revival or add-wins keep this device mints (§4.9). | `acked_fingerprint` is `contentFp` of the row and cannot name a revival (`reviveFp`), so word recovery uses the receipt (§3.3), never `contentFp` of the row. After a crash between Tx 1 and Tx 2 of a revival, `L` is restored to `reviveFp` with its `anc`, `revives` and `del_json`, so a replay of the `del`, even after the triggering review is undone, is Stale. A word `del` that reaches Agreement rewrites `restore_json`, `observed_json` and `del_json` from `R` only when `R.fp` is (or becomes) `L`; when `L` is a distinct concurrent `del` that wins on `(hlc, fp)` (§3.1 Step 2), the stored payloads stay and `R`'s are discarded, so `fp`, `hlc`, `anc`, `restore` and `observed` never come from different deletes. Occurrences are a separate domain; their merge is commutative and idempotent (§4.10), so replay reproduces the same result without a receipt. A word `del` also stores its `restore` and `observed` payloads (`restore_json`, `observed_json`, §4.9). |
| `vocabReview` (`vocabulary_reviews` + `vocabulary_review_sync`) | `UNIQUE(wordId, syncId)` + `vocabulary_review_sync(peer_id, review_id, acked_deleted)`. | `INSERT OR IGNORE` + idempotent `reconcileSchedule`; replay inserts 0 rows and produces identical SRS state. |
| `session` (`reading_sessions` + `drive_session_ack`) | `UNIQUE INDEX` on `reading_sessions.remote_session_id`. | Incoming pull uses `INSERT OR IGNORE` — replay after crash inserts 0 rows, so session counts once. Outbound upload tracks per-account delivery in `drive_session_ack`. |
| `series` / `catalog` (`books`) | `drive_apply_receipt` row written in the same Tx 1 as the `books` writes (§3.3). `drive_record_state` stores `version_fp`, `published_fp`, `content_fp`, `body_json`. | Receipt recovery (§3.3) and local diffing run **before** pulling. The bare `books` row cannot reproduce a catalog wire fp (it has no `archiveHlc`, edition availability or `updatedAt`), so recovery compares a row projection with the receipt and never `contentFp(book)` with `contentFp(R.body)`. Row unchanged since the apply → restore `drive_record_state` from the receipt, advance the cursor on replay. Row edited after the apply → the edit becomes a new local version whose `anc` holds `R.fp`, so replay is Stale and no spurious `series` conflict opens. |
| `position` / `furthest` (`reading_progress` + `sync_peer_state` + `furthest_position`) | `sync_peer_state` account row (`acked_*`, delivery) and per-device rows (`acked_*`, `agreed_*`, `pending_*`), §4.5; `FurthestPositionDao.observe` (monotonic max). | `reconcileReadingState` and `observe` / `deleteUpToForOrigin` are strictly idempotent on replay. |
| Local conflict resolution (`drive_conflict` `RESOLVING`) | Before anything is applied, a `drive_sync.db` transaction writes `RESOLVING` with the exact resolution (`resolution_json`: `resolveFp`, `outcome`, `resolvedFps`, `hlc`, `anc`, `resRefs`, `body`). Tx 1 applies the choice to `liseur.db` and, for the receipt domains (`annotation`, `series`, `catalog`, `vocabWord`), writes a `drive_apply_receipt` row (§3.3, `local_version = 1`, `version_fp = resolveFp`) in the same transaction, and the Tx 2 that stores `L := resolveFp` with its `res_refs` also sets `acked_receipt_id := receipt_id`; `evaluation` and `metadata` raise `acked_revision` with `sent_json.vfp = resolveFp`. `drive_conflict.state` moves `RESOLVING → RESOLVED` after publish. | On restart, the stored resolution is never re-applied to `liseur.db` once its receipt (or raised `acked_revision`) exists: Tx 1 is recovered through §3.3, or the revision adapter above. Row unchanged since the apply → `L := resolveFp` with its `res_refs`, still owed. Row edited since (the reader edited or deleted after Tx 1 and before the batch was frozen) → the edit stays dirty as a descendant with `resolveFp` in its `anc`, never overwritten by the stored choice. Without a receipt Tx 1 never committed, and the choice is applied from `resolution_json`. Either way the stored `op: "resolve"` record is frozen and uploaded, in its own batch ahead of any later edit of the same record (§2.3; an oversized `resolve` is withheld instead and does not hold the edit back), `drive_resolution` and `res_refs_json` are persisted, and the row is marked `RESOLVED`. |
| Incoming `op: "resolve"` replay (`drive_conflict` `OPEN`) | Remote resolution outcome + per-peer ack/`sent_json` committed to `liseur.db` in Tx 1; `drive_resolution`, `drive_conflict` (`RESOLVED`), `res_refs_json`, and `drive_cursor` committed in Tx 2. | If crash occurs after Tx 1 and before Tx 2, recovery repairs `drive_record_state` from `liseur.db` and the apply receipt (§3.3) while the conflict stays `OPEN` and `drive_cursor` stays un-advanced. On replay of `R` (`op: "resolve"`), **Step 0 runs BEFORE Agreement (unedited) and BEFORE Stale (post-crash local edit)**, idempotently persisting `R` in `drive_resolution`, closing the `OPEN` conflict in `drive_conflict` as `RESOLVED`, updating `res_refs_json`, and only then advancing `drive_cursor`. |

### 3.3 Apply Receipt & Transaction Order

Equal row content never proves which wire version was applied: a keep-body
resolution can leave the row and `acked_fingerprint` untouched, a `vocabWord`
revival or keep (§4.9) has `fp = reviveFp` while the row holds
`restore.body`, and a catalog wire body cannot be recomputed from `books`.
Domains without a per-peer ack that carries the applied version fp therefore
get a receipt. `annotation`, `series`, `catalog` and `vocabWord` write one row
per record in a new `liseur.db` table `drive_apply_receipt`, in the same Tx 1
as the data. The Tx 1 of a remote apply writes one, and so does every Tx 1
that makes a version minted on this device current: a `vocabWord` revival or
keep (§4.9) and a local conflict resolution (§3.2). Migration 84→85 creates the table empty; no Drive data predates
it, so nothing is backfilled. `evaluation` and `metadata` already satisfy the
rule: `sent_json.vfp` is the applied `R.fp`, and `rev` strictly increases with
every local edit and every applied remote version (§3.2).

| Column | Meaning |
|---|---|
| `receipt_id` | UUIDv4 generated in the Tx 1 that writes the row, unique per apply. A replay after a crash reuses the id already stored for `R.fp`. |
| `peer_id`, `domain`, `record_key` | Primary key. One row per record; the next apply of the same record, or the next `vocabWord` revival or keep this device mints, overwrites it. Other local publishes never touch it. |
| `version_fp`, `version_op`, `outcome` | `R.fp`, `R.op`, and `R.outcome` (`NULL` unless `R.op == "resolve"`). |
| `hlc`, `origin_device_id`, `origin_seq` | `R.hlc`, `R.deviceId`, and `R.seq` (`throughSeq` for a snapshot record). |
| `anc_json`, `res_refs_json` | The `anc` and `res_refs` that `L` takes after the apply. |
| `body_json` | Canonical body as materialized, which for the `catalog` automatic merge is the merged body and can differ from `R.body`. `NULL` when the apply deleted the row. |
| `row_fp` | Fingerprint of the row projection after the apply, or `"DELETED"`. The projection is the set of body fields the domain stores in `liseur.db`: `annotation`, `series` and `vocabWord` use the whole body (minus `editionSha` for annotations); `catalog` uses `title`, `author`, `fileSeriesName`, `fileSeriesIndex`, `addedAt` and `archivedAt` only. |
| `republish` | `1` when `body_json` differs from `R.body`, so the merged body is owed as a new version. |
| `local_version` | `1` when this device minted the version in this Tx 1 instead of applying `R`: a `vocabWord` revival or add-wins keep (§4.9), where `version_fp` is the `reviveFp`, or a local conflict resolution (§3.2), where `version_fp` is the `resolveFp`, `version_op` is `"resolve"`, `outcome` and `body_json` are the chosen outcome and body, and `res_refs_json` includes `resolveFp`. `hlc` is the freshly minted HLC (the resolution's own) and `origin_*` are `NULL`. `0` for a remote apply. |
| `revives_json` | The `revives` envelope of a `vocabWord` revival or keep, including `delRecord` (§2.3), else `NULL`. Recovery restores `drive_record_state.del_json` from it. |

**Tx 2 acknowledgement.** The Tx 2 of every record that writes a receipt
writes `drive_record_state.acked_receipt_id := receipt_id` in the same
transaction as the rest of the record's state. That covers a remote apply
and the two receipts this device mints itself (`local_version = 1`): a
`vocabWord` revival or keep (§4.9) and a local conflict resolution (§3.2).
Their Tx 2 is the `drive_sync.db` transaction that records the minted
version (`L := reviveFp` or `resolveFp`, with its lineage), and it
acknowledges the receipt even though the version stays owed for publishing
(`published_fp` keeps its old value). Later local writes that create no
receipt (an ordinary edit, a local publish, `anc` truncation) never change
it; only the next receipt-writing Tx 2 or a recovery repair does. It is a
watermark, not ancestry, so it survives any number of later local versions
and the 64-entry `anc` cap. Without the acknowledgement, a restart would
treat a fully committed revival or resolution as interrupted, and a later
edit that returned to the receipt's body would make recovery restore the old
`reviveFp` or `resolveFp` over the newer version and its lineage.

**Recovery** runs before local diffing and before any pull. For every
receipt whose `receipt_id` differs from `acked_receipt_id` of the matching
`drive_record_state` row (a missing row counts as `NULL`), Tx 2 did not
commit. A receipt whose id matches is never re-applied, however old. Each
repair below sets `acked_receipt_id := receipt_id` in its own Tx 2. Recompute the row projection and compare it with `row_fp`:

- **Unchanged since the apply:** set `L := version_fp`, `version_op`,
  `anc`, `res_refs`, `origin_*` and `body_json` from the receipt, and
  `published_fp := version_fp`, unless `local_version == 1`: then
  `published_fp` keeps its old value, so the minted version (with `revives`
  from `revives_json`, or the stored resolution of §3.2 for a local
  resolution) is still owed. The replayed record is then the revival's
  trigger (a child or the `del`), which reaches an idempotent insert or
  Stale. If `republish == 1`, instead mint a fresh HLC `h`, make
  `L := putFp(body_json, h)` (`content_fp := contentFp(body_json)`) a new
  local version with `anc := [version_fp, <old L>] ∪ anc_json` and leave it
  unpublished, so the merged body is sent next. Replay of `R` then reaches Agreement.
- **Changed since the apply (post-crash edit):** the row projection differs
  from `row_fp`.
  - **Row still present:** build the new body from `body_json` with the
    projection fields replaced by the row's values, mint a fresh HLC `h`, set
    `L := putFp(<new body>, h)`, `content_fp := contentFp(<new body>)`, `anc := [version_fp, <old L>] ∪ anc_json`,
    `res_refs := res_refs_json`, and keep it dirty. Replay of `R` then
    reaches Stale because `R.fp ∈ anc(L)`. For `catalog`, only the
    row-backed fields are replaced; every edition, its `availabilityHlc`,
    identifiers and `updatedAt` stay as in `body_json`. `archiveHlc` is
    minted (a fresh HLC tick persisted in `drive_account`, before
    fingerprinting) **only when the row's `archivedAt` differs from
    `body_json.archivedAt`**; otherwise the receipt's `archiveHlc` is kept.
  - **Row deleted (`annotation`, `series` and `vocabWord`, where a local
    delete is a `del`, §4.4, §4.7, §4.9):** `body_json` is the last applied
    body but the
    projection is `DELETED`, so there is nothing to put. Mint a fresh HLC
    `h`, set `L := delFp(d, k, h)`, `version_op := "del"`,
    `anc := [version_fp, <old L>] ∪ anc_json`, `res_refs := res_refs_json`,
    `body_json := NULL`, the per-peer `acked_fingerprint` to `"DELETED"` where
    the domain keeps one, and keep it dirty. Replay of `R` then reaches Stale
    because `R.fp ∈ anc(L)`, the delete is published, and the incoming record
    never resurrects the row. A receipt with `row_fp == "DELETED"` and a row
    still absent is the *Unchanged* case above. For `vocabWord` the `del` is
    an ordinary local word delete (§4.9) with
    `restore = {fp: <receipt version_fp>, hlc: <receipt hlc>, body: body_json}`
    and `observed` taken from this device's cursors (the freeze adds the own entry, §2.3).
- In both cases Step 0 (§3.1) still runs on replay before the cursor
  advances.
- A `local_version` receipt of a local resolution whose row changed since
  the apply keeps the minted resolution owed: the stored `op: "resolve"`
  record (`drive_conflict.resolution_json`, §3.2) is frozen first, in its own
  batch (§2.3), and the post-crash edit, which has `resolveFp` in its `anc`
  and `res_refs`, follows in a later batch. The stored choice is never
  applied to `liseur.db` again.

A receipt whose `version_fp` differs from `R.fp` on replay, or no receipt,
means Tx 1 for `R` never committed. `R` is processed from scratch. A
`local_version` receipt never names `R`; recovery has already restored `L`
from it, and `R` is decided against that `L`.

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
   `acked_receipt_id`; the same holds for the Tx 2 of a local revival, keep or
   resolution) and, when this is the batch's last record of the
   domain, `drive_cursor` last. The cursor
   `drive_cursor(device, domain).applied_seq` names a whole batch, so it
   moves to the batch's `seq` only once every record of that domain in the
   batch has committed its Tx 2: in the Tx 2 of the last such record, or in
   a separate `drive_sync.db` transaction after it. For a snapshot, it moves
   to `throughSeq` only after every record of that domain in every part has
   committed. A crash inside a batch replays the whole
   `(device, domain, seq)`: records whose Tx 2 committed reach Agreement or
   Stale, or an idempotent insert (§3.2), and the rest apply. A held record
   (§4 intro) or a malformed `del` (§2.3) counts as committed once it is
   processed. A deferred record keeps the cursor below `seq`.
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
- `session` records insert under `drive:<workKey>` as usual. They are never
  held, and the tombstone never deletes them (§4.2).
- `metadata`, `series`, `evaluation` and `position` records for that
  `workKey` are **held**: the record is decided against `L` with the normal
  §3.1 cases, but Tx 1 writes nothing to `liseur.db`. Tx 2 stores the
  version in `drive_record_state` with `held = 1` (`version_fp`,
  `version_op`, `body_json` or `NULL`, `ancestors_json`, `res_refs_json`,
  `origin_*`, `last_hlc`; a resolution's outcome is in `drive_resolution`),
  and the record counts as committed for the batch cursor (§3.3). A held
  version is never discarded or overwritten
  except by a later version of the same record that §3.1 accepts, and a
  concurrent one opens a conflict in `drive_conflict` that the UI shows only
  once the book exists. Materialization: at the start of each run, after the
  `catalog` pass, every held record whose `workKey` now has a local `books`
  row (for `drive:<workKey>` or an alias target) is run through §3.1 as an
  incoming `R` built from the stored version, against whatever the restored
  book already has locally (`L = none` if nothing). A held `position` record
  belongs to another device's wire key (`<workKey>/<deviceId>`), so §3.1 alone
  would see it as unseen; its row write instead runs §4.5 for that device
  (`reconcileReadingState` and the explicit-choice reconciliation against
  `sync_peer_state(book_url, drive:<permissionId>/<deviceId>)`, a missing row
  meaning no baseline), so a newer local reading state on the restored book is
  compared, not overwritten, and a disagreement raises the same position
  prompt. Tx 1 applies it, Tx 2 clears `held`. Nothing in this path creates a `books` row, so arriving
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
    state, such as an edition upload, never overrides a later archive choice,
    and an archive change made locally but not yet collected is kept over a
    pull; `title`, `author`, `fileSeriesName`, `fileSeriesIndex` and
    `updatedAt` come from the body whose record has the higher `(hlc, fp)`.
  - **`hidden_at` is excluded** (local-only).
- **`del` semantics:**
  - Published **only** by explicit **Delete from synced library** (never by
    **Remove download** or watched-folder removal).
  - Receiver behavior:
    - If local row is `drive:<workKey>` with `local_uri == null`: remove the
      `books` row through a history-preserving `BookRemoval` path
      (**annotations and `reading_sessions` are kept**).
      `BookRemoval.forget()` deletes sessions and clears remote stats, so it
      is not used here. Kept sessions stay under
      `book_url = "drive:<workKey>"`, still count in statistics, and belong to
      the book again if it is restored. Sessions that arrive after the
      tombstone insert the same way (§4.12), so statistics match whether a
      session was applied before or after the `del`. The device that
      publishes the `del` follows the same rule.
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
  - **Exact anchors.** When the record's `editionSha` equals the local
    edition's and its `locatorJson` is an exact anchor, pass
    `exactPositionAgreement = ExactLocatorAnchor.agreement(local locatorJson,
    remote locatorJson)` to every `reconcileReadingState` call, as the
    liseur-sync path does. Otherwise pass `null` (percentage tolerance).
    Without it two distinct exact anchors 0.2 percentage points apart read as
    `InSync` and a short reading move is acknowledged without applying the
    locator.
  - On pull of device `X`'s record, run `reconcileReadingState(...)` using
    local `reading_progress` and the **per-device** row
    `sync_peer_state(book_url, drive:<permissionId>/<X>)`. Its `agreed_*` is
    the baseline last agreed with `X`. `localDirty` is
    `reading_progress.local_revision > acked_revision` of that row, and status
    acts use `status_revision` and `acked_status_revision` the same way. In a
    per-device row `acked_*` is the local revision last reconciled with `X`,
    never a delivery ack. A missing row means no baseline and `acked_* = 0`.
    - `InSync`: set `X`'s `agreed_progression`, `agreed_status` and
      `acked_revision` to the current local values. `acked_status_revision`
      and `agreed_status_choice` move only as the explicit-choice
      reconciliation below settles them, because `InSync` says nothing about
      `finishedOverride`.
    - `Pull`: apply `totalProgression`, `read_at`, `status` and
      `books.finished_at`, and `finished_override` as the explicit-choice rule
      below decides. Apply `locatorJson` **only when**
      `editionSha != null && editionSha == localEditionSha`; otherwise update
      `totalProgression` while keeping/deriving the local edition's locator.
      The same Tx 1 raises `local_revision` (and `status_revision` when status
      changes), because the adopted state is new relative to every other
      device, then sets `X`'s baseline and `acked_revision` to it (and
      `acked_status_revision` as the explicit-choice rule settles it).
    - `AdoptStatus`: apply `status` (and `finishedOverride` as the
      explicit-choice rule below decides) without overwriting the local
      locator. Raise `status_revision` and update `X`'s row as for
      `Pull`.
    - `Push`: keep local state and leave `X`'s row unchanged.
    - `Conflict`: call `SyncPeerStateDao.persistPending(...)` (and
      `persistPendingStatus(...)` for conflicting status acts) on `X`'s row so
      Liseur's existing position-choice UI resolves the conflict on next book
      open.
    - **Explicit status choice, reconciled separately.** `ReadingState`
      carries a `ReadingStatus` and a position, not `finishedOverride`, and at
      one reading position `automatic` and an explicit `reading` both map to
      `ReadingStatus.READING`. `reconcileReadingState` therefore returns
      `InSync` for a changed choice (`ReadingStateMerge.kt`, `sameSpot`), and
      acknowledging the status revision there would lose the choice on the
      receiving device. So for every record of `X`, whatever
      `reconcileReadingState` returned, the choice is reconciled on its own
      against a per-device baseline `agreed_status_choice` (a
      `ReadingStatusChoice` wire name, `NULL` when none was agreed). Let
      `local` be `reading_progress.finished_override`, `remote` the record's
      `finishedOverride`, and `localDirty` be `status_revision >
      acked_status_revision` on `X`'s row:
      - `local == remote`: set `agreed_status_choice := local` and
        `acked_status_revision := status_revision`.
      - `local != remote`, `remote != agreed_status_choice` (`NULL` counts as
        moved) and not `localDirty`: adopt `remote` (`finished_override`, the
        derived `status` and `books.finished_at`), raise `status_revision`
        like `Pull`, and set `agreed_status_choice` and `acked_status_revision`
        to the adopted state. This covers setting an explicit choice, and
        clearing it back to `automatic`, without any change of position.
      - `local != remote`, `remote == agreed_status_choice` and `localDirty`:
        keep `local`, leave `X`'s choice baseline and `acked_status_revision`
        unchanged (the local choice is still owed, as for `Push`).
      - `local != remote`, `remote` moved and `localDirty`: the choices
        conflict. Call `persistPendingStatus(...)` with `pending_status_choice
        = remote`, and leave the baseline and `acked_status_revision`
        unchanged. Resolving the prompt sets both.
      The `Pull` and `AdoptStatus` branches apply `finishedOverride` only
      through this rule. A fresh device has no baseline, so a remote choice
      different from a clean local one is adopted, and one that differs from a
      local choice the reader already changed opens this single prompt.
  - Intentional backward moves are preserved because `sync_peer_state.agreed_*`
    tracks the last agreed baseline.
  - **Delivery is not agreement.** The account row
    `sync_peer_state(book_url, drive:<permissionId>)` holds only delivery:
    `acked_revision` and `acked_status_revision` record that a local change
    reached this device's own Drive stream (upload selection,
    `unreadResetAt`). It never holds `agreed_*` or `pending_*` and is never
    the baseline of a pull, so uploading never makes a local change look
    reconciled. Agreement lives only in per-device rows, so agreeing with one
    device never moves the baseline used against another: after A agrees with
    B at 60%, C's unchanged 50% matches A's baseline with C and A keeps 60%
    (`Push`). Concurrent positions on two devices open `Conflict` whether the
    local upload or the pull happens first. On a fresh device the first
    `Pull` raises `local_revision`, so a second stream with a different
    position opens `Conflict` in either enumeration order. Required tests:
    upload before pull, three devices (C19), and fresh-device reconstruction
    in both enumeration orders. The wire format cannot prove that a writer
    saw another writer's position before moving on, so a sequential handoff
    can also require a choice when the intermediate agreement was not seen.
    A pending writer position retains its HLC so seeding cannot replace a
    newer conflict with an older cached record. The account row records the
    revision of an imported unread reset separately from a local reset.
- **`del` semantics:** Not used ("Mark unread" is a `put` with
  `finishedOverride = "unread"` and non-null `unreadResetAt`).
- **Room mapping (`liseur.db`):**
  - `reading_progress`: `book_url`, `locator_json`, `total_progression`,
    `updated_at`, `read_at`, `status`, `local_revision`, `finished_override`,
    `status_revision`, `position_revision`, `unread_reset_revision`, `coverage`,
    `status_retired_revision`.
  - `sync_peer_state`, two kinds of row per book: the account row
    `peer_id = drive:<permissionId>` (delivery only: `acked_revision`,
    `acked_status_revision`), and one row per other device
    `peer_id = drive:<permissionId>/<deviceId>` (`acked_revision`,
    `acked_status_revision` as reconciled local revisions,
    `agreed_progression`, `agreed_status`, `agreed_status_choice`, `pending_progression`,
    `pending_locator_json`, `pending_edition_sha`, `pending_status`,
    `pending_coverage`, `pending_updated_at`, `has_pending`,
    `has_pending_status`, `pending_status_choice`,
    `pending_status_updated_at`, `remote_updated_at`, `synced_at`). The key is
    the existing `(book_url, peer_id)`. Migration 84→85 adds the nullable
    column `agreed_status_choice TEXT` (`NULL` for every existing row, which
    means no agreed choice).
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
    binding is dropped when the book's peak changes. Internal `_`-prefixed
    domains are never published.
  - Merge rule: ignore if `seq <= maxFloor[workKey][deviceId]`; otherwise apply
    via `FurthestPositionDao.observe` (keeps max `progression` per
    `(peer_id, work_id, edition, origin = deviceId)`). A peak that `observe`
    does not make the visible row because its `progression` is lower, but
    whose `seq` is higher than the visible row's, is a **newer peak
    candidate** and is retained, not dropped: B's 80% peak at `seq = 10` and
    its post-reset 30% peak at `seq = 11` are both kept, because a reset
    with floor 10 deletes the first and must leave the second. Candidates
    live in `drive_sync.db` (`drive_peak_candidate`, one row per
    `(permission_id, work_id, edition, origin, seq)` with `progression` and
    the payload), and a candidate is discarded as soon as another peak of the
    same slot has `seq` at or above its own and `progression` at or above its
    own, so each slot holds a short chain of rising `seq` and falling
    `progression`. `furthest_position` holds the slot's maximum-`progression`
    row, so the visible state is unchanged when no reset ever arrives.
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
    floor)`), delete the retained candidates of the same slots with
    `seq <= floorSeq`, **re-materialize** each affected slot's visible row
    from its best remaining candidate (through `observe`), and persist
    `maxFloor[workKey][originDeviceId]` in `drive_sync.db` so late-arriving
    peaks with `seq <= floorSeq` are ignored. Resets are retained forever in
    snapshots. The visible peak is therefore a function of the set of peaks
    and floors received, never of their delivery order: a peak above the
    floor survives whether it arrives before or after the reset.
  - Forgetting applies to the whole work, not one edition. The reset record,
    its floors (the authoring device's floor is its highest allocated `seq`)
    and a local-only replay marker are persisted in `drive_sync.db` first.
    Local effects follow: delete
    the covered `furthest_position` rows, reset the local `reading_progress`
    peak of every local edition of the work whose bound `seq` is at or below
    its device floor, and clear that edition's `peak_delivery`. Resetting the
    local peak is required: otherwise a book that was at 80% and is now at 20%
    could not record a new peak at 30%, because capture needs a strict
    increase. A peak that is unsent or newer than the floor is preserved. The marker is
    cleared last; each sync replays any remaining marker before pulling, so an
    interrupted forget completes.
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
  in `annotations` and the remote envelope in `drive_conflict.remote_json`; `DELETE_EDIT`
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
  `op: "del"`, including when the clear happens after a crash between the two
  commits and before recovery (§3.2), where it is never rebuilt as a put.
  Applying remote `del` sets `book_evaluation.value = null` and
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
    publish (or skip) a put of that body, each with its own `putFp`, and meet
    in Agreement by content (§3.1 Step 2). Equal
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
    version. A child is **covered** when `observed[deviceId] >= seq`. The
    vector includes the deleter's own entry (its batch `seq`, §2.3), so the
    deleter's own children need no separate rule: one published at or before
    the delete's batch is covered, and a later one (`A:6` after a delete at
    `A:5`) is not. No batch number, `throughSeq` or carrier ever stands in for
    that entry. A child is **uncovered** in every other case, including no recorded
    provenance, a snapshot record without `seq`, and a local review or
    occurrence edit that is still unpublished (no batch `seq` yet). Reviews
    are append-only and an occurrence's edits live in its own domain, so
    neither is part of `anc(L)`, and `anc(L)` alone cannot prove the
    deleter's word version descends from the current children.

    **Provenance belongs to the child version, not to the copy that carried
    it.** A device that re-sends a child version it already published (an
    add-wins keep or a revival, below) puts that version's own provenance in
    `origin` (§2.3), so the first copy and every later copy name the same
    `(deviceId, seq)`: a review first published as `C:5` is still `C:5` when
    X re-sends it in batch `X:9`, and the republisher's own batch never
    stands in for it. A child whose provenance the republisher never knew is
    re-sent with `origin: null` and stays uncovered. A device keeps the best
    provenance any copy has shown it: a known provenance replaces an unknown
    one, and a repeated copy of a held child changes nothing else. Which copy
    arrives first therefore never decides coverage. Local storage for it is
    `origin_device_id` / `origin_seq` on the child's `drive_record_state` row
    (occurrences, and the `vocabReview` key of a review) and on its
    `vocabulary_staged_review` row once it is non-live.
  - **Child history states.** A review or occurrence of a word is **live**
    (a row in `vocabulary_reviews` / `vocabulary_occurrences`), **staged**
    (parent absent, not yet judged) or **covered** (a delete's `observed`
    vector covers it, so it is non-live history). A review can also be
    **undone** (final undo, `vocabulary_review_sync.acked_deleted`). Staged,
    covered and undone children are never shown, counted, searched or fed to
    `reconcileSchedule`, and nothing in the UI changes while the delete is in
    force. Reviews are kept in `vocabulary_staged_review`, which gains in
    migration 84→85 `state` (`STAGED` / `COVERED` / `UNDONE`),
    `origin_device_id` and `origin_seq` beside its existing review fields.
    Drive rows write `0` to the liseur-sync columns `word_generation` and
    `seq`. Occurrences are kept as `drive_record_state` versions with
    `held = 1` (version, `body_json`, `origin_*`). An undo moves a live,
    staged or covered review to `UNDONE`, and a review `put` whose `syncId` is
    already undone is stored as `UNDONE` instead of dropped. Both use the
    put's provenance.

    **Arbitration is a function of the facts held, never of arrival order,
    carrier or child label.** For one word the facts are the deletes this
    device knows and the children it holds with their best-known provenance.
    A delete is known whether it arrived as a delta record, as a snapshot
    record, or inside the `revives.delRecord` of a revival put (§2.3), so a
    device that adopted a revival and never received the `del` still has its
    `hlc`, `anc`, `observed` and `restore` (stored in `del_json`,
    `observed_json` and `restore_json`). The delete in force, `D*`, is the
    highest `(hlc, fp)` among the known deletes that are concurrent with one
    another (§3.1 Step 2); a delete in the `anc` of a genuine word edit (a put
    that is not a revival) is Stale and never counts. The word is deleted
    exactly when every child held is covered by `D*.observed`. It survives,
    or is revived from `D*.restore`, when some child is uncovered: a live,
    `STAGED`, `COVERED` or `UNDONE` review, or a live or held occurrence
    version. An undo is final for the review but never withdraws it as
    evidence, and `UNDONE` reviews are never attached.

    `STAGED`, `COVERED`, `UNDONE` and the word's live or absent state are
    outputs of that verdict, never inputs to it. Every new fact, whether a
    delete in any carrier or any copy of a child (including a repeat that only
    improves its provenance), recomputes the verdict over all children
    whatever their current label and relabels them in both directions: a
    `COVERED` child that the new `D*` does not cover revives the word, and a
    `STAGED` or live child that it covers becomes `COVERED`. A lower delete
    changes nothing, because `D*` stays. A revival `put` is not evidence
    against any delete: it contributes its `delRecord` to the known deletes
    and its body as the live version.
  - **Delete vs. uncovered child:** before any cascade delete (Tx 1), every
    live local review, every `UNDONE` review and every live local occurrence
    of the word must be covered. If any is uncovered, the delete is a
    concurrent write and **add-wins silently**: keep the word with *all* its
    live children and republish every retained review and occurrence as fresh
    delta records (same content, new `hlc`, `origin` set to the version's own
    provenance, §2.3). How the kept word is published
    depends on whether `L ∈ D.anc`.
    - **`L ∈ D.anc`** (the word is unchanged since a version the deleter
      saw): the keep is a revival. Tx 1 writes `restore.body` (already the
      row when `L == restore.fp`) and the receipt (§3.3,
      `local_version = 1`). Tx 2 records `fp = reviveFp(D.fp, restore.fp)`,
      `revives = {del: D.fp, restore: restore.fp, delRecord: <D's envelope>}`,
      `anc = [D.fp, restore.fp] ∪ anc(L)`, `acked_receipt_id := receipt_id` and
      `content_fp := contentFp(restore.body)`, as in **Revive** below.
      Republishing a plain `putFp` put of `restore.body` would leave `L ∈ D.anc`, so a replay of `D`
      after the child is undone would pass Step 4 before Step 5 and delete
      the word again.
    - **`L ∉ D.anc`, `L` not a revival** (a genuine concurrent word edit):
      the republished `put` keeps a `putFp` put (fresh HLC) of the local body
      with `D.fp` in `anc`. A replay of `D` is then Stale.
    - **`L ∉ D.anc`, `L` is a revival** (`L` carries `revives = {del: D'.fp,
      restore: …, delRecord: …}`, minted here or adopted from a peer, or
      from a snapshot, without ever receiving `D'` itself) **and `D` is
      concurrent with `D'`** (distinct deletes, neither fp in the other's
      `anc`, judged from `del_json`): the revival is a synthetic version, not
      a reader edit. It only says that `D'` lost to an uncovered child, so it
      never counts as independent evidence against `D`. The two deletes are
      arbitrated as in §3.1 Step 2, by the higher `(hlc, fp)`, and the
      evidence is judged again against the winner:
      - `D` is higher: `D` is the delete in force and its `restore_json` and
        `observed_json` replace those of `D'`. Judge every child received
        against `D.observed` as in **Tombstone arrives**. If all are covered,
        the word is deleted with the cascade above (`L := D.fp`, attached
        reviews back to `COVERED`, occurrences `held = 1`, `del_json` from
        `D`) and the revival is retired. If one is uncovered, the word stays live as a new revival
        `reviveFp(D.fp, D.restore.fp)` with `anc = [D.fp, D.restore.fp] ∪
        anc(L)`, republished with the retained children.
      - `D` is lower: `D'` stays the delete in force, `L` stays and `D.fp`
        joins `anc(L)`, so a replay of `D` is Stale. `D`'s payloads are
        discarded.

      The outcome therefore equals the one reached by receiving `D` and `D'`
      before the child, whatever the interleaving of `D`, `D'` and the child,
      and whether `D'` was seen directly or only through the revival.
      The mirror case follows the same rule: a revival `put` `R` that reaches
      a device whose `L` is a `del` `D` is not an edit either. If
      `R.revives.del == D.fp` it supersedes `D` causally (`D ∈ R.anc`).
      Otherwise `R.revives.delRecord` is a known delete concurrent with `D`
      and the higher `(hlc, fp)` is `D*`: when `D` wins and covers every
      child held, `R` is discarded (`R.fp` joins `anc(L)`), and when any child
      is uncovered the word is revived from the winner.
      A word edited after the revival is an ordinary `put` (it has no
      `revives`) and follows the genuine-edit rule above.

    If all children are covered, the cascade does not discard them: Tx 1
    moves each live review to `vocabulary_staged_review` as `COVERED`, and
    Tx 2 marks each occurrence's `drive_record_state` `held = 1`, so covered
    children stay recoverable.
  - **Restore payload:** every `vocabWord` `del` carries `restore`
    (`{fp, hlc, body}`, §2.3), the last live word body the deleter held, and
    `observed`. The deleter fills it from its word row in the same Tx 1 that
    deletes it. The freeze that places the `del` in batch `s` adds the
    deleter's own entry `observed[<own deviceId>] = s` in the same
    transaction that allocates `s` and writes the authored row (§2.3), so the
    stored and published vector are identical. A receiver applying the `del` stores it in Tx 1 by keeping
    (or, on a device that never had the word, creating) the
    `vocabulary_word_sync` row with `acked_fingerprint = "DELETED"` and
    `acked_base_json = restore.body`, and in Tx 2 by writing
    `drive_record_state.restore_json = restore` and `observed_json =
    observed`. It always uses the `restore` of the `del` that is `L` (§3.1 Step 2: with
    distinct concurrent deletes, the winner's `restore` and `observed` travel
    with the winner's `fp`), not its own last body, so every device holds the
    same parent baseline. The payload is
    replicated through deltas and snapshots like any other record field, so a
    device that reconstructs only from snapshots has it.
  - **Child arrives:** the same coverage test against the tombstone's
    `observed_json` applies on every arrival path (delta or snapshot).
    - Word live: attach as normal.
    - Word absent, no tombstone known: store the child `STAGED` / `held`.
    - Tombstone known, child covered: store it `COVERED` / `held`. The word is
      not restored.
    - Tombstone known, child uncovered: **revive** (below).
    - Child already held (a repeated copy, such as a republication by another
      device): insert nothing, but record any better provenance and judge
      again as under **Tombstone arrives**, so the verdict is the same
      whichever copy came first.
    - An undone review's `put` is judged the same way: an uncovered one keeps
      or revives the word but is stored `UNDONE`, never attached.
  - **Tombstone arrives:** after storing `restore_json`, `observed_json` and
    `del_json` (above), when the arriving `del` is, or becomes, `D*`, judge
    every child already held for the word against `D*.observed`, whether
    `STAGED`, `COVERED`, `UNDONE` or live. A child received before the
    tombstone is treated the same as one received after it, and a child that
    an earlier, lower delete covered is judged again. If the word is live, an
    uncovered live, staged, covered or `UNDONE` child keeps it (add-wins,
    published as in **Delete vs. uncovered child**). If the word is absent,
    every child covered by `D*` is labelled `COVERED` (an `UNDONE` review
    keeps its state) or, if any `STAGED`, `COVERED` or `UNDONE` child is
    uncovered, the word is **revived** in the same Tx 1. A lower delete that
    is not `D*` is discarded and changes no label.
  - **Revive:** one Tx 1 restores the word row from `restore_json.body`,
    then attaches the **eligible child set**: every `STAGED` or `COVERED`
    review and occurrence of the word, plus the arriving child unless it is
    `UNDONE`. `UNDONE` reviews are never attached. It re-runs
    `reconcileSchedule` and sets `acked_fingerprint`. Tx 2
    clears `held` on the attached occurrences, records a new word `put`
    version with `fp = reviveFp(delFp, restore.fp)` (§1.3), body
    `restore.body`, `revives = {del: delFp, restore: restore.fp, delRecord:
    <`D*`'s envelope>}` and `anc = [delFp, restore.fp]`, `del_json` from
    `delRecord`, sets `content_fp := contentFp(restore.body)`, and
    the device publishes it and the attached children. The distinct `fp`
    means the original `del` is in the revived version's `anc` but the
    revived `fp` is in no `del`'s `anc`, so a replay of that `del` (for
    example after the reader undoes the uncovered review that triggered the
    revival) is Stale under §3.1 Step 5 and can never supersede the revival
    in Step 4. A later `del` of the revived word has `restore.fp =
    reviveFp` and `anc[0] = reviveFp` as usual. The same set is
    attached whichever order the `del`, covered children and uncovered child
    arrived in. A device that adopts a peer's revival `put` instead of
    minting one stores `restore_json`, `observed_json` and `del_json` from its
    `revives.delRecord`. A word `put` received later that supersedes the tombstone
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
    `del` with `restore` and `observed`, and every child with its `seq` (or
    `origin`), and reaches the same state in any order. A device that
    receives a revival without the `del` it retired still holds that `del`
    in `revives.delRecord`, which a snapshot keeps (§2.4), so compaction
    never removes the delete an arbitration needs.
  - A `del` with no valid `restore` was never applied (§2.3), so the word
    and its children are still present locally and there is nothing to
    restore.
  - `del` vs. concurrent edit of the word: **add-wins silently**, keeping the
    local word and children, and republishing as in **Delete vs. uncovered
    child** (a revival when `L ∈ D.anc`, otherwise a `putFp` put with the
    `del` fp in `anc`).

  - **Durable delete boundary:** when a `del` takes effect (applied remotely,
    or our own acknowledged), the device keeps the per-word max-merge of its
    `observed` vector as local-only record state, never published. A later
    `vocabReview` `put` whose `(deviceId, seq)` provenance that vector covers
    is neither staged nor inserted, so a delayed, replayed or reordered
    historical review cannot reattach to a re-collected word. Staged reviews
    the vector covers are dropped when the `del` takes effect; uncovered,
    concurrent ones are kept.
  - **Review undo vs. word delete:** only an explicit undo publishes a review
    `del`. The per-review sync row is created before the `put` is published, so
    an undo during an unacknowledged upload is still recorded. A review that
    disappeared because its word was deleted, or while duplicate rows were
    coalesced, is never published as an undo. A pulled review `del` removes
    every local copy of the event, including duplicate unowned and
    retired-account words, and a legacy null-`syncId` review whose
    deterministic id (§4.11) is already tombstoned is deleted rather than
    backfilled.
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
    reviews are stored `COVERED`, uncovered ones restore the word. Staged
    reviews are
    never dropped, and a re-sent review (revival republish) is insert-if-absent
    by `syncId`, except when that `syncId` was undone. A re-sent copy still
    replaces unknown provenance with its known `origin` and triggers a fresh
    judgement against the delete in force (§4.9).
  - `op: "del"` (undo review): **final tombstone**. Deletes the matching
    `vocabulary_reviews` row, moves the review (live, `STAGED` or `COVERED`) to
    `vocabulary_staged_review` as `UNDONE` with its put's provenance, sets
    `vocabulary_review_sync.acked_deleted = true`, and re-runs
    `reconcileSchedule` on the parent word. A later replay of the undone
    `syncId`'s `put` never reaches the schedule: it is stored `UNDONE` if not
    already present. An `UNDONE` review stays evidence for the parent-existence
    rule (§4.9). **Undo before its put:** a review `del` has `body: null`, so it
    carries the `syncId` and nothing else, not the `language` and `normalized`
    that `vocabulary_review_sync` requires, and no row can be written in
    `liseur.db` for it. When no review with that `syncId` is held, live,
    staged, covered or undone, the receiver writes an **ID-only durable
    tombstone** in Tx 2: the `drive_record_state` row for the `vocabReview`
    key (`record_key = syncId`) with `version_op = del`, `body_json = NULL`
    and the `del`'s `version_fp`, `last_hlc` and `origin_*`. It changes
    nothing in `liseur.db`, never touches the schedule, and counts as
    committed for the batch cursor (§3.3), so a delta never stalls on it and a
    snapshot never drops it. Every arrival path of a review `put` (delta,
    snapshot, or a revival's republished copy) consults that row by `syncId`
    before inserting. If the row is a `del`, the put is stored `UNDONE` in
    `vocabulary_staged_review` with the put's provenance (its `origin` when
    present), the `vocabulary_review_sync` row is created with the put's
    `language` and `normalized` and `acked_deleted = true`, and the
    schedule is not updated, exactly as for a put that arrives after a
    local undo. The stored `UNDONE` review is then evidence in the same
    way, so the result is the one reached when the put came first. The
    tombstone row stays after the put is stored, and a repeated put or undo
    changes nothing. The `put` and its `del` share one `syncId`, so when both are
    owed before the next sync they freeze in separate, consecutive batches,
    the `put` first (§2.3); each keeps its own authored row and snapshot
    envelope.
- **Room mapping (`liseur.db`):**
  - `vocabulary_reviews`: `wordId`, `rating`, `reviewedAt`, `intervalMinutes`,
    `dueAt`, `firstReview` (derived), `syncId`.
  - `vocabulary_review_sync`: `peer_id = drive:<permissionId>`, `review_id =
    syncId`, `local_review_id`, `language`, `normalized`, `acked_deleted`.
  - `vocabulary_staged_review`: non-live review history (`state` `STAGED`, `COVERED` or `UNDONE`, with `origin_device_id`, `origin_seq` and the review fields), §4.9.

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
  - A catalog tombstone for `workKey` neither holds nor drops sessions: they
    insert under `drive:<workKey>` and the `del` keeps the ones already applied
    (§4.2).
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
| `catalog` + EPUBs | A connects, syncs catalog entry for whole library; selects 1 book in **Upload EPUB files** → B sees whole catalog (`REMOTE`), downloads selected EPUB on open and verifies SHA-256. | Concurrent `archived_at` toggle resolves silently to higher HLC; concurrent edition uploads union cleanly in `editions[]`. | **Remove download** frees local file only; **Delete from synced library** removes `drive:` row on B (keeping annotations and reading sessions) and unlinks local-file copy on C; Drive-side EPUB trash clears `driveFileId` while keeping history. | `hidden_at` stays local; `drive:` row never sets `remote_uuid`; concurrent first connection creating two `Liseur` folders picks lowest `(createdTime, fileId)` and reads EPUBs from both. |
| `metadata` + Custom Covers | A overrides title, author, description, and sets custom cover → B applies all 4 overrides, including displaying the custom cover on a `REMOTE` catalog entry with no local EPUB. | Both A and B edit `title` offline and upload before pulling → both open per-field `EDIT_EDIT`, reader resolves on A, B closes conflict and converges. | A resets `author` override while B untouched → silent reset on B; A resets while B edits → `DELETE_EDIT` prompt (**Keep** / **Delete**). | Cover > 1200 px / > 512 KiB scales down via WebP ladder; uncompressable cover refused gracefully; crash between `liseur.db` and `drive_sync.db` recovers via `MetadataOverrideSync` revision-to-fp adapter. |
| `series` | A drags 5-book shelf into custom order → B displays identical 1…5 order. | A and B reorder the same 5-book shelf offline → grouped into **one** shelf conflict by `seriesKey`; resolving emits per-book `op:"resolve"` and converges all 5 books. | A clears series order while B untouched → B reverts to file/catalog series; concurrent clear + reorder → `DELETE_EDIT` shelf prompt. | Book explicitly filed under "no series" (`seriesOverridden=true, userSeriesName=null`) stays un-shelved on B despite file metadata. |
| `position` | A reads to 42%, B pulls exact locator (matching edition) and opens at exact passage. | A reads forward to 60%, B reads to 55% offline → `SyncDecision.Conflict` persists in `sync_peer_state` and triggers existing position-choice UI; intentional backward reread on A from agreed 50% → 20% pulls cleanly onto untouched B; A agrees with B at 60% while C last reported 50% and is unchanged; A pulls C and stays at 60%. | "Mark unread" on A emits `finishedOverride="unread"` + `unreadResetAt`, resetting untouched B and outranking stale `Finished`. | Different editions of same work apply `totalProgression` without corrupting local `locatorJson`; skewed device clocks cannot overwrite deliberate rereads. |
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
     `drive_resolution`, and marks `RESOLVED`. Add the variant where the
     reader edits the field after the crash and before recovery (C27): the
     stored choice is not re-applied over the edit.
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
   - Expired `pageToken` (HTTP 400/404) triggers idempotent full rescan that
     also clears the availability of EPUBs deleted while the device was offline (C22).
   - Process death after delta upload before outbox ACK deduplicates via
     `appProperties.batchId` without creating duplicate delta files.
   - Unsupported `schemaVersion` pauses device stream with upgrade notice;
     unsupported domain version pauses only that `(device, domain)` cursor.
   - Clean reinstall on a fresh device reconstructs catalog, custom covers,
     metadata overrides, series, positions, annotations, evaluations,
     vocabulary, and reading stats from Drive alone.

### 5.3 Contract Acceptance Cases

These cases test the contracts in §1.1, §1.3, §2.1, §2.3, §2.4, §3.1–§3.3,
§4.2, §4.3, §4.5, §4.6, §4.8–4.12 and the §4 intro. Each runs on the §5.2 harness. Cases marked "both
orders" run once per delivery
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
| C6 | A and B edit `notes`, then separately the definition group, an occurrence sentence and an occurrence locator, with equal timestamps and different values, then exchange them. Run merge with arguments swapped, with a provisional and a canonical occurrence in both iteration orders. | Merged canonical bodies and `contentFp` are byte-identical in every order (each device's `putFp` differs, because its HLC does). Neither device republishes a differing body: they meet in Agreement by content (§3.1 Step 2), and both end on the same `L`, the higher `(hlc, fp)`. |
| C7 | A snapshot holds a catalog `del` for K, annotation puts and a `del` for K, and `evaluation`, `series`, `metadata` and `position` records for K. Apply on a fresh device. | The annotations exist under `drive:<K>` with no `books` row and no shelf entry. The other four are `held` in `drive_record_state` with body, op and lineage, and no cursor stalls. A later annotation edit applies. Reading sessions for K are inserted under `drive:<K>` and counted in statistics. |
| C7b | Continue C7: the reader later restores K (a `books` row for `drive:<K>` appears). A newer `evaluation` for K also arrives while it is held. | On the next run the held records are applied through §3.1 and `held` clears; the newer `evaluation` replaced the held one before that, losing nothing. Before the restore, no `books` row exists and K is not on the shelf. A concurrent held `evaluation` conflict stays hidden until the book exists. |
| C8 | Incoming keep-body resolution `R` for an annotation: Tx 1 commits, crash before Tx 2, the reader edits the annotation, restart. | Recovery builds `L` from the edit with `R.fp` in `anc`; replay is Stale; the edit stays dirty and is published; the conflict closes as `RESOLVED`; the cursor advances after Tx 2. Without the edit, replay is Agreement. |
| C9 | Catalog merge applied (new edition, `archiveHlc`) then crash before Tx 2. Variant (a) no edit, (b) the reader unarchives before restart. | (a) `drive_record_state.body_json` is restored from the receipt including `archiveHlc` and every `availabilityHlc`, with no republish unless `republish = 1`. (b) The unarchive becomes a new version with `R.fp` in `anc` and a new `archiveHlc`; no edition or identifier is lost. |
| C10 | A local edit lands between the apply decision and Tx 1. | The apply aborts, re-decides against the edited row, and the edit is never overwritten. |
| C11 | Crash before Tx 1, no receipt for `R.fp`. | Replay processes `R` from scratch with no receipt-based repair. |
| C12 | Apply a catalog record `R` fully (Tx 1 and Tx 2). Then make 65 or more local catalog versions, including new upload and clear availability groups, keeping the `books`-backed fields unchanged. Restart. Variant: a local `vocabWord` revival (or a local annotation resolution) commits Tx 1 and Tx 2, the reader then edits the word (annotation) and edits it back to the revival's (resolution's) body, and the device restarts before publishing. Variant (revision-based, resolution during upload): an `evaluation` (and separately a metadata `title` override) batch for revision 5 is uploading when the reader resolves a conflict locally, so Tx 1 commits revision 6 with `sent_json.vfp = resolveFp`. The ACK for the revision-5 batch then arrives, and the process crashes before the Tx 2 of the ACK or of the resolution. Repeat with the crash before the ACK. | `R.fp` is no longer in `anc(L)`, but `receipt_id == acked_receipt_id`, so no recovery runs and the newer availability groups are kept. A crash between Tx 1 and Tx 2 of a later apply still recovers, because its `receipt_id` differs. In the variant `receipt_id == acked_receipt_id`, so no recovery runs: `L` stays the newer version with `reviveFp` (`resolveFp`) in its `anc`, and the old `reviveFp` (`resolveFp`) is not restored over it. A crash before the Tx 2 of the revival or resolution still recovers it from the receipt (C16, C27). In the revision-based variant the ACK's `target_revision` (5) is not above `acked_revision` (6), so Tx 1 leaves `acked_revision`, `sent_revision` and `sent_json` at the resolution's values, and the ACK only marks the revision-5 `drive_outbox` row `ACKED`; `published_fp` does not move to the older fp. Recovery finds `row.revision == acked_revision`, pairs `sent_json.vfp = resolveFp` with the recomputed `contentFp`, and the stored `resolve` is still owed and published. The revision-5 version is never restored, and a post-crash edit still keeps `resolveFp` in its `anc`. |
| C13 | Device A runs with a default locale whose `%d` emits non-ASCII digits (for example `ar-EG`). A creates an HLC, a delta file name and a snapshot file name. | All three contain only ASCII digits, and equal byte-for-byte the output under `Locale.ROOT`. B orders A's HLC against its own by plain string comparison; a hand-built HLC with non-ASCII digits is rejected as invalid. |
| C14 | C8 with the reader **deleting** the annotation (not editing it) before restart. Variants: incoming `R` is a `put`; incoming `R` is a keep-body `resolve`. | Recovery sees projection `DELETED` against a live `row_fp`, mints a fresh HLC `h` and builds `L = delFp(d, k, h)` with `R.fp` (receipt `version_fp`) in `anc`. Replay of `R` is Stale, the annotation stays deleted, the `del` (`body: null`) is published, and the cursor advances after Tx 2. |
| C15 | C9 (b) variants: (a) the reader unarchives and a post-crash edition upload happens; (b) the reader only changes `title` after the crash. | (a) `archiveHlc` is minted once for the unarchive and every other edition, `availabilityHlc` and identifier is unchanged. (b) the receipt's `archiveHlc` is kept unchanged; no new `archiveHlc` is minted. |
| C16 | A deletes W, B's review `r` is uncovered, so W is revived on A with `reviveFp`. The reader then undoes `r`. The original `del` D (and the original `put` P) are replayed to A, and delivered late to B, in both orders. Variant (live parent): W is live on B at `P = restore.fp` when `D` arrives with `r` uncovered, so B keeps W. Then the reader undoes `r`, and `D` is replayed to B and delivered to C. | `fp(revival) ≠ restore.fp`; D is Stale on every device (`D.fp ∈ anc(revival)`), P is Stale, and W stays live with the same body everywhere. Two devices that revive concurrently from D produce the same `reviveFp` and agree without a conflict. A record with `revives` whose `fp` is not `reviveFp(revives.del, revives.restore)` is rejected as malformed. In the live-parent variant B publishes `fp = reviveFp(D.fp, P)`, not `P`. On B and C `D` is Stale, W stays live, and a device that revived W from absent reaches Agreement with B. Crash variant: crash after Tx 1 and before Tx 2 of A's revival, restart, the reader undoes `r`, then `D` is replayed. Recovery restores `L = reviveFp` from the receipt with `anc = [D.fp, restore.fp]`, the revival is still published, `D` is Stale and W stays live. The same holds when the revival is a peer's revival `put` applied by A. |
| C17 | B authors review `r` (published, `seq = 5`), adopts A's later update to an occurrence B had also authored, then undoes `r` (final `del` at `seq = 7`). B compacts and prunes its deltas. A fresh device D reads only B's snapshot, with a word `del` from A that covers `r` (`observed = {B: 5}`) and another that does not (`observed = {B: 4}`). | The snapshot holds `r`'s `put` (`seq = 5`) and `del` (`seq = 7`) and B's authored occurrence record with its original `seq`, although `drive_record_state` holds only the adopted or deleted version. D reaches the same state as a device that read the deltas. Crashing between seq allocation and the outbox write leaves neither an authored row nor an outbox row. Variant: the `put` of `r` is still unpublished when the reader undoes it, so both are owed before B's next sync. They freeze into two consecutive batches (`put` at `seq = 5`, `del` at `seq = 6`) in one freeze transaction, each with its own authored row, and neither insert fails or replaces the other. B's snapshot holds both envelopes, and a device reading only that snapshot ends with `r` `UNDONE`. Cross-stream permutations on a device holding W: `D` (`observed = {B: 4}`) → `r` put (`B:5`) → undo (`B:7`); `r` → `D` → undo; `r` → undo → `D`; and an undo (a `body: null` `del` that carries only the `syncId`) that arrives before B's `r` put, including by snapshot, where the undo `del` is read before the `put`. All four end with W live without `r`, and `r` stored `UNDONE`. In the undo-first orders, the receiver writes an ID-only tombstone (`drive_record_state`, `version_op = del`, `body_json = NULL`) and nothing in `liseur.db`, since `language` and `normalized` are unknown; a restart between the undo and the put keeps it. The put then consults the tombstone and is stored `UNDONE` with its own provenance, `vocabulary_review_sync.acked_deleted = true`, and `reconcileSchedule` is never run for it, so `dueAt`, `streak` and `repetitions` equal those of a device that applied the put first. On a device without W, the same orders revive W from `restore`. With `observed = {B: 5}` every order ends with W deleted and `r` `UNDONE`. |
| C18 | B has applied A's sessions `s1`, `s2` for K, then receives A's catalog `del` for K. A fresh device reads A's snapshot holding the `del` and both sessions. Both orders. | `s1` and `s2` exist under `drive:<K>` on both devices with no `books` row, and total and per-day durations are equal. Restoring K shows them on the book again. |
| C19 | A, B and C agree at 50%. B reads to 60%. A pulls B (`Pull`), then pulls C, which is still 50%. Then a fresh device D reconstructs from B's and C's streams, in both orders. | A stays at 60% and keeps a per-device baseline of 60% with B and 50% with C. The account row's `agreed_*` is never read. D opens one position conflict (60% vs 50%) in both orders, and choosing either value converges. |
| C20 | A delta batch from B holds two `annotation` records `R1`, `R2` (and two `vocabReview` puts). Crash after `R1`'s Tx 2 and before `R2`'s. Repeat with a snapshot whose `annotation` records span parts 0 and 1, crashing after part 0. | `drive_cursor(B, annotation)` is still below the batch `seq` (or below `throughSeq`). On restart `R1` reaches Agreement, `R2` applies, and only then does the cursor move. No record is skipped and the review count is unchanged on replay. |
| C21 | B holds `EDIT_EDIT` on a rating. A's keep-body `resolve` `R` keeps B's value, so the row value is unchanged. Tx 1 commits, then crash before Tx 2. The reader edits the rating, then restart. Repeat for a metadata `title` override. | Tx 1 raised `acked_revision`, so recovery runs. The edit stays dirty with `R.fp` in `anc` and carries `R.fp` in `resRefs`, replay of `R` is Stale, the conflict closes as `RESOLVED`, and the cursor advances after Tx 2. Without the edit, replay is Agreement. |
| C22 | A uploads edition X of K (`liseurKind=epub`), and B caches it in `drive_file` as `epub`. The reader then deletes the EPUB in the Drive UI, so B's change feed returns `removed = true` with no `file`. Repeat with `trashed = true` and full metadata. Offline variant: A and B are offline, the EPUB is deleted in the Drive UI (and a second one is trashed), B's `pageToken` then expires (HTTP 400/404), and B reconnects and rescans, with a crash after the listing and before the new token is committed, and a variant where the listing fails on its second page. | Both variants route as EPUB changes. The device that sees the change publishes a catalog `put` clearing X's `driveFileId`, `fileName` and `uploadedAt` with a new `availabilityHlc`. No catalog `del` is published, and history is kept. In the offline variant the rescan lists only surviving files, but reconciliation finds X's cached `drive_file` row and the catalog edition's `driveFileId` absent from the inventory and publishes the same clearing `put` for each deleted or trashed EPUB, so X is no longer shown as available on B or on a device that syncs it later. The token is committed only afterward, and the crash repeats the rescan and the reconciliation to the same result without a duplicate clearing `put`. When the listing fails on its second page nothing is cleared and the old token is kept. |
| C23 | Migrate a version-84 database that holds liseur-sync `vocabulary_staged_review` rows to 85. | The rows survive with `state = 'STAGED'` and `NULL` `origin_device_id` / `origin_seq`. liseur-sync staging and attach behave as before. `drive_apply_receipt` exists and is empty. `reading_sessions` has the new columns and the unique index. |
| C24 | Note N is at version X (`updatedAt = t0`). A edits N to Y, then edits it back to X's exact body while A's wall clock has returned to `t0` (the HLC still advances), then B, which holds Y, pulls. Repeat with B pulling after each step, and with the three records delivered as X, Y, X' and as Y, X', X (a replayed older record) to a fresh device. Run for an `annotation` note, a `vocabWord` note and an `evaluation` review. Variant: A and B independently make the same edit from X, each minting its own `putFp`. | X' has the same body and `contentFp` as X but a different `putFp`, with `anc` holding Y's and X's `fp`. B and the fresh device end on X' in every order, and a replayed Y or X is Stale (`R.fp ∈ anc(X')`), so the revert is never silently replaced by Y. No device finds X and Y mutual ancestors. In the variant the two puts meet in Agreement by content, both end on the higher `(hlc, fp)` with the other `fp` in `anc`, and no conflict opens. The golden suite pins `putFp` for one body at two HLCs (§1.3). |
| C25 | Two devices A and B delete word W concurrently, giving distinct deletes `D1` and `D2`, with `D1 > D2` on `(hlc, fp)`. `D1.observed = {C: 5}`, `D2.observed = {C: 4}`, and each has its own `restore`. Device X receives `D1` then `D2`, and a second device Y `D2` then `D1`. C's review `r` (`C:5`) arrives later on both. Repeat with `D1.observed = {C: 4}` and `D2.observed = {C: 5}`. Then run the interleavings in which `r` arrives between the deletes: `D2` → `r` → `D1` and `D1` → `r` → `D2` (the revival minted from the first delete is then met by the second), and the same on a device that adopts a peer's revival `put` before the second delete arrives. Copy-order variant (`D1.observed = {C: 5}`, `D2.observed = {C: 4}`): replica X holds only `D2` and `r`, so it revives W from `D2` and republishes `r` in batch `X:9` with `origin = {C, 5}`. A fresh device receives X's copy of `r`, C's own copy, X's revival and `D1` in every order, including X's copy first with no delete known. Snapshot-only variant: the fresh device reads only X's compacted snapshot (the revival with `revives.delRecord` for `D2`, and `r` with its `origin`), and `D1` arrives later or never; repeat with `D1.observed = {C: 4}`. Label variant: `D2` → `r` → `D1` with `D1.observed = {C: 4}`, which stores `r` `COVERED` before `D1` arrives, then the same with `r` `STAGED` (before any delete) and `UNDONE`. Same-author variant: A deletes W in batch `A:5` (`observed = {A: 5, C: 4}`), then A authors a review `r6` (`A:6`) and an occurrence edit (`A:7`) of W. Cover check on a device that reads the deltas, on one that reads only A's snapshot with `throughSeq = 20` (the `del` kept from `A:5`), and on one that adopted a revival carrying `delRecord`; a review `r4` from `A:4` is the control. | In both orders `L = D1.fp` with `D1`'s `restore_json` and `observed_json`, and the losing delete's payloads are discarded. In the first run `r` is covered on X and Y: W stays deleted and `r` is `COVERED`. In the second run `r` is uncovered on X and Y: W is revived from `D1`'s `restore` on both. The answer never depends on delivery order. In the interleavings the first delete's revival is not an independent edit: the winning delete `D1` is judged against `r` again. With `D1.observed = {C: 5}`, `D2` → `r` → `D1` ends with W deleted, `r` `COVERED` and `L = D1.fp`, exactly as `D1` → `D2` → `r`. With `D1.observed = {C: 4}`, every interleaving ends with W live and `L = reviveFp(D1.fp, D1.restore.fp)` with `D1`'s `restore.body`, and a lower `D2` arriving after a revival from `D1` is Stale. In the copy-order variant every order ends with W deleted, `L = D1.fp` and `r` `COVERED` with provenance `C:5`: X's copy read first is `STAGED` at `C:5`, never `X:9`, and C's copy is a repeat that changes nothing. In the snapshot-only variant the device holds `D2` from `delRecord` (`del_json`, `observed_json`, `restore_json`) without ever having received it. `D1` is ordered against it by `(hlc, fp)` and covers `r`, so W ends deleted with `r` `COVERED` and `L = D1.fp`, the same as on a device that read every delta. With `D1.observed = {C: 4}` W stays live as `reviveFp(D1.fp, D1.restore.fp)`. In the label variant `r` is uncovered by `D1` in all three states, so `D2`'s earlier verdict is replaced: W is revived from `D1`'s `restore` with `r` attached (`COVERED` or `STAGED`) or kept as `UNDONE` evidence and never attached, exactly as when `D1` is received first. In the same-author variant `r4` (`A:4`) is covered by `A`'s own entry, `r6` (`A:6`) and the `A:7` edit are uncovered, so W survives or is revived on every device, identically for deltas, the `throughSeq = 20` snapshot and the `delRecord`: the delete's batch boundary is `observed[A] = 5`, never `throughSeq`, a republisher's batch or the revival's batch. |
| C26 | C holds B's 80% peak for edition E at `seq = 10`. A resets the work with `floors = {B: 10}`. After pulling the reset, B reads to 30% and publishes a peak at `seq = 11`. C receives the `seq = 11` peak before A's reset, then repeat with the reset first. Variant: the `seq = 10` peak is replayed after the reset. | In both orders C ends with exactly one peak for B and E, 30% at `seq = 11`, and `maxFloor[B] = 10`. In the first order `observe` does not make the 30% peak visible, but it is kept as a newer candidate and re-materialized when the reset deletes `seq = 10`. The replayed `seq = 10` peak is dropped. A `seq = 11` peak that is 80% (higher than the `seq = 10` row) replaces it, leaving no candidate chain. |
| C27 | The reader resolves an annotation `EDIT_EDIT` locally. Tx 1 commits the choice and its `local_version` receipt, then the process crashes before the `op: "resolve"` batch is frozen. After restart, before recovery runs, the reader edits the annotation (variants: the reader deletes it). Repeat for `outcome: "delete"`, for a metadata `title` override, and with a crash before Tx 1. | Recovery finds the receipt with `version_fp = resolveFp` and a changed row (for the metadata override, the raised `acked_revision` with `sent_json.vfp = resolveFp`), and never re-applies the stored choice, so the edit (or deletion) is kept. It becomes a descendant with `resolveFp` in its `anc` and `res_refs`. The stored `resolve` record is frozen in its own batch ahead of the edit's batch, the conflict closes as `RESOLVED`, and a peer applying both in order ends on the edit. Without an edit the version is `resolveFp` and is published. With a crash before Tx 1 (no receipt), the choice is applied from `resolution_json`. |
| C28 | A rating (and separately a `review`, and a metadata `title` override) is applied remotely: Tx 1 commits and the process crashes before Tx 2. After restart, before recovery runs, the reader clears the rating or resets the override. Variant: the reader clears the field, then sets it again before recovery. | Recovery sees `row.revision > acked_revision` with `value == null` (or `title_override = 0`) and mints `delFp(d, k, h)` with `sent_json.vfp` first in `anc`; `body_json` is `NULL` and the tombstone is owed. Replay of `R` is Stale, a `del` is published, and the override is not restored from fallback metadata. A put with a null value is never built. In the variant the final value is a put with a fresh `putFp` and `sent_json.vfp` in `anc`. |
| C29 | A and B agree at 40% with `finishedOverride = "automatic"` and `ReadingStatus.READING`. A sets an explicit `reading` choice without moving, and B pulls. Then A clears it back to `automatic` without moving, and B pulls. Variants: the same for `paused`; B has set its own different choice before pulling; a fresh device with no baseline pulls A's record. | The position and status are equal, so `reconcileReadingState` returns `InSync`, but the choice is reconciled separately: B adopts `reading`, then `automatic`, raises `status_revision`, and sets `agreed_status_choice` and `acked_status_revision` only after applying it. B never acknowledges a status revision it did not apply. In the variant where B changed its own choice, the choices conflict and `persistPendingStatus` raises one prompt, with the baseline unchanged. The fresh device adopts A's choice (clean local side) without a prompt. |
| C30 | An annotation note of about 1.5 MiB is edited next to 50 other dirty records, in the same and in other domains, then synced. Variants: the reader shortens the note; the reader deletes the annotation; a local conflict resolution keeps the oversized body. Ancestry variant: a record whose `resRefs` alone exceed 1 MiB, then the reader shortens its body, and separately deletes it. | The 50 records publish in ordinary batches and no cursor stalls. The oversized record is neither truncated nor dropped and the local row is unchanged. No `seq` is allocated for it, `published_fp` keeps its old value, and the run reports it as withheld. Peers keep the last version they received. The shortened note publishes normally with the withheld `fp` in `anc`, and the deletion of the 1.5 MiB note publishes a normal `del`, because the overflow was body-only. A snapshot never holds the withheld version. In the ancestry variant the shortened body and the `del` inherit the same `resRefs`, so both are oversized too: each is withheld without a `seq`, stays owed and is reported, the local row (including the deletion) is unchanged, peers keep the last version they received, and the 50 other records still publish. The oversized resolution stays `RESOLVING`, and a later edit that fits publishes alone and moves the conflict to `RESOLVED` locally. |
| C31 | A resolves a `DELETE_EDIT` on annotation K by deleting, minting resolution `Q`. A then publishes a new series order `P` and resets the book locally, giving local delete `D` with `Q` in `anc(D)`, and `D` is not yet uploaded. Replay `Q` before `D` uploads, as a crash replay or a second pull would. Repeat with `Q` as a plain `del` and `D` its descendant, and with `D` uploaded first. | `Q ∈ anc(D)` so `Q` is Stale (§3.1 Step 5), not Agreement: `L` stays `D`, `published_fp` is unchanged and `D` is still owed. `D` uploads and peers still on `P` receive the reset. Two unrelated concurrent deletes still reach Agreement. |
| C32 | B holds `EDIT_EDIT` on annotation K against A's remote record `R` whose `resRefs = {r1}` and `anc = {a1}`, with `r1` settled earlier. B restarts, then resolves the conflict, and the record is later compacted. Variant: `DELETE_EDIT`. | `drive_conflict.remote_json` holds the whole envelope of `R`. The `resolve` carries `anc ⊇ {L, R.fp, a1}` and `resRefs = res_refs(L) ∪ {r1}`, so a peer that only knows `r1`'s branch does not reopen a conflict after compaction. |
| C33 | The reader deletes book K from the synced library, and a snapshot holds A's `position` record for K. B's restored book holds a local reading state at 60%, newer than its baseline with A (40%). The reader restores K. Variants: no baseline row (fresh device, one prompt allowed); the same percentages with a different explicit choice. | The held record is materialized through §4.5 against the per-device row, never as `Unseen`. A's 40% is compared with the local 60%: B keeps its state (`Push`) or raises the position prompt on a real conflict, and the explicit choice follows the C29 rule. No position is silently overwritten. |
| C34 | A and B share edition E, and the record's `locatorJson` is an exact anchor. A moves from 50.0% to 50.2% onto a different passage (different `href`, selector or text). Variants: same anchor with different percentages; no exact anchor on the remote record; a different `editionSha`. | `ExactLocatorAnchor.agreement` decides `exactPositionAgreement`: the moved anchor is not `InSync`, B applies the locator and acknowledges only after applying it. The same anchor at different percentages is `InSync`. Without an exact remote anchor, or on another edition, `null` selects the percentage tolerance. |
