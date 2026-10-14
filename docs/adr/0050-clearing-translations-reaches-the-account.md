# 50. Clearing translations reaches the account

Status: accepted

## Context

Since liseur-sync's ADR-0052 the sentence translations of a book live on
the account as well as on each phone. Nothing the reader did on a phone
reached the account's copy: removing a book, finishing it, and Clear in
Settings, Translation only touched `translations.db`, and the next pull
filled the phone again. The account's copy only shrank when the server
evicted past its cap or the book was deleted from the server with its
reading state. liseur-sync's ADR-0053 adds the two routes that clear it,
`DELETE /v1/works/{id}/translations` and `DELETE /v1/translations`.

## Decision

The reader can clear the account's copy in four places:

- The remove download, delete file, and remove from library dialogs
  offer "Also clear its translations from my account", off by default
  and shown only when the book has a work on the connected liseur-sync
  account. This phone's sentences follow the book as before: they go
  with the file, and stay while a hidden book is still in `books`. The
  ones that stay are marked as already sent to the account, in the
  same transaction as the clear, so the sync that sends the clear
  doesn't push them straight back. The clear is recorded before the
  book goes and the sync is started after, without the removal
  waiting for it.
- Finishing a book, from the library or by reading past its end,
  clears its translations on this phone and on the account. A switch in
  the liseur-sync account's Sync section turns it off; it is on by
  default and backed up with the other settings. It is one device
  setting rather than one per account, since only one book server is
  connected at a time. The clear is recorded before marking the book
  finished returns. A status adopted from another device, or derived
  from progress, does not clear anything.
- Clear in Settings, Translation offers "Also clear from my account",
  off by default and shown only with a liseur-sync account connected.
- The reader's overflow menu has "Clear translations", which asks first
  and then clears the book here and on the account. Like Clear in
  Settings, the page already on screen keeps its translation until the
  book is reopened; the reader's cache keeps saving what it translates
  next.

A clear the account is owed is a row in `translation_account_clears`
(`translations.db` v4): the peer and the work id, with an empty work id
for the whole account. It is state, not a log. There is one row per
owed clear, the DELETE is idempotent, and the row goes once the server
answered. A generation counts how often the same clear was asked for,
and an answer only settles the generation it was sent for, so a clear
asked again while the first was on the wire is sent again. The work id
is read from the book's alias when the clear is asked for, since
removing the book can take the alias with it. The rows are written in
the same transaction as the local clear, under the same fence as Clear
in Settings: a save or pull asked for before the clear lands nothing.
A book's clear does not move its epoch, which would stop the reader's
open cache from saving anything until the book was reopened.

The translation pass sends owed clears before it reads anything from
the account, the account-wide one first:

- A success, or a `404` whose error is `work not found`, settles the
  row. A `404` with any other error keeps it.
- A server without the routes answers Go's plain-text
  `404 page not found` or a `405`. The
  clear is dropped and logged, and the server keeps syncing
  translations; it is not marked as lacking them.
- Any other failure keeps the row. The pass skips every work the row
  covers, pulling nothing and pushing nothing for it, so the old rows
  cannot come back before the clear is sent.

The rows are keyed by peer, so `RemoteAccountRepository`'s rekey and
disconnect carry or drop them with the rest of the peer's bookkeeping
(`SavedTranslations.rekeyPeer`, `forgetPeer`, `forgetPeersBeyond`).

## Consequences

- The reader can free the account's storage without the server's admin
  tools, and a finished book no longer refills a phone that cleared it.
- Other devices keep what they already pulled. The server only stops
  serving it, and another device with unpushed saves can push them
  back. That is acceptable for a cache: every row is still a correct
  translation. The device that asked for the clear never pushes back
  what it held before.
- A clear asked for offline waits for the next sync, and holds that
  book's translation exchange until it goes through.
- The pending request lives in `translations.db`, which Android backup
  excludes to keep cached sentence text out of backups. Restoring app
  data before the next sync can lose the request; a later sync can
  refill the translations from the account.
- Against a server from before ADR-0053, clears are dropped rather than
  kept forever.
