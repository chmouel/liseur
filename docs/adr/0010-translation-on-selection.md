# 10. Translation on selection

Status: proposed
GitHub issue: [#3](https://github.com/chmouel/liseur/issues/3)

## Context

Reading in a second language means meeting sentences, not just words.
The Wiktionary lookup answers "what does this word mean" but not "what
does this clause say", and switching to a translator app loses the
selection and the place. The constraint is real, though: Liseur's
network story is the book server plus opt-in dictionary lookups, and a
translation feature must not quietly widen it.

## Decision

A **Translate** action in the selection popup, sending the selection to
a translation endpoint the user configures themselves: a LibreTranslate
instance or anything speaking its API. Off until an endpoint is entered;
no default server, not even a FOSS one, because a default turns opt-in
into opt-out.

Fit with Liseur's simplicity: one action in the existing selection
popup, one endpoint field on the existing dictionary settings screen.
The result appears in the same bottom sheet the dictionary uses.

## Design

The settings live beside the dictionary lookup opt-in in
`data/settings/AppSettings.kt`: an endpoint URL and a target language,
both empty by default. The popup only shows Translate when the endpoint
is set, the same visibility rule the dictionary action follows.

The client is a sibling of `WiktionaryClient`: one POST, blocking work
on `Dispatchers.IO` inside the client as the convention requires, every
failure surfacing as a quiet "couldn't reach the translator" line in
the sheet rather than an error dialog mid-book. The result sheet is
`DefinitionSheet` generalised, or a near-copy if generalising it costs
more than it saves.

This extends the documented network exception, so `docs/PRIVACY.md`
gains a line: selections you translate are sent to the server you
configured, and nowhere otherwise.

## Consequences

Selected passages leave the device for a server the reader chose,
named in settings, and only when they press Translate. A reader who
self-hosts LibreTranslate gets translation with no third party at all.
There is no offline mode; bundling a translation model is the offline
dictionary decision again, already turned down in `docs/ROADMAP.md`.

*Where:* `data/settings/AppSettings.kt`,
`reader/annotations/SelectionPopup.kt`, `reader/dictionary/`,
`ui/settings/`, `docs/PRIVACY.md`.
