# 51. Character profiles use only earlier chapters

Status: accepted, amended by
[57. Codex tells the story so far](0057-codex-tells-the-story-so-far.md)
and [58. Codex hosts the character profile](0058-codex-hosts-the-character-profile.md)

## Context

Readers forget who a character is, and asking a model about a name is the
quickest way to find out. A model asked without care answers from the
whole book, or from what it remembers of it, and spoils what comes next.
Even the chapter the reader is in can give away a reveal on the next
page. The answer has to be safe without the reader having to judge it.

## Decision

A character profile is built only from the chapters strictly before the
one the reader asked from, and that limit is enforced on the phone:

- Chapters come from the publication's contents mapped onto the reading
  order. Text that might belong to the asking chapter, because a
  boundary cannot be located, is treated as if it did, and withheld.
- The asking chapter is frozen when the panel opens and kept while the
  reader follows a source and comes back, or picks between two people
  with the same name.
- The model gets the selected name and earlier passages only. It
  searches and reads them through bounded JSON actions over ordinary
  chat completions, so any OpenAI-compatible server works without
  native tool support, and every claim it makes must cite passages it
  was given. The quotes the reader sees are the book's own text.
- Background chapter recaps are opt-in, made only for chapters the
  reader finished by reading on, and used only to find passages; their
  wording is never sent to a lookup.
- Following a source is a peek that does not move the saved place.

There is no AI-generated list of characters, follow-up chat, web lookup
or recap browser (the on-device Codex indexes names locally under the
same earlier-chapters ceiling without sending anything to a model).

## Consequences

- Nothing from the asking chapter or later is sent to the server.
  A model may still know a famous book from its training; the prompt,
  the mandatory citations and showing the book's own quotes reduce
  this but cannot rule it out, and the privacy policy says so.
- Books with poor contents get fewer eligible chapters, never more.
  A book without usable contents is one chapter and has no profiles.
- A lookup costs up to ten requests, and recaps cost one or more
  requests per finished chapter, on the reader's own server account.
- A lookup in a chapter with no recaps still works, by searching the
  earlier chapters directly.
