# 24. Cross-device reading comparison

Status: accepted

## Context

ADR-0018 kept period comparisons on this device because the old server
summary did not identify its timezone and current and previous periods
would have required independent requests. Either limitation could make
the two sides count different reading.

The statistics snapshot introduced for ADR-0021 answers in the account
timezone and binds totals, local overlap and a statistics revision in one
response. It can therefore carry both comparison periods without mixing
generations or counting an uploaded sitting twice.

The last day still needs special treatment. A comparison at lunchtime
must stop both periods at lunchtime. Raw sessions can be prorated at that
wall-clock cutoff. A daily rollup cannot show how much happened before
the cutoff.

## Decision

When liseur-sync advertises comparison support, the Android client asks
for the current and previous spans in the existing snapshot request. The
request includes one millisecond-precision wall-clock cutoff in the
server's account timezone.

The server calculates both totals and their overlap from the same database
snapshot and revision. Raw sessions that cross the cutoff contribute the
same estimated share used by the client:

`active duration * elapsed wall time before cutoff / session wall-clock extent`

Both implementations round each contribution to milliseconds before
adding it. The account timezone resolves daylight-saving gaps and repeated
hours. Sessions remain assigned whole to their ending day everywhere else.

The comparison is optional within an otherwise complete statistics
snapshot. If compacted data cannot prove a boundary contribution, evidence
is too large, or the comparison block is malformed, the server omits it.
It does not invalidate the headline. The client then uses the existing
local comparison and retains the "on this device" wording.

When the proof is available, the client combines each side as:

`server + captured local - actual server overlap`

The client validates the echoed spans and cutoff and requires both sides
to pass. It never shows a comparison with mixed provenance. The
all-device wording omits a second scope suffix because the provenance line
under the same headline already says the figures count every device.

## Consequences

Week, month and year comparisons can represent reading across all devices
without a second network request. Older periods may fall back to this
device when session compaction removed the timestamps needed to split the
boundary day.

The capability is additive. Older servers receive the old request and the
client continues to show its local comparison. All-time statistics still
have no comparison.
