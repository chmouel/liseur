# 14. Position sync over KOReader's kosync protocol

Status: accepted

## Context

[Issue #95](https://github.com/chmouel/liseur/issues/95). Grimmory is
browsed through its Komga shim (ADR-0012), and the shim carries no
reading position: `canSync` is false, and the settings screen says
positions stay on this device. But Grimmory does hold positions, behind
KOReader's kosync protocol at `{base}/api/koreader`, under a third
credential set created in its device settings.

kosync is not Grimmory's. It is KOReader's own sync protocol, spoken by
kosync.koreader.rocks, by every self-hosted kosync server, by
calibre-web plugins, and by liseur-sync at `/adapter/kosync`.
Implementing Grimmory's flavour of it would have been implementing all
of it while pretending otherwise.

The protocol is small: `GET /users/auth` proves a credential,
`GET /syncs/progress/{document}` asks where a book stands,
`PUT /syncs/progress` says where it stands here. A book is named by
KOReader's partial MD5 of its file bytes, which
`BookFingerprint.partialMd5` already reproduces, because liseur-sync
resolution needed it first. Auth is two headers: `x-auth-user` and
`x-auth-key`, the hex MD5 of the password.

## Decision

**The pairing is offered where a server has no positions of its own,
which today means Grimmory.** calibre-web, Komga and liseur-sync all
carry a reading position themselves, and pairing kosync next to one of
them would leave a single book with two sources of truth, silently
disagreeing on a device the reader is not looking at. That is a conflict
they can neither see nor resolve, so it is not offered.

`ServerKind.hostsKosyncPeer` is the one place that answers, and it is
asked in three: the settings section, so it is not shown; the sync
itself, so it stays quiet; and the foreground policy, so nothing wakes
it. The last two are what make the rule true rather than merely
presented. A saved pairing is not permission to sync. An account switch
interrupted halfway, a crash, or a database restored onto a phone that
never made the pairing all leave a `kosync_peer` row behind a server
that knows nothing about it, so the peer asks what is connected on
every run instead of trusting the row's existence.

A pairing the newly connected server cannot host is dropped, in the
transaction that stores the new server rather than when the reader
starts connecting: an attempt that fails leaves the old server standing,
and putting the pairing down on the attempt would take a working
Grimmory setup with it. It goes through
`KosyncAccountRepository.disconnect`, the same door a reader's own
disconnect uses.

**A kosync server is a second sync partner, not a fifth kind of
server.** It is paired *alongside* whatever catalog server is connected,
from a section on the same settings screen, and lives in its own
single-row table (`kosync_peer`) with its own lifecycle: disconnecting
the catalog server leaves it standing, and vice versa. The architecture
was already shaped for this: `CompositePositionSync` runs a list of
`PeerPositionSync`s in turn, and `sync_peer_state` keys agreements by
`(book_url, peer_id)`, so the kosync partner's baselines and the catalog
server's cannot overwrite each other. Merging goes through
`domain/ReadingStateMerge.kt` unchanged: a fourth partner is not a
fourth set of rules.

**Coverage is the connected catalog server's downloaded books**
(`remote_uuid` set, a remote-scheme `books.url`, bytes on device).
kosync needs the file's hash, so only a downloaded book can be spoken
about at all; restricting to server books keeps the run bounded and
matches the reason the partner exists: Grimmory's own books, positions
held next door. The URL check matters: a locally added book *adopted*
after an upload to liseur-sync also carries a `remote_uuid` but keeps
its local `url` by design, and its positions already travel natively, so
kosync leaves it alone. kosync has no list endpoint, so a run is one GET
per candidate. It checks every candidate, deliberately, because a
position that exists only on the server can be found no other way, and
stops early when the server stops answering.

**Percentage-only fidelity.** The protocol's `progress` field is an
engine-specific position (a CRe xpointer, a page number) that means
nothing to Readium. On the way out it carries the percentage as a bare
string, the shape KOReader itself accepts for engines without an
xpointer, and the one liseur-sync answers with (verified against its
kosync adapter and tests). A pull therefore lands at roughly the right
page, exactly as calibre-web's Kobo sync does, and
`SyncPreview.exactPositionAgreement` stays null. Timestamps on the wire
are unix seconds, from stored state rather than the clock.

**Only the derived key is stored.** The password is hashed to the MD5
auth key the moment it is typed; the key is sealed with
`CredentialCipher` and the password never touches disk. What a database
leak exposes is a credential for this one protocol, not a password the
reader may have reused. The key is still a replayable credential, and a
cleartext connection exposes it to the network path: the scheme
defaults to https, and an unreachable https root is never retried over
http; the downgrade the catalog kinds offer behind a confirmation is
not offered here at all.

**Redirects are not followed.** OkHttp strips `Authorization` when a
redirect changes host, which protects the catalog kinds, but kosync
signs with custom `x-auth-*` headers that would be forwarded wholesale,
and a register body even carries the raw password. No kosync endpoint
legitimately redirects (the mount root is typed by the reader), so a
3xx is read as the wrong address and refused.

**Registering is an explicit ask, never a fallback.** liseur-sync
redeems a pairing code through `POST /users/create` with the password as
typed; Grimmory forbids the route outright. Retrying a failed sign-in as
a registration would turn a typo into a fresh junk account on a stock
kosync server, so the toggle is off unless the reader flips it. And
because register is the one call that carries the password as typed
rather than the derived key, it is refused outright over a plain-http
root.

**The account key is `kosync|{url}|{username}`.** Signing in as a
different kosync user strands the old agreements rather than adopting
them. That is the rule every other kind follows, and all per-account state is
cleared through one repository door, the `forgetSyncPeer()` precedent.

**A document name is validated before it is addressed.**
`^[0-9a-f]{32}$`, exactly KOReader's partial-MD5 shape, the only
document this client ever computes, or the request is refused as
malformed. The `GrimmoryId` rule: opaque to carry, checked before put
in a URL path.

A `ServerKind.CUSTOM` (generic OPDS catalog + optional kosync) stands on
top of this and is deliberately a separate change: position sync for it
flows through this same partner, so the rules exist once.

## Consequences

- Grimmory gets position sync: browse through the shim, sync through
  kosync, with `{base}/api/koreader` prefilled and the "positions are
  not synced here" notice replaced by directions to the section below.
- Any stock kosync server works the same way, which is tested end to end
  against liseur-sync's `/adapter/kosync`.
- A book downloaded from elsewhere has different bytes, a different
  hash, and therefore a different kosync document, so two copies of one
  work do not exchange positions. That is the protocol's nature, not a
  bug to fix here.
- `shouldSyncOnForeground` now asks about the kosync partner on its own
  terms, because the account it is most useful next to (Grimmory) is
  exactly the one whose `canSync` is false.
