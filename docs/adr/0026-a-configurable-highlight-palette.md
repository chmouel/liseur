# 26. A palette the reader sets, three colours wide

Status: accepted

## Context

Selecting a passage put eleven things over it: six colour chips, Note,
Define, Search, Share, and Delete when the passage was already marked.
On a phone that bar is wider than the sentence it belongs to, and it is
drawn precisely over the words the reader is looking at — the one thing
`SelectionPopup` was written to avoid, and it was defeating itself with
its own palette.

Six colours exist for a reason that is worth restating, because it is
not this one. `HighlightTint` matches liseur-sync's palette entry for
entry so that a highlight another device made always has a name here; a
colour this app could not name would arrive rewritten, and a sync that
quietly changes what it carries is worse than a colour too many. That
argument is about **storage**. It says nothing whatever about how many
chips belong in a bar, and it was being used as though it did.

Most readers use one colour, some use two or three — a quotation, a
doubt, a word to come back to. The other three are paid for on every
selection by everybody.

## Decision

Which colours the bar offers is the reader's to set: any subset of the
six, and **yellow, green and blue by default**. Which colour a plain
Highlight gets when none was picked is theirs to set too. A Note attached
to selected passage text follows the first offered colour instead.

`HighlightPalette` holds both, and holds the whole of the rule:

```kotlin
val shown = HighlightTint.entries.filter { it in offered }
```

A set rather than a count, because a count leaves the reader working out
which three "3" meant, and because the first three are not obviously the
three anyone wants. Declaration order rather than the order of ticking,
or the default first, which has one deliberate consequence: **ticking a
colour slots it in beside its neighbours and leaves the others where
they were**, so a reader who marks by position rather than by name keeps
their muscle memory when they change their mind.

The default need not be offered. It is what the plain Highlight action
uses when nothing is ticked, and the fallback for a passage Note when no
colours are offered, so it has to mean something even when it is nowhere
on the bar. Making it offer itself would be the setting quietly
overruling a tick the reader made.

A Note attached to selected passage text gets the first offered colour in
the palette's stable order. Removing yellow from the offered set therefore
also removes yellow as the automatic Note colour, even if yellow remains
the configured default. If the reader offers no colours at all, the Note
falls back to that configured default so the action remains available.

### Nothing about a mark changes

`toDecorations()`, the annotations list, the database and every sync
path are untouched. All six names remain legal in both directions on
the wire, a highlight in a colour the bar no longer offers is still
drawn over the page in that colour and still listed with its own dot,
and nothing anywhere rewrites a stored tint to the default because it
fell out of `shown`.

Hiding a chip hides a **choice**, never a mark. That distinction is the
whole safety argument for the feature, and it is why the palette is a
presentation concern that happens to also supply a default, rather than
a filter over the enum.

### A mark in a colour that is not offered

`chipsFor(active)` appends the selected mark's own colour when the
palette has left it out — made here before a colour was unticked, or
made on a device showing all six. Without it, recolouring such a
highlight would offer a bar in which its own colour did not appear: the
reader could not see which one it was, and any tap would lose it with no
way back.

### None

An empty set is a legal answer, and it does not mean "you may no longer
highlight". The chips are replaced by a plain **Highlight** action that
marks in the default colour — one word where six circles were, which is
what a reader who never changes colour actually wants. A passage **Note**
also uses the default in this case because there is no offered colour to
choose. A bar with no way to mark a passage would be a regression wearing
a setting's clothes.

Choosing none is stored, and it is not the same as never having chosen.
`HighlightPalette.of` gives the three defaults for an absent set and an
empty bar for a set that was written empty; reading them the same way
would mean a reader who wanted no chips got three back on next launch.

### Where it is set

The reader's Advanced sheet, and Settings → Reading appearance →
Advanced, through one shared control so the two cannot drift. Advanced
on both surfaces, as ADR 0001 requires: this is a thing a reader sets
once, if ever, and the typography sheet is not to grow for it.

All six are drawn whatever is ticked, at the size the bar will draw
them, so the control is the bar rather than a description of it and a
colour that is missing is missing where the gap can be seen and filled.
Ticked swatches are filled and carry a check; unticked ones are hollow
rings, not dimmed, because a dimmed yellow is still a yellow and a
reader would have to compare it against its neighbours to know.

The default rides on the same swatch, under a long press, rather than a
second row repeating the first six: it is much the rarer choice, and two
rows of the same colours invite the reader to think the rows disagree
about something. A long press is not discoverable, so the line under the
row says it and names the current default, and the swatch carries the
press as a TalkBack custom action for readers who cannot see that line.

## Consequences

Every existing reader loses three chips the first time they select a
passage after upgrading. That is the change, and it is why the release
note has to name the way back rather than only the new default: a
setting nobody is told about is a feature that reads as a bug.

The alternative — new installs start at three, existing ones keep six —
was rejected. A setting that means different things depending on when
the app was installed cannot be explained in one sentence, and this one
has to be.

`AppSettings` now references `reader.annotations`, as
`ReadingPaceRepository` already references `reader.progress`. The
palette is app-wide and deliberately not part of `ReaderPrefs`: it is
not typography, and "just this book" must not apply to it. A colour
scheme that changed as you moved between books would be a way to make
marks you could not find again.
