# 60. Server stats when liseur-sync is set up

Status: accepted

Amends [ADR 0021](0021-cross-device-reading-statistics.md).

## Context

ADR 0021 shows this device's figures whenever the merged liseur-sync
figures cannot be proved exact, labelled "this device only". In
practice the screen opened on this device's figures and then jumped to
the merged ones once the server answered. It also fell back to this
device for the length of every refresh, every range change and every
refused or unreachable request. A reader with several devices saw two
different sets of numbers within a second, and could not tell which one
to believe.

## Decision

When a liseur-sync account is set up and its token can read
statistics, the stats screens show the server's figures only. They
still add this device's sittings the server does not hold yet, through
the same union and overlap rules as before.

- While the first answer for a span is on its way, the screen shows
  the loading indicator, not this device's figures.
- A refresh for the same span keeps the current answer on screen until
  the new one replaces it. A failed refresh leaves it there.
- When no answer can be shown for the span, because the server cannot
  be reached or the merge is refused, the screen says so and offers to
  try again.
- The union is computed from the sittings the answer was proved
  against, so a sitting recorded or uploaded in between does not
  invalidate it. The next refresh includes it.
- A reply that the reading moved under while the server was answering
  is asked for again a few times before the screen gives up.
- The comparison with the previous period is the server's or none. One
  worked out from this device alone is not put beside every device's
  figures.

Without a liseur-sync account, with another server kind, or with a
token that lacks the read-insights scope, the screens show this
device's figures, as before. A 403 clears that scope, so a token that
loses it falls back to this device on its own, including when an
earlier answer was on screen.

The home screen widgets are unchanged.

## Consequences

A refusal such as `candidate_payload_mismatch` used to be invisible:
the reader saw smaller numbers. It is now an error screen until the
cause is fixed. The reason is still logged under `liseur-sync-insights`.
