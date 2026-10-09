# 46. Translated sentences are kept on the phone

Status: accepted

## Context

Page translation (ADR-0045) kept its translations in memory for one
opening of a book. Closing the book lost them, so a chapter read again
was asked for again: money on paid servers, time on all of them, and a
wait for the reader every time.

## Decision

Each sentence page translation translates is saved in `translations.db`,
a Room database separate from `liseur.db`.

- The row belongs to the local book URL and is keyed by a hash of the
  service, server, model, languages, prompt version, the previous
  sentence and the sentence. A different service, model or language pair
  misses by design. Renaming a server does not.
- Device translations are saved as well as network ones.
- The store holds at most 50,000 sentences and 20 million characters,
  dropping the least recently read first.
- Removing a book deletes its sentences, by sweeping rows whose book the
  library no longer holds. The sweep runs at start and after each
  committed change to the library, since a removal may still be inside a
  caller's transaction when it returns. A book removed and added back
  under the same URL before the sweep looks keeps its sentences: they
  are keyed by the exact text, so they still translate it.
- Translation settings show the count and size and can clear them all. A
  reply asked before a clear, or before its book was removed, is shown
  but not saved.
- Passages translated in the sheet are not saved.

## Consequences

- Reading a translated chapter again costs nothing and appears at once.
- Translated text from books is now on disk. It is app-private, never
  backed up (the backup rules name `liseur.db` only), and listed in
  `docs/PRIVACY.md`.
- A store that cannot be read or written only costs a request: the
  translation is still shown.
- Changing the prompt needs a `TranslationPrompt.VERSION` bump, or old
  answers to the old prompt would keep being served.
