# 72. Reading status choices

Status: accepted

## Context

A position does not say whether a reader intends to begin, has paused,
or has stopped reading a book. liseur-sync records these choices as
status acts independently of positions.

## Decision

Expose automatic, unread, want to read, reading, paused, did not finish,
and finished choices. An explicit choice wins over the position until it
is changed. Automatic withdraws the choice. The unread action retains its
existing position reset; other choices do not move the locator or reading
history.

Want to read belongs on the unread shelf. Paused and did-not-finish books
remain accessible but are not suggested as next up or resumed on launch.
Backups include the choice alongside any position, including when no
position exists. Restoring a choice does not move the stored position.

## Consequences

Peers that cannot represent a choice receive their closest supported
status or position. An unrecognized liseur-sync choice stays pending until
it is superseded or the client understands it. Older backups without a
choice leave the existing choice unchanged.
