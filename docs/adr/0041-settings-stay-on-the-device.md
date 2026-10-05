# 41. Settings stay on the device

Status: accepted

Supersedes [34. Settings travel by when they were changed](0034-settings-travel-by-when-they-were-changed.md).
Server side: liseur-sync ADR-0050, "Settings stay on the device".

## Context

ADR-0034 made reader settings follow the reader: a font picked on the
phone showed up on the tablet. In practice the devices are not the same
kind of thing. A phone, a tablet and an e-ink reader want different
sizes, margins and themes, and a choice made on one kept landing on
another that did not want it. Every fix pulled in more machinery: a
collector stamping each change, dates for the moved font-size default,
a cap for clocks set into the future. All of it existed to settle
arguments between devices that should not have been arguing.

## Decision

**Each device keeps its own settings.** A liseur-sync server stores the
syncable settings under the device the token belongs to and shows them
to nobody else, so it is a backup of this device's choices, not a
shared copy.

**This device is the only writer of its copy.** No conflict is left to
settle, so the change collector (`SettingsChangeTracker`), the change
stamps and the font-size default migration are gone.
`SettingsSyncRepository` keeps one fact per account and key: the value
this device last saw stored there. It also notes the server device id
that record was made as. An account can come back as another device,
for instance through a pasted token, and that device's copy is not the
one the record describes, so the record is dropped and the next pass
restores instead of uploading over it.

**A pass restores, then uploads.** For a key this device has never
stored on the account, a value the server already holds is its own
earlier copy, and it is applied, still held back while a book is open. After that,
whatever differs from the server is uploaded, dated after the copy it
replaces so a clock set back cannot get it refused.

**An older server is left alone.** The server answers with
`"scope": "device"`. A server that does not is one that still shares a
single copy across devices, and this build neither reads from it nor
writes to it.

**The old bookkeeping is cleared once.** The first time the new build
opens the store it drops everything the cross-device version kept, so
the next pass uploads this device's settings afresh.

## Consequences

Setting up a second device means choosing its settings on that device.
That is the trade: what was convenient on day one turned into a font
size overwritten on a reader who never touched it.

The server copy is keyed by the device id, so only a sign-in that keeps
that id can restore it: signing in again while the account is still
remembered on this device. A disconnect forgets the account and its id,
and a clean reinstall never had them, so either one signs in as a new
device. That device finds nothing on the server, keeps the settings it
already has and uploads them. Carrying a device id across a reinstall
was left out on purpose, because the same mechanism could hand one
device's copy to another.

Positions, annotations and statistics are unchanged. They are about the
book, not the screen, and still sync across devices.
