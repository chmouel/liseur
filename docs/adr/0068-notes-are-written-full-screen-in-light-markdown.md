# 68. Notes are written full screen, in light Markdown

Status: accepted

Amends [27. A tapped note opens as a note](0027-a-tapped-note-opens-as-a-note.md).

## Context

Notes were written in `NoteDialog`, a Material `AlertDialog` holding a
three-line field. It was the one piece of note handling left out of the
reading theme's care, and it had problems of its own. A tap beside it
or Back threw away what had been typed, and a note can be paragraphs
long. The colour of the mark could not be chosen while writing. Save was
disabled on an empty field, so a note, once written, could never be
taken off its highlight short of deleting the highlight. Notes were
plain text, and the companion was already saving answers with `-`
bullets that showed as dashes.

## Decision

`NoteEditor` replaces the dialog. It is a full-screen `Dialog` painted in
the reading theme like `NoteSheet`, with plain ink on e-ink. Top to
bottom: close, title and Save; the passage with the mark's colour down
its side, folded to four lines with Show more; the colour chips when the
palette offers more than one; the field; and a toolbar over the keyboard.

- Leaving with changes, by Back or close, asks Discard or Keep editing.
  Leaving unchanged closes at once. The draft survives the activity being
  recreated.
- The colour picked goes in with the words in one save, so recolouring
  and rewording are a single sync write. Saving the same words in a new
  colour does not stamp the note as edited.
- An empty field over an existing note turns Save into Remove note: the
  mark becomes the plain highlight it was before (`NoteText.cleared`).
  On a book note it is Delete note and the row goes. liseur-sync already
  carries an empty body as a highlight, so the protocol is unchanged.
- The field opens focused with the keyboard up, the cursor at the end,
  sentence capitalisation and autocorrect.
- Notes may carry a small Markdown subset: `**bold**`, `*italic*` or
  `_italic_`, `-` and `1.` lists, `>` quotes, and `#` to `######`
  headings, typed or written by the companion. The toolbar writes all
  but headings and lights up what is in force at the cursor; Enter
  carries a list on and ends it on an empty item. While typing, the characters stay as
  they are, so the cursor never jumps, with the words styled and the
  markers dimmed. `NoteSheet` and the contents list draw it with the
  markers gone. Line breaks are kept as typed.

The note is stored exactly as typed. Sync, backup, share and the
notebook export (already Markdown) pass it through untouched. Nothing
outside Liseur draws the formatting, which is why the subset is the part
of Markdown that still reads well as plain text.

## Consequences

- Other clients (liseur-desktop, the liseur-sync web reader) and
  servers show the raw markers.
- `NoteMarkdown` and `NoteFormatting` are plain Kotlin, tested on the
  JVM. Unclosed or space-padded markers stay literal, so `2 * 3 * 4` and
  `snake_case` are left alone.
- Headings, links and checkboxes are not part of the subset.
- `ReaderScreen` gained no state: the editor owns its draft, which keeps
  the reader composable clear of the verifier limits it sits near.
