# 35. An SVG cover is drawn, not decoded

Status: accepted

## Context

A book whose cover is an SVG has a blank tile on the shelf. The same
book reads perfectly, and its cover appears in full inside it, because a
page is a WebView and a WebView has drawn SVG for years. The shelf is
not a WebView. It is a bitmap, and the platform has never had a way to
make one from an SVG: `BitmapFactory` decodes BMP, GIF, JPEG, PNG, TIFF
and WEBP, and that is the whole list.

So Readium's `publication.cover()`, which resolves both ways a
publication can point at its artwork — the EPUB 3 `cover-image`
property and the EPUB 2 `<meta name="cover">` pointer at a manifest id —
comes back empty for such a book however plainly it declared itself. The
guess that follows, an image the book called `cover`, was gated on
`MediaType.isBitmap`, so it turned `cover.svg` away too.

What the reader sees is a book that reads beautifully and has no cover,
beside the same title in EPUB 2 that has one (#233). Nothing about that
looks like a limitation of the format.

## Decision

Draw it, at import, into the same JPEG the raster covers already produce,
with AndroidSVG.

### Why a dependency

There is no way to avoid one short of rendering the SVG in an offscreen
WebView and photographing it — a main-thread, view-hierarchy-shaped
operation, wanted here in a batch on a background thread while a library
is being scanned.

`com.caverock:androidsvg-aar` is Apache 2.0, on Maven Central, has no
transitive dependencies and adds about 220 KB of classes. It is old — 1.4
is from 2017 — but SVG 1.1's static profile, which is all a cover uses,
is older still and has not moved. It meets the FOSS and F-Droid
constraints the project holds every dependency to.

Not `coil-svg`, which would have brought the same library in behind
Coil's decoder: covers are rasterized once at import and read back from a
file, so a decoder on the display path would be work done on every scroll
for a picture that has to exist as a file anyway.

### Why at import, and what that costs

Covers are already resolved once, when a book is indexed, and stored at
`filesDir/covers/<sha1>.jpg`; everything that shows a cover — library,
series, details, statistics — reads that file. Drawing the SVG there
means nothing downstream learns that a cover ever was one.

The cost is that **a book already on the shelf with no cover is not
revisited**. A cover is resolved when a book is indexed, when its file
changes, and when it is downloaded — never on a launch. A reader
upgrading into this keeps the blank tile until the book is added again.
That is a deliberate trade: the alternative is a `cover_checked` column,
a migration and a backfill pass that reopens every coverless EPUB in a
library, and a book that genuinely has no cover is far commoner than one
whose cover is an SVG. It can be added later without changing anything
here.

### Three routes, in this order

1. `publication.cover()` — what Readium can decode.
2. A declared `rel=cover` whose media type is `image/svg+xml`.
3. An image named `cover`, now raster *or* vector.

The new route sits in the middle because a declaration is a statement and
a filename is a guess, which is the rule the fallback was already written
around.

### The wrapper case

A cover SVG is very often a frame rather than a drawing: a `<svg>` whose
entire content is an `<image>` pointing at the JPEG beside it. Rendering
those without resolving the reference produces a blank page — worse than
the blank tile, because it looks deliberate.

AndroidSVG asks for such files through an `SVGExternalFileResolver`, and
two facts about it shape the code:

- It is **registered statically**. There is no per-document resolver in
  1.4, so a resolver holding one publication would be the resolver for
  every publication, and two books drawn at once could hand each other
  their artwork. One resolver is therefore registered once and answers
  from a `ThreadLocal` map set around the render, which is synchronous:
  the thread that puts the images there is the thread that asks for them.
- Its callback **cannot suspend**, and Readium's reads do. So the
  referenced files are read before the render rather than during it: the
  SVG is parsed for `<image>` hrefs, each is resolved against the SVG's
  own URL inside the publication, and a few are read and decoded through
  the same bounded decode a guessed raster cover goes through.

The resolver answers from that map and from nothing else. One that could
read during a render would be a callback that blocks, and one that could
read from anywhere would be a way out of the book.

### What bounds it

An SVG is bytes from an archive that may have come from anywhere, and it
is a program more than it is a picture. So:

- **Bytes**: 4 MiB, a quarter of the raster bound, because XML costs what
  its tree costs rather than what its bytes do.
- **Entities**: `setInternalEntitiesEnabled(false)`. A cover has no use
  for a DTD, and entity expansion is the classic way to turn a few
  kilobytes into a heap dump.
- **Pixels**: a vector has no size, so one is chosen — the long edge at
  1600, whichever way round the cover is. That fixes the area below the
  budget the raster path subsamples down to, so neither route can be
  talked into an allocation by a file. An infinite or undefined dimension
  is treated as an absent one; left in, it rounds to `Int.MAX_VALUE`
  pixels.
- **Images**: four. A cover needs one.
- **Geometry**: a document stating neither its dimensions nor a viewBox
  has nothing to scale and is not drawn.

### A bound that was never reached

Capping a read by asking for one byte past the cap and refusing whatever
fills it is the obvious way to tell a file at the limit from one over it,
and it is what the raster fallback was already written to do. It does not
work: Readium answers a range that runs past the end of an entry with a
decoding failure rather than the bytes that are there, whatever `read`
documents. Every cover small enough to want came back as an error, which
is why the named-cover fallback had quietly never produced a cover on a
device. Both routes now ask the entry how long it is, refuse it on that,
and read exactly that range — measuring the bytes that arrive all the
same, since the length is a hint.

An entry that will not say how long it is is refused rather than read
whole. A whole-entry read is a read with no bound, and the size in a zip
directory is written by whoever wrote the zip.

### White underneath

SVG has no background, and the file this ends up in is a JPEG, which has
no alpha. Without an opaque white fill before the render, every cover
drawn on nothing would arrive on the shelf as a black tile.

## Consequences

- A book whose cover is an SVG looks like a book.
- The rules are in `data/library/SvgCover.kt`, apart from Android where
  they can be, so what the reader ends up looking at is decided by
  functions that can be tested without a device — as `coverSampleSize`
  and `isNamedCover` already were. The render itself is checked under
  Robolectric's native graphics, which draws for real.
- Covers that arrive from a server as a URL are untouched: Coil loads
  those, and calibre-web, Komga and liseur-sync all serve raster. An SVG
  cover served over the wire would still be blank, and nothing yet serves
  one.
- Libraries shelved before this keep their blank tiles until the books
  are added again. See above; this is the part to revisit first if it
  turns out to matter.
