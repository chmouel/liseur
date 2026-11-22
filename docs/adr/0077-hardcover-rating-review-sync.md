# 77. Hardcover two-way rating and review sync

Status: accepted

## Context

[ADR 0075](0075-hardcover-reading-tracker.md) made Hardcover reporting
push-only for reading status, page progress, dates and ratings, and left
reviews unsent. Readers who rate or review books in Liseur or on
Hardcover want both fields kept in step without silent overwrites when
the two sides disagree.

## Decision

In the Google Play build, ratings and reviews sync both ways between
Liseur and Hardcover whenever a Hardcover account is connected and the
book is matched and not excluded (`HardcoverEvaluationPlan`). Automatic
runs follow the existing `autoSync` switch; manual pushes always run.

Liseur's review syncs with Hardcover's public review (`review_markdown`,
with `reviewed_at` set to today's `YYYY-MM-DD` date when non-null and
both set to `null` when cleared; read back from `review_markdown`
falling back to `review_raw`). `review_has_spoilers`, `private_notes`
and Hardcover privacy settings are never modified. The Hardcover
settings section and `docs/PRIVACY.md` state that synced reviews are
public on Hardcover.

Per matched book and per field (`rating`, `review`), `hardcover.db`
(version 3, `hardcover_evaluation`) stores the base `(baseLocal,
baseRemote)` of the last values both sides agreed on:

- If the book is not yet on the Hardcover shelf (`user_book_id == null`),
  nothing is pulled; a local rating or review is sent only as part of
  adding the book with a target status.
- If local and remote are equivalent (rating: `local / 2.0 == remote`;
  review: trimmed with `\r\n` normalised to `\n`), the base is updated
  and nothing is written.
- With no stored base (for existing installs without a rating base, a
  non-null `pushed_rating` migrates as the rating base), a single
  non-null side is copied to the other; if both are non-null and differ,
  a conflict is recorded.
- With a stored base, if neither side changed from the base (including
  lossy pulls such as a `0.5` Hardcover rating mapped to Liseur's `"2"`
  1-star minimum), nothing happens; if only one side changed, that side
  is written to the other; if both changed, a conflict is recorded and
  the old base is kept.
- A remote review exceeding `PersonalEvaluation.MAX_REVIEW_BYTES` or
  failing `PersonalEvaluation.valid` cannot be pulled. While Liseur's
  review is unchanged it stays on Hardcover untouched and the base moves
  to it; only when both sides changed is it a conflict, where only
  keeping Liseur's value is offered.

When a field is in conflict, background sync stops touching that field
until the reader resolves it in a card in Book details ("Keep Liseur's"
or "Use Hardcover's") or a later check finds both sides equivalent.

Rating and review writes to Hardcover fold into `insert_user_book` /
`update_user_book`, followed by a `shelfState` re-read so Hardcover's
stored representation becomes the new base. Pulled values write into
`book_evaluation` via a compare-and-set transaction checking the
expected local value, then trigger `requestBookSync` so `liseur-sync`
propagates the edit without re-queuing `readingTracker.bookChanged`.

Hardcover is checked on the existing push triggers and when Book details
opens for a matched, non-excluded book with `autoSync` enabled,
throttled to at most once per 10 minutes per book via `pushed_at`.

## Consequences

- `hardcover.db` advances to version 3 via an automatic migration;
  `pushed_rating` stays for migration compatibility.
- Evaluation rows in `hardcover.db` are cleared whenever links are
  forgotten, reset for another account, or moved to a different
  Hardcover book.
- `liseur.db` and `liseur-sync` schemas and wire formats are unchanged.
