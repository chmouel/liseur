# 58. Codex hosts the character profile

Status: proposed

Amends [51. Character profiles use only earlier chapters](0051-character-profiles-use-only-earlier-chapters.md),
which gave the profile its own panel, and
[57. Codex tells the story so far](0057-codex-tells-the-story-so-far.md),
which hid Story when the on-device codex was off.

## Context

The reading companion and Codex grew side by side. Ask companion with a
service chosen opened a profile in its own sheet, with a button that
closed it and opened Codex on the same name. Without a service it opened
Codex directly. A name tapped inside Codex only ever listed local
mentions, and turning the on-device codex off hid Story along with the
scan, even with a service chosen. The two also read the book twice: a
lookup walked the publication in memory for passages Codex had already
stored in `companion.db`.

## Decision

Codex is the one sheet, and users see it as Companion: its title, the
On-device companion switch, the docs and store listings say Companion.
Code, string IDs and the `codex_*` tables keep the name Codex, and older
ADRs keep their wording. A character page in it has two panes, Profile
(what the companion says) and Mentions (where the book names them).
Without a service Codex is unchanged: the page is Mentions alone and no
tab row shows.

- Ask companion on a selection opens the page on Profile and asks at
  once when a service is chosen, and on Mentions otherwise, as before.
  With no service and no scan it opens on Profile, which offers setup.
- A name tapped in Names, a pair in Relationships, or a character chip
  on a Story row opens on Profile when a service is chosen, on Mentions
  otherwise. Profile shows an Ask button and nothing is sent until it is
  tapped. The lookup uses the scope Codex froze when it opened.
- Before Ask, Profile shows what saved recaps of earlier chapters already
  say about the name (facts and relationships, with their quotes), or
  for a pair what each says about the other. The map's pair rows show the
  latest such line. Recaps are read as Story reads them, whichever
  server or model wrote them, and showing them sends nothing. Story
  pauses while a character page covers it.
- A pair's Profile reuses the first name's profile and shows the
  relationship it gives for the second, matched by whole words. When it
  gives none it says so and shows the full profile. There is no new
  prompt.
- The profile header shows Codex's kind, mention count and chapter span
  when the scan has the name. An alias Codex also knows opens Mentions
  narrowed to that alias; others expand their quotes in place.
- With a service chosen and the on-device codex off, the sheet still
  opens with Story and profiles. Names, Relationships, Mentions and the
  scan are hidden, and nothing is read from an earlier scan's index.
- Search's pill opens the sheet too; with the scan off it opens the
  query's Profile.
- Closing Codex cancels a lookup in flight. Peeks hide and show one
  sheet, and the peek bar's Show button always reads Companion.

Lookups and recaps read their passages from Codex's store when the scan
has committed every chapter they need under the same identity (content
fingerprint, chapter boundaries, `CodexStore.FORMAT` and
`PassageText.VERSION`), and walk the book as before otherwise. Stored
passages are rebuilt without the run-on text Codex keeps for continuity
and filtered by the same eligibility rule, so the list sent is the one
the walk would build. A test holds the two equal.

## Consequences

One Back stack, one peek and one Return serve both features, and a
profile no longer costs a second walk of the book once Codex has
scanned it. The profile's separate sheet and its Codex button are gone.
Recaps the phone already holds answer part of a profile with no request.
What a request contains is unchanged. Two names for one feature remain
in the code; renaming the classes and tables was not worth the churn.
