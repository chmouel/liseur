# 40. Auto-scroll controls on the page

Status: accepted

Amends [6. Auto-scroll](0006-auto-scroll.md) and
[1. One Advanced entry, and nothing else](0001-advanced-reading-menu.md).

## Context

Auto-scroll shipped as one row in the Advanced sheet: a switch and a
speed slider, shown only in a scrolled book. Once running, its only
control was the chrome. A tap on the page raises the chrome and stops
the text, and another tap hides it and starts the text again.

That works, but it asks for too much. A reader who has just chosen to
read by scrolling finds the switch that makes the page move two sheets
away, under settings they change once a year. Once the page is moving,
changing the pace means opening the chrome, the typography sheet and
the Advanced sheet, finding the slider, and backing out through all
three. Pausing on purpose has no control of its own. It happens to be
what the chrome does.

## Decision

**The switch sits beside the scrolling switch.** "Scroll the page for
me" moves to the typography sheet, directly under "Read by scrolling",
and shows only while the book is effectively scrolled: vertical text
counts, a fixed-layout book does not, the same test
[ADR 6](0006-auto-scroll.md) uses everywhere else. It is turned on
every time a reader sits down to read that way, which is the
"changed frequently" exception `DEVELOPER.md` allows to the rule that
new reading settings default to Advanced. Turning it on still closes
the sheet and the chrome and starts the page moving.

**The speed stays in Advanced.** The pace is chosen once and left
alone. The slider stays where it was, still shown only in a scrolled
book.

**The page gains a small control while auto-scroll is on.** It is a
pill at the bottom centre, painted in the reading theme like the
jump-back and catch-up pills, with four buttons and the current speed
notch:

- pause or play,
- slower and faster, one notch at a time on the same stored setting
  as the slider (`AutoScrollPreference.nudge`),
- stop, which turns auto-scroll off.

It works like a video player's controls. It shows when auto-scroll
starts and each time one of its buttons is used, then fades after
`AUTO_SCROLL_CONTROLS_LINGER_MS` (three seconds), stretched to whatever
timeout the reader has asked the system for through the accessibility
settings. It stays up for as long as the page is paused, since play is
the way back, and while the chrome is up. It steps aside while a
jump-back or catch-up offer is showing, because those already hold the
page still and two pills compete for the same tap. On electronic paper
it appears and disappears without a fade. The whole rule is
`autoScrollControlsShown`, a pure function with its own tests.

**Pausing is now a state of its own.** `autoScrollPaused` joins the
`canAutoScroll` predicate. Pause holds until play is pressed; a page
tap that raises and then drops the chrome does not undo it. It resets
whenever auto-scroll is turned on or off, which includes the book
stopping being scrolled.

**The tap still pauses.** A tap on the page raises the chrome and
stops the text, as ADR 6 says, and the pill comes up with it, showing
play. Pressing play drops the chrome and carries on. A tap that drops
the chrome also carries on, as before, unless the reader paused with
the button.

## Consequences

ADR 6 said the reader gains no new chrome for auto-scroll. That no
longer holds: one pill, shown only while auto-scroll is on and faded
out while the page moves undisturbed.

ADR 1 listed auto-scroll among the things the Advanced sheet exists
for. The switch has left; the speed remains, so the sheet still has
an auto-scroll row in a scrolled book.

A touch on the pill counts as a finger on the page for the moment it
is down, so the text holds still under a press. A tap on the pill
never reaches the web view, so it is never taken for a chrome tap.

*Where:* `reader/chrome/AutoScrollControls.kt`,
`reader/chrome/AutoScroll.kt`, `reader/chrome/TypographySheet.kt`,
`reader/chrome/AdvancedSheet.kt`, `data/settings/ReaderPrefs.kt`,
`reader/ReaderScreen.kt`.
