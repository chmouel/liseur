# 45. Translated pages save coarse positions

Status: accepted

## Context

Translate first showed one selected passage in a sheet. Readers asked for
the book itself in another language, the way read aloud follows the text:
start from a sentence and keep going.

Doing it in the page means the words on screen are no longer the book's.
Several parts of the reader rely on those words. A saved position quotes
the text around it so another device, or the same one after a reflow, can
find it again. BookOrbit receives an EPUB CFI built from the page. A
highlight and a bookmark record the quoted text they belong to. Built from
translated text, any of these would point at words the book does not
contain.

## Decision

Translation in the page walks the same sentences read aloud speaks
(`PublicationUtteranceCursor`) and replaces each one in the page as its
translation arrives, a few sentences ahead of the reader. Injected
JavaScript keeps each changed text node's original and redraws from it,
so Stop and read aloud put the book back exactly. Translations stay in
memory for the session; nothing is written to disk.

> Amended by [ADR-0046](0046-translated-sentences-are-kept-on-the-phone.md):
> translated sentences are now saved on the phone, outside backups.

While a page shows a translation:

- Positions keep saving, without quoted text: href, progression and the
  element's CSS selector remain, which places the reader on the right
  element after a reload or on another device.
- BookOrbit is not sent a position, since its CFI would be computed from
  translated text.
- Highlights are hidden, and new highlights, notes and bookmarks are off.
  Existing ones come back on Stop.
- Starting read aloud stops the translation and restores the page first,
  so the voice reads the book's words.

Fixed-layout books are not offered the mode.

## Consequences

- A position saved while translated is a little less precise: it lands on
  the right paragraph rather than the right sentence.
- Layout moves as translated sentences are longer or shorter. The reader's
  place may shift slightly after a batch; this is accepted.
- A sentence whose text the page does not contain as Readium extracted it
  stays in the original and the run moves on.
- A network service receives each sentence near the reader with the one
  before it as context, the same destination the Translation settings
  already name. The bar says which service is translating.
