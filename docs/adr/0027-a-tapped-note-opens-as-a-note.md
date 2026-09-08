# 27. A tapped note opens as a note

Status: accepted

## Context

Since a highlight could be edited by tapping it, every tapped mark
brought up `SelectionPopup`: the row of colour chips, Note, Define,
Search, Share and Delete, placed over the passage. That bar is right for
a plain highlight, where the only thing to know about the mark is its
colour and the chips show it.

A mark with a note is a different object. The reader put words there,
and the thing they want on tapping it is to read them. The bar hid the
note behind its "Note" button, and that button opened the *editor* — a
Material dialog with a text field and a keyboard — when what was asked
for was to look, not to write.

## Decision

A tapped mark that carries a note opens `NoteSheet` instead of the bar.
A mark with no note, and any passage the reader selects by hand — even
over a noted mark — still gets the bar, because a hand selection was
reaching for the words.

The sheet is a bottom sheet painted in the reading theme, for the reason
`FootnoteCard` is: it sits over the page, and a white sheet over a black
page at night is a lamp in the face. `LiseurModalBottomSheet` learned to
take a container and content colour for it; every other sheet keeps
Material's.

It shows the passage, quoted and italic, with the mark's colour as a
stripe down its side; the note in full; where and when it was written,
and when it was reworked if that was a later sitting. Under those come
the colour chips, Share, Delete and Edit. Edit is the existing
`NoteDialog`; nothing about how a note is stored, identified or synced
changes.

Recolouring does not close the sheet. The stripe changes where the
reader is looking, which is the confirmation; closing would send them
back to the page to check. The annotation list refresh already carries
the new colour into the open sheet.

Share sends the passage first, quoted, and the note under it — the bar
shares the passage alone, because there is nothing else to share.

## Consequences

- The sheet reuses the same `tappedSelection` state the bar does, so
  every guard the bar earned — no page turn, no auto-scroll, no curl
  while it is up; cleared on a position change, a hand selection, a
  navigator swap or an image opening — applies to it unchanged.
- The two stamps on a mark are in different units (`created_at` in
  milliseconds, `updated_at` in microseconds, since the latter goes to
  liseur-sync verbatim). `NoteText.edited` is where that is reconciled,
  once, and it treats anything inside a minute as one sitting.
- On electronic paper the sheet's quiet text is plain ink rather than a
  wash, since a wash under half ink dithers to grey.
