# 73. Private ratings and reviews

Status: accepted

## Decision

Keep one personal rating and one plain-text review per work on liseur-sync,
independent of catalog metadata and reading status. Ratings range from
1 to 5 stars in half-star steps (integer wire values 2 through 10).
Reviews are nonblank UTF-8 text, limited to 8192 bytes, without NUL.
Either field can be removed independently. There is no finish prompt.

Locally, fields live beside books without a cascading foreign key and keep
the title and author for export after file removal. Removing or replacing a
file moves its evaluations and delivery agreements to a retained identity,
independent of the reusable file URL. Capture its identifiers before
clearing the fingerprint so pending edits can resolve even after the file
has gone. Work aliases establish cross-device identity; uncertain matches
are not used. Apply responses only while the original work binding still
holds. Editor drafts are scoped to the book's row and content generation;
saving verifies that identity in the same transaction as the evaluation
write.

The server orders writes from different installations by receipt, separately
per field. A stable installation writer ID and persisted local revision
prevent an older first delivery from replacing a newer accepted edit from
that writer, including after a work merge. The writer ID lives under Android's
no-backup directory, outside cloud backup and device transfer. New write IDs
derive from that writer, work, local book, field and revision; restored pending
requests keep their original bytes. Local revisions increase across all
evaluations, including retained entries, so
returning to a path cannot reuse an accepted write ID. Persist its bytes
before sending. The server retains request fingerprints and writer revisions
so a delayed retry never overwrites a later choice. A removal leaves a
permanent null field; receipt records do not retain review text. For each
work and field, settle requests, send local edits in revision order across
active and retained copies, then pull the result into every copy. Full syncs
poll at most 20 works per pass, persist each sweep's progress, and schedule
follow-up passes for the rest. A sync for
one book includes the other known copies of that work. An acknowledged
later revision retires older unsent edits on copies that become usable
afterwards; those copies adopt the current server fields. Identical retries
remain valid after reconnecting with another device token for the same
account. A local edit made during a request remains owed. Remote values
never become locally authored edits. Re-resolving a merged work preserves
acknowledged revisions and pending request bytes; the server moves their
receipts to the surviving work. A merge excludes heads superseded by a higher
revision from the same writer in either work's receipts, then chooses by
server receipt time. If every head for a field is superseded, the merge
returns 409 without changing either work; a fresh choice is needed because
receipts do not retain the discarded values.

Peer state follows account rekeying. Leaving an account retires outstanding
local revisions and drops agreements, retaining the values on this device
without automatically uploading them to another account.

## Export

Settings → Backup → Export ratings and reviews writes UTF-8 JSON with
`format: "liseur-evaluations"`, `version: 1`, and a `books` array. Each book
has `book_url`, `title`, `author` and whichever `rating` and `review` fields
have been recorded. A field contains `value` and `updated_at` (Unix epoch
milliseconds). Rating values use the same half-star units as sync; null
means removed. Removed books and deletion markers are included. Account
identifiers, credentials and sync receipts are excluded. The export is
separate from the settings backup ZIP.
