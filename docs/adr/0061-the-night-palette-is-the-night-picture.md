# 61. The night palette is the night picture

Status: accepted

## Context

With wallpaper colours off, the dark theme was the day palette's warm
neutrals walked down: brown-black surfaces, cream text and a peach
accent. The library bar shows a painted tile of a woman reading in a
navy room. On the brown bar that tile read as a navy sticker pasted on
the app, and the cream text did not belong to either.

Repainting the art would fix one picture and leave the next one with
the same problem. The palette is the part we own.

## Decision

The night palette is taken from the room in the night picture.

- The wall gives the surfaces: a muted ink blue, #11161F, with the raised
  steps kept to the same hue so cards and sheets do not drift purple or
  teal as they rise.
- The moon in the frame gives the text: #E3E8F0, and #A9B3C2 for quieter
  text.
- The cushion gives the one warm accent: #EE9F82, with #8E4A33 behind
  filled buttons.
- Icon discs and the selected chip or segment are slate, #384963, with a
  lamp-lit terracotta, #F4B09A, on top. The slate is lighter than the
  sofa it comes from because the default card is #2C3441 and a selected
  filter chip has no border; anything closer leaves the chip with no
  shape.

The dark window and splash background, the home screen widgets and the
widget picker previews use the same values.

The light palette, the e-ink palettes, wallpaper colours and the reading
page themes are unchanged. In the reader only the accents and their
containers come from this palette, so slider tracks on a dark page are
now slate where they were brown.

Every text pair is at least 4.5:1. Body text on the surface is 14.7:1.

## Consequences

The dark theme reads as cool with one warm thing in it, as the picture
does, and the tile looks like a window into the same room.

Store screenshots taken in dark mode with wallpaper colours off show
the old palette until they are retaken.
