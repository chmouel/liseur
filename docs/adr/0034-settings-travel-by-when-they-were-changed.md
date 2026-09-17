# 34. Settings travel by when they were changed

Status: accepted

## Context

Setting up a second device meant setting up everything again: the
typeface, the theme, the margins, the highlight colours. All of it is
already stored per device, and an account that syncs positions and
annotations was sitting right there, so the question was not whether to
sync settings but what a "newer" setting means.

The obvious answer — whichever arrives last — is wrong, and wrong in a
way that only shows up once somebody has been offline. A reader who
changes their font on a plane on Monday, then picks up the other phone
on Tuesday and changes nothing, should still find Monday's font when the
plane lands. Ordering by arrival gives them Tuesday's, because Tuesday's
device reached the network first. This is the same mistake ADR-0032
records for reading positions, where an afternoon's import claimed to be
an afternoon's reading.

Nothing in the app knew when a setting had been changed. The DataStores
store the value and nothing else, and by the time a sync pass reads one,
the only honest thing it can say is "this is different from what the
server agreed to" — never when it became different.

## Decision

**Each setting carries the time the reader changed it, and the later
change wins.** The time is the device's, not the server's. A server
clock would be easier to trust but would turn last-writer-wins into
last-arriver-wins and throw away the one case the feature exists for.

**A collector notes the change as it happens.** `SettingsChangeTracker`
watches the two settings flows from the application scope and stamps a
key when its value moves. It runs whether or not a server is connected,
because a change made offline is exactly the one whose time cannot be
recovered later. The first sight of a key seeds the baseline without
stamping it: an install, or a key a new version added, was not *changed*
by anybody, and claiming otherwise would push it over another device's
real edit.

That stamp is **advisory**. What actually gets pushed is still decided
by comparing the current value against the baseline the account agreed
to. This is most of what lets the collector run without locking against
the sync pass: when the pass writes a pulled value, the collector sees
it and stamps it like any other write, and nothing comes of it, because
the value now matches the baseline and so is not offered.

Most, but not all — the argument has a hole on an account nothing has
been agreed with yet, where there is no baseline for the stamp to be
harmless against. So the pass says outright which values came off a
server, and those are not this device's edits. The two run
independently and land in either order, so saying so covers both: the
value is written down, so a collection arriving after it skips the key,
and the stamp is removed, so a collection that got there first is
undone. An edit *away* from a server's value is an edit like any other
and is stamped.

A key with no stamp at all is the other half of the same question. It
means one of two things, and they want opposite answers. On an account
this device has agreed something with, an unstamped difference is an
edit the collector has not caught up with — the window between a setter
committing and the flow emitting — and treating it as the beginning of
time would hand the server every race it should lose. With no baseline
at all, nothing was chosen here: it is what the device happens to hold,
and offering it would push a fresh install's defaults over the
account's real settings on first connect. So a missing stamp reads as
*now* where a baseline exists and as the beginning of time where none
does.

**A value back where it started is still a choice.** A reader who
changes a setting and changes it back leaves it matching what the
account agreed, which usually means nothing happened — but not always,
and the stamp tells the two apart. A value the account agreed on, with
a later change recorded against it, overrules a server that moved in
between and is offered back under the time the reader settled on it.
The collector is what makes this possible and also what limits it: a
change and its undoing faster than the collector runs looks like
nothing at all, which is the advisory stamp's floor rather than a case
to be worked around.

**A conflict is settled against the copy that was sent.** If the reader
changes a setting while its push is in the air, the answer to that push
is about the old value and must not be written over the new one. This
is the rule annotation sync already follows, for the same reason.

**A push that loses is not an error.** The server's upsert keeps
whichever side is newer and answers `200` either way, carrying the
merged state. That merged state is what both sides then agree on — both
the value and the timestamp. Recording the value that was *sent* against
the timestamp that came *back* would invent a pair that exists on
neither side, and since neither would then look newer than the other,
the key would sit diverged and silent for good.

**A value this build cannot use is left alone.** Every `fromId` in the
app falls back to a default for an id it does not recognise, so an older
build accepting a newer build's font would store the fallback, read back
something different, and push its own default over the choice that was
made — then do it again, in both directions, forever. Writing a setting
therefore reports whether it was understood, and a refusal records
nothing, leaving the newer device's answer standing.

**Absence is a value.** Six typography settings treat "not set" as a
real answer meaning "use whatever the publisher asked for", which is not
the same as never having chosen. The server has no delete and no null,
so absence travels as a sentinel. Without one, clearing a setting could
never be pushed, and the stale baseline would put the old number back on
the next pass.

**What cannot be stored is not sent.** A NUL byte or an oversized value
is refused by the server, and its `PUT` is one transaction, so a single
unsendable setting would take every other setting down with it on every
pass, forever. The client drops such a value before the request rather
than discovering it in a `400`.

A time is unsendable in the same way. The server refuses a batch
carrying an `updated_at` more than a day ahead of its own clock, and a
change stamp is only as good as the clock that was running when it was
recorded — a phone whose date was wrong once leaves one behind, and it
outlives the correction. So an outgoing stamp is capped at now. This
loses nothing worth keeping: a change that has not happened yet is a
wrong answer whatever it is compared against, and now is the nearest
right one.

**The baseline belongs to one account.** Its timestamps came off that
server's clock and mean nothing anywhere else, so it is keyed by account
and moves or is dropped with it, like every other peer-keyed table. The
record of what *this device* changed is not keyed by account and
survives a switch: signing into another server does not un-change a
font.

**Nothing is applied while a book is open.** A pulled font size or
margin reflows the page under whoever is reading it, which is the
settings version of turning somebody's page. Reader settings are held
back and nothing is recorded for them, so the next pass applies them.
This is the rule an incoming position already follows. Which settings
those are is a property each one carries, not its prefix: `reader.` is
right for typography and wrong for `app.scroll_mode`, which is not
typography and rebuilds the page anyway. Whether a book is open is
asked again for each write rather than sampled once for the run, since
a device set to resume its last book opens one while the request is
still out.

**A run only counts for the account it started on.** Disconnecting or
switching accounts part-way through must leave nothing behind: the
account is checked before the request, after it, and again before
anything is written down. The request is itself the side effect, which
is the rule annotation sync already states.

**Only some settings travel.** Anything about a particular piece of
hardware — the volume keys, keeping the screen on, an e-ink panel's
refresh — stays where it is. A tablet has no business telling a phone
how its volume keys work.

## Consequences

Two devices converge on the reader's most recent choice rather than on
whichever one synced last, including across an offline stretch.

**Last-writer-wins on wall clocks has a floor, and this does not remove
it.** Two devices whose clocks disagree can still resolve in the wrong
order, and no amount of care here fixes that; only a server-assigned
ordering would, at the cost of the offline case above, which is the more
common one. What is bounded is the damage: the server refuses a change
time more than a day ahead, the way it already does for positions, so a
clock set wrongly into the future cannot pin a setting permanently with
no way to correct it.

A setting is a string on the wire, so the two sides need not agree on
what it means. An older build refuses what it does not recognise instead
of corrupting it, which is what makes a mixed-version pair of devices
safe.

The reader is never asked about any of this. There is no conflict
prompt, because a setting is not a manuscript: the cost of quietly
picking the later of two fonts is that somebody changes it back, and the
cost of asking is a dialog on every device every time.
