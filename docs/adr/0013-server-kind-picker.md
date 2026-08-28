# 13. Picking a kind of server from a row and a sheet

Status: accepted

## Context

[Issue #96](https://github.com/chmouel/liseur/issues/96). The kind picker
on the book-server screen has been rebuilt once per kind added. It was a
`SingleChoiceSegmentedButtonRow` while there were two, and adding
Grimmory (#94) took four labels — "calibre-web", "Komga", "Grimmory",
"liseur-sync" — past what a phone's width holds. A segmented control does
not wrap, so it became a `FlowRow` of `FilterChip`s.

That works, and on a phone it is two rows of chips above a
`surfaceContainerHigh` card of 20.dp padding above three fields above a
button. The whole first screenful is spent on a choice most readers make
once, and three things are wrong with it:

- **The chips carry no information.** A reader who does not already know
  which of these they run learns nothing from four product names, and
  the card underneath explains only the kind already selected. There is
  no state of this screen in which the four can be compared.
- **It gets worse as it grows.** Every kind added makes every chip
  narrower. A fifth wraps to a third row or starts truncating.
- **The card is tall and mostly says what the fields say.** Two of the
  four `server_intro_*` strings are "give the address of your server and
  the login you use for it", which is the `Server address` and `Password`
  labels restated at four times the height.

## Decision

One `OutlinedCard` holding a `ListItem` — overline "Which kind of
server?", headline the selected kind, supporting its tagline and one
line on whether it keeps your place, trailing a chevron — which opens a
`ModalBottomSheet` listing every kind in the same shape with a radio
button. `ServerKindRow` and `ServerKindSheet`, in their own file.

The height at the top of the form goes from two chip rows plus a
five-line card to one three-line row plus a link, which puts the address
and credential fields above the fold on a phone. The sheet is where
comparison finally happens, and it scrolls, so a fifth kind is a `when`
branch and three strings with no layout consequence at all. That is the
test the issue sets, and it is the one the chips fail.

### The rejected directions

**A list of kinds, with the form on a second screen.** The most room per
kind, and the most expensive: a nav destination, a back stack that has to
survive process death holding a half-typed form, and a reader who wants
to change kind after a failed connect going back a screen to do it. It
also makes the first thing a reader sees a wall of prose, when most of
them know which server they run and want the address box.

**An `ExposedDropdownMenuBox`.** The smallest footprint, and it fixes
height and scaling — but a menu item is one line, so the taglines have
nowhere to go and we are back to four bare names. It also reads as a
form field among form fields, when it is the switch deciding which form
fields exist.

**Keeping the chips and collapsing the card to its tagline.** The
smallest diff by far, and it leaves the fault that will bring us back
here: the chips still narrow with every kind. It makes the comparison
problem worse, too, by moving the explanation off-screen entirely.

## What the `server_intro_*` strings became

Retiring them is the only lossy part, so each clause was placed rather
than dropped:

- Grimmory's "not the login you sign into Grimmory with in a browser"
  was already duplicated in `server_opds_user_help`, shown inline under
  the password field. It stays exactly there. Naming the credential a
  server actually wants is the thing this screen most has to get right,
  and it belongs next to the box, not in a paragraph above it.
- Grimmory's "it cannot keep your place in a book here" is now its sync
  line, which the reader sees *before* choosing rather than after. The
  issue asks that this survive; it is better served than it was.
- liseur-sync's "books that came from nowhere but this phone" moved into
  its tagline, where it is the distinguishing thing to say about it.
- calibre-web's and Komga's are "give the address and the login", which
  the field labels and `server_api_key_help` say already.

## A kind's sync ability is not an account's

`ServerKind.syncAbility` (`EXACT` / `PROGRESSION` / `NONE`) is new, and
deliberately not `RemoteServer.canSync`. They answer different
questions: the picker asks what a kind *can ever* do, with no account in
existence yet, while `canSync` asks whether this account is ready now —
and for calibre-web that waits on a Kobo token, which is an account's
problem and not a reason to warn a reader off the kind. Folding them
together would mean a picker inventing an account to interrogate, or a
repository offering calibre-web a sync it has no token for.

They still have to agree about the one case that matters, so
`ServerKindTest` pins it: for an account holding every secret,
`canSync` is true exactly when `syncAbility` is not `NONE`. A fifth kind
cannot promise a sync in the picker and refuse it on the connected
screen.

`PROGRESSION` versus `EXACT` is not decoration. calibre-web's Kobo
protocol exchanges `locations.totalProgression` and nothing else, so the
page comes back approximately; Komga and liseur-sync exchange a whole
locator. A reader choosing where their library lives is entitled to know
that before typing a password.

## Consequences

The form is one screen, one ViewModel, no navigation change and no
persisted state: sheet visibility is a local `rememberSaveable`, so
rotation is free and `onKindChange` keeps its signature.

Switching kinds costs one more tap than a chip did. That is the trade,
and it is a small one — switching is something a reader does while
working out which server they have, and the sheet is better at that than
four names ever were.

`ServerAccountScreen.kt` was 1300 lines. The picker leaving for
`ServerKindPicker.kt` takes the per-kind label, tagline, home URL and
link lookups with it, which is where the next kind will be added.

## Logos, and the one we cannot ship

Rows carry each kind's own mark, which is what makes a list of four
scannable rather than four paragraphs to read. Three are ours to ship:
calibre-web's is GPL-3.0 and Grimmory's AGPL-3.0, both with the rest of
their source and with no carve-out for the artwork, and liseur-sync's
was drawn here. The two upstream marks are unmodified, converted to
VectorDrawables, with their notices and licence texts added to the
Licences screen.

Komga's is not. Its repository is MIT, but the icon is, by Komga's own
README, "based on an icon made by Freepik from flaticon.com", and
Flaticon's licence is neither transferable nor sublicensable — so
Komga's MIT cannot reach it, and F-Droid's inclusion policy requires
every bundled asset to be redistributable. Komga therefore gets a
neutral book-server glyph, tinted with the theme rather than left in
colours of its own, so it reads as a placeholder and not as a mark
somebody chose. Drawing something Komga-shaped instead would have been
worse than either shipping theirs or shipping none: it puts invented
artwork under their name.

This is a licensing fact about one upstream project, not a rule about
the picker. If Komga relicenses the icon, `ServerKindLogo` gains a
branch and loses one.
