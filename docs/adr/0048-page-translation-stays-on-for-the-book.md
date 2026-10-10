# 48. Page translation stays on for the book

Status: accepted

## Context

"Translate the page from here" (ADR-0045) lived only in the reader's
screen. Leaving the book, or Android killing the app in the background,
turned it off: the book reopened in its own language and the reader had
to select a sentence and ask again. With the reader's controls down,
nothing on the page said its words were a translation either; the
translation bar only shows with the controls up or when a run stops on
an error.

## Decision

A book stays translated, between the same two languages, until the
reader taps Stop on the translation bar or starts read aloud. Closing
the book, or the app being killed, keeps it on.

- The choice is a row in `translations.db` per local book URL, holding
  the two language codes and no text. Removing the book deletes it with
  the book's sentences. Clearing saved translations in Settings keeps it,
  since it is a choice and not a cache.
- Reopening the book starts translating again from the place it opens
  at, once the opening restore has finished and the page is still, with
  the translation service selected now. If the page never settles, or
  the reader starts or stops something first, it does not start, and the
  book stays marked for next time.
- The translation bar shows for about three seconds when this happens,
  so the reader sees why the page is not in the book's language.
- While translated with the controls down, a faint translate mark sits
  in the top corner across from the bookmark ribbon. A tap brings the
  controls up, where Stop is.
- Changes to the choice go through one queue in order, outside the
  reader, so Stop followed at once by Back, or by reopening the book,
  still leaves it off. If Stop cannot be saved the reader is told,
  because the book would otherwise turn translation on again by itself.

## Consequences

- Opening a translated book can send requests without a fresh tap. The
  saved sentences (ADR-0046) often make that free, but not when they
  were evicted or the service, model or context changed.
- A start killed in the few milliseconds before its choice is saved
  reopens as before it.
- Fixed-layout books never get a choice, since only reflowable pages
  translate.
- `translations.db` is at version 2; the migration only adds the table.
