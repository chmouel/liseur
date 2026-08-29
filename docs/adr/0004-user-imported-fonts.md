# 4. User-imported fonts

Status: accepted
GitHub issue: [#6](https://github.com/chmouel/liseur/issues/6)

## Context

Four bundled families cover most tastes, but not the reader who has
paid for a font they love, needs OpenDyslexic, or reads a script the
bundled four render badly. The bundle cannot grow to meet them all —
every family is APK weight everyone carries.

## Decision

Let the reader import a font file. Picked through the system file
picker, copied into the app's own storage, offered in the font row like
the bundled four, applied to the open book at once.

Fit with Liseur's simplicity: one "Add a font…" entry at the end of the
existing font list, and imported fonts in that same list, previewed in
their own face, with a remove affordance behind a confirmation. No
manager screen, and — this is what keeps ADR 0001 satisfied — **no new
row**: the font control the typography sheet and Settings → Reading
appearance already share is the one that grows.

## Design

### Storage

Import is `ACTION_OPEN_DOCUMENT`, the bytes copied to `filesDir/fonts/`
— copied, not referenced, so the choice survives the source file moving
or the SAF grant lapsing. The MIME filter includes
`application/octet-stream`, because `MimeTypeMap` has no `ttf` entry and
most DocumentsProviders report a font that way; a filter without it
hides the very files being looked for. Nothing is trusted from the
provider: the format is decided by the sfnt magic, and the extension on
disk with it.

A font is stored as `<sha256>.<ext>` and known by `user:<sha256>`.
Content-addressing is what lets a font be deleted, and the same file
imported again months later, and every book that was reading in it pick
it straight back up — the id was never a handle into a table, it was
always the bytes. `filesDir/fonts/index.json` caches only the display
name, the one thing a font with no `name` table cannot tell us twice; a
lost or corrupt index costs a name and never a font.

The stored preference is the **raw** choice. An `Imported` id whose file
is absent stays in DataStore and in `book_typography` untouched, and
only `ReadingFont.effective(registry)` resolves it to the default for
rendering. Writing the fallback back would lose the choice for good the
first time a book was set apart while its font was missing.

### Serving — what this ADR originally got wrong

The first draft of this section said declaration goes through
`addFontFamilyDeclaration` "with the served-asset scope widened to cover
the app's font directory". **That cannot work.**
`EpubNavigatorFragment.Configuration.servedAssets` is only a pattern
allowlist consulted by `WebViewServer.isServedAsset`. When a path
matches, the request is handed to a `WebViewAssetLoader` whose single
path handler is `AssetsPathHandler`, which reads the APK's `assets/` and
nothing else. Widening `servedAssets` widens *what is allowed*, never
*where it is read from*, so a file in `filesDir/fonts/` returns 404.
Readium's own guide documents only assets-bundled fonts.

A `data:` URL is refused by `Url()` — `AbsoluteUrl` requires a
hierarchical URI and a `data:` URI is opaque. A `file://` URL is refused
by the web view as a subresource of an `https://` page. There is no
`@font-face` hook in `readiumCssRsProperties`, which is a typed
`RsProperties`.

What does work: a request whose host is *not* `readium_assets` is
treated as a publication resource and goes to `publication.get(href)`,
and Liseur builds the `Publication` itself. So the book's container is
wrapped —

```kotlin
container = CompositeContainer(UserFontsContainer(userFonts::value), container)
```

— and each family is declared with an **absolute** source,
`https://readium_package/__liseur_fonts__/<digest>.<ext>`.
`ReadiumCss.normalizeAssetUrl` is `assetsBaseHref.resolve(url)` and
`Url.resolve` returns an `AbsoluteUrl` unchanged, so the assets host is
bypassed. The font lands same-origin with the page, so unlike the
bundled four it needs no CORS header.

The font container goes **first** in the composite. `CompositeContainer`
answers with the first match, so with the book first an EPUB carrying a
resource at the reserved path would shadow the reader's font. Ours is
safe in front because it is registry-strict: it can only ever answer for
a digest it holds, so it shadows nothing else.

Matching is exact-string, against an href built from a font already in
the registry, and rejects `%2f`, `%5c`, `%2e`, dot segments, doubled
slashes and backslashes on the **raw** url before any normalisation —
normalising first is what turns `x/../y` into something that compares
equal. A fragment or query is tolerated rather than refused, because
`Publication.get` looks up `href` and then retries with the query
removed, and turning one away would break Readium's own second attempt.
There is no path concatenation anywhere: the file comes from the matched
font, so traversal has nothing to aim at even in principle.

**Two couplings to record.** `WebViewServer.PACKAGE_HOSTNAME` is
`internal`, so `readium_package` is spelled out in
`UserFontResources.PACKAGE_ORIGIN`; a Readium upgrade that changes it
breaks this quietly, and this paragraph is where to look. And
`MimeTypeMap` has no `ttf`/`otf` entry, so an imported font is served
with a **null `Content-Type`** — verified on device to render anyway,
for both TTF and OTF, because Blink does not content-type-check
`@font-face`. It is the same reason the bundled four are served as
`text/plain` today.

### Applying

All imported families are declared up front, not just the selected one,
so switching between them is instant as it already is for the bundled
four; the web view only fetches the family the page uses.

An import applies to the open book immediately. `ReaderScreen`'s
`key(columnMode, scrollMode, fontKey)` is what actually tears the
fragment down and rebuilds it — rebuilding the factory alone leaves the
live fragment in place — so a generation key over the imported ids is
threaded through that, the factory `remember`, and the `restoreTarget`
snapshot, which is what keeps the reader on the page they were on rather
than where the book opened. This is the mechanism a column-mode change
already uses.

Rebuilding makes a family *available*; it does not choose it, and
somebody who has just picked a font file plainly means to read in it. So
a successful import is followed by a selection through the existing
path, which already knows whether this book is set apart. `AlreadyPresent`
selects too: re-picking a font you have should land on it, not appear to
do nothing.

`ReaderViewModel.open()` awaits the repository's first scan before
taking its font snapshot, or a book already set to an imported font
opens in the default for a beat and then reflows — unasked for, on the
screen someone was trying to read.

### Backup

Fonts are **excluded from cloud backup** — they are user-supplied
binaries, up to 32 of them, against a 25 MB quota — and **included in
device transfer**, which is not quota-limited and is where "everything
is as it was on my new phone" is the promise. A cloud restore therefore
lands on the dormant-id path above: books fall back to the default, and
re-importing the same file brings every choice back.

## Consequences

One imported file is one face: no italic, no bold axis unless the file
is variable. The page falls back to synthesised styles, which is what
every other reader does with a single-face import.

Licensing stays the reader's own business, as with any file they put on
their device; nothing is redistributed.

A font file is hostile input: it was chosen out of a file manager, and
the parser is reached from a callback with nowhere to put an exception.
`SfntFont` never throws and returns null instead, and it clamps a
variable `wght` axis to `1..1000` because Readium's `setFontWeight`
asserts on the range and would take the reader's book down, not just
their font. `Typeface.Builder` is run at import as well, because
plausible sfnt tables are not the same thing as a face this device can
render, and the failure would otherwise surface inside the dropdown's
Compose preview.

*Where:* `data/settings/ReaderPrefs.kt`,
`data/settings/UserFontRepository.kt`,
`data/settings/fonts/{SfntFont,FontNames,UserFont}.kt`,
`reader/UserFontResources.kt`, `reader/ReaderPreferencesMapper.kt`,
`reader/ReaderViewModel.kt`, `ui/reading/ReadingAppearanceControls.kt`.
