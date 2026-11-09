# 67. The companion recalls where you stopped, describes terms and saves answers as notes

Status: proposed

Builds on [51. Character profiles use only earlier chapters](0051-character-profiles-use-only-earlier-chapters.md),
[57. Codex tells the story so far](0057-codex-tells-the-story-so-far.md)
and [58. Codex hosts the character profile](0058-codex-hosts-the-character-profile.md).

## Context

The companion answered one question: who is this character. Readers also
select places, groups, objects and invented words, and got a character
profile of a ring. Coming back to a book after a break, Story listed the
earlier chapters but said nothing of the one being read, and an answer
could only be read, not kept. KOAssistant, a KOReader plugin, covers
these with a term X-Ray, a re-immersion summary and saving answers on
the highlight; its prompt structure informed ours, its wording and its
"use what you know" approach did not.

## Decision

- A lookup may answer `term` instead of `profile`: what it is (with a
  nature: place, group, object, concept, other), its role, how it
  changed, its connections, and what the text leaves open. Same gate,
  refs, corrections and cache as a profile; the model picks the shape.
- Story has a "Where was I?" card. Only a tap sends anything. It sends,
  for the scope Codex froze, the last eligible passages before the read
  point (up to 12,000 characters, which can reach into the previous
  chapter) and the summary sentences of up to six saved recaps of earlier
  chapters as `C` refs. The answer gives where the action stopped, the
  current objective, the open conflict, what is in focus, the mood and
  open questions; every field cites `S` or `C` refs it was shown. It is
  kept in memory only, keyed by everything the request sends.
- The read point is the one Codex already freezes: the first visible
  element of the page, which never passes the gate itself.
- Profiles, terms and "Where was I?" can be copied as plain text or saved
  as a note. From a selection the note goes on that passage, added after
  any note already on its mark; otherwise it is a note on the book. Saved
  notes sync like any other note. Nothing is saved without a tap.

## Consequences

Story is no longer free of book text: its "Where was I?" button says what
it sends and to which service before it is tapped. Recap sentences are
model text and uncited, so a field citing one shows the chapter it came
from, never a quote.
