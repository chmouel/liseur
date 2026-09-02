# 2. Typography fine-tuning

Status: accepted
GitHub issue: [#44](https://github.com/chmouel/liseur/issues/44)

## Context

The sheet exposes font, size, line height, margins and columns. Readium
can also justify text, hyphenate it, and adjust letter, word and
paragraph spacing and font weight, and none of that is reachable. For
most readers the defaults are right; for a reader with a dyslexic
child, or one who cannot stand a ragged right edge, the missing knob is
the difference between using the app and not.

## Decision

Expose alignment, hyphenation, letter spacing, word spacing, paragraph
spacing and font weight as rows in the Advanced sheet, and on Settings ->
Reading appearance, which has no Advanced section to collapse them
behind.

Fit with Liseur's simplicity: these controls are why the Advanced sheet
exists ([ADR 1](0001-advanced-reading-menu.md)); the main typography
sheet does not change.

Every one of the six defaults to **Default**, so a reader who never
opens the sheet gets exactly the page they got before.

## What Readium actually does

Implementing this meant reading Readium's stylesheets rather than its
documentation, and most of what follows was not what the proposal
assumed.

**The bundled `readium-css` is not upstream `master`.** The copy inside
`readium-navigator-3.3.0.aar` differs from
`readium/readium-css@master`, which does not contain the word
`advanced` at all. Everything below was read out of the bundled files,
because those are what render our pages. Re-check on upgrade.

### `readium-advanced-on` is not a per-setting switch

`publisherStyles = false` becomes `--USER__advancedSettings:
readium-advanced-on`, and turning it on **unconditionally restyles
`:root`, `h1`–`h6`, `p`, `li`, `dd`, `div`, `pre`, `small`, `sub` and
`sup`**; it normalizes the whole type scale and overrides the sizes the
book's designer chose, whatever else is set. It is a large price, and it
is worth paying only for a setting the reader asked for *and* the book
can honour.

Audited property by property in the bundled default stylesheet:

| Property | Gated on `readium-advanced-on` |
| --- | --- |
| `lineHeight`, `textAlign`, `bodyHyphens`, `letterSpacing`, `wordSpacing`, `paraSpacing` | **yes** |
| `pageMargins`, `fontSize` | no; they work without it |
| `fontWeight` | no rule at all; it rides the user-properties `overrides` map |

**This is a behaviour change: page margins used to turn advanced styles
off and no longer does.** A reader who had only widened their margins
was losing their book's heading sizes for nothing. Readium's own
`EpubPreferencesEditor` corroborates it; it gates `pageMargins`' and
`fontSize`'s effectiveness on nothing but a reflowable publication,
while gating every one of the six on `!publisherStyles`.

### Justification hyphenates by itself

```css
:root[style*=readium-advanced-on][style*="--USER__textAlign: justify"] body {
  hyphens: auto;
}
```

`--USER__bodyHyphens` is `!important` and wins, so an explicit setting
wins and no setting loses: justified text hyphenates on its own, and the
way out is switching hyphenation **off**, not leaving it at Default.
`justificationHyphenates()` is that condition, and the sheet says so
rather than leaving the reader to discover it. Readium agrees:
`isHyphensEffective` includes `|| textAlign == JUSTIFY`.

### Not every stylesheet carries every rule — and not the same ones

Readium picks one of four stylesheets per publication, and the variants
do not agree:

| Stylesheet | hyphens | letter | word | `textAlign` | paragraph |
| --- | --- | --- | --- | --- | --- |
| default | yes | yes | yes | yes | yes |
| `rtl/` | **no** | **no** | **no** | **yes** | yes |
| `cjk-horizontal/`, `cjk-vertical/` | **no** | **no** | **no** | **no** | yes |

So **alignment survives into an RTL book and dies in a CJK one**. A
single "not the default stylesheet" state would tell an Arabic reader
their alignment control is dead when it works, and a Japanese reader
that it works when it does not. Hence three live states, not two.
Readium's `isTextAlignEffective` says the same thing
(`stylesheets in [Default, Rtl]`, against `== Default` for the other
three), but it is `internal`.

### `requiresAdvancedStyles` is scoped to the book's stylesheet

Which is the point of the whole classification.
`requiresAdvancedStyles(css)` counts a setting only when this book's
stylesheet has a rule for it. Without the scope, an app-wide letter
spacing would switch advanced styles on for an Arabic book whose
stylesheet has no letter-spacing rule: Readium would normalize that
book's entire type scale and apply none of the spacing that asked for
it. It would be pure loss, and it would contradict the row telling the reader the
setting does not apply here.

The values themselves are still *sent*. A setting a book cannot use is
not erased, it merely stops counting, so it is there for the next book
that can honour it.

Both narrowings (by property and by stylesheet) mean strictly *less*
interference than before.

### `readingCssFor` mirrors three pieces of `internal` Readium

`Layout.from`, `EpubSettingsResolver`, and the `isCjk`/`isRtl`
extensions are all `internal`, so the classification is a copy rather
than a call. It is derived from `publication.metadata` alone, and is
exact **only because Liseur sets no `language`, `readingProgression` or
`verticalText` preference and no `EpubDefaults`**; every branch of the
resolver that could diverge from metadata is unreachable.
`ReaderPreferencesMapperTest` pins that invariant, so the day someone
adds a reading-direction preference the build fails and points here.

Deriving it from the publication rather than from the navigator's
resolved settings is what lets the answer exist *before* the navigator
does. The mapper needs it to build the initial preferences; classifying
late would mean opening the book one way and correcting it a moment
later, causing a reflow and moving the reading position while the
reader watches.

The mirror includes Readium's bugs, deliberately, because the sheet has
to describe the page Readium will actually produce:

| Tag | Readium's answer |
| --- | --- |
| `ja`, `ko`, `zh`, `zh-Hant`, `zh-TW` | CJK |
| **`ja-JP`, `ko-KR`** | **not CJK**; `ja`/`ko` are compared against the whole code, and only `zh` is region-stripped |
| `ar`, `fa`, `he` | RTL |
| **`ar-EG`** | **not RTL**; `isRtl` compares the whole code to a fixed list |

Fix those upstream, not here. Re-check the mirror on every Readium
upgrade; `ReadingCssTest` is what will notice.

## Design

Six new nullable fields on `ReaderPrefs`, one DataStore key each,
absent when null, mapped in `toEpubPreferences`, and rendered by one
composable used by both surfaces so they cannot drift.

### "Default", not "Publisher"

`publisherStyles` is one global flag: once any advanced field carries a
value it is off for all of them and the type scale is normalized, so a
field left alone means "no override of my own", not "the publisher's
styling, restored". The rows say **Default**, matching
`reader_spacing_default` and `reader_margins_default` beside them.

### Default versus zero, and the two-way toggle

`0.0` is a value ("no extra spacing"), and it counts as an override.
But a null and an explicit `0.0` both rest the thumb at the range start,
so no drag reaches one from the other and a zero-distance press is not
guaranteed to report itself. The trailing button is therefore a two-way
toggle: from Default it commits the range start as an explicit value,
and from an explicit value it returns to Default.

### The invalid-number policy

`EpubPreferences` throws from its constructor on a negative `fontSize`,
`letterSpacing`, `wordSpacing`, `paragraphSpacing`, `pageMargins` or
`typeScale`, and on a `fontWeight` outside `0.0..2.5`; the moment
that would happen is the moment the reader is changing their settings.
`NaN >= 0` is `false` and `NaN.coerceIn(a, b)` is still `NaN`, so
anything not finite has to be refused rather than clamped.

Three categories, because "clamp" and "discard" are different answers:

- Sliders (letter, word, paragraph spacing). Not finite or negative
  -> discarded; `0.0` kept; above the maximum clamped, because Liseur's
  ranges are deliberately narrower than Readium's and a larger value is
  a real preference from a wider one.
- Segmented doubles (`lineHeight`, `pageMargins`). The reader can
  only ever write one of three offered values or nothing, so anything
  else is corruption or another build: out of range is **discarded, never
  clamped**. `lineHeight = 0.0` is below Readium's minimum of `1.0` and
  goes; `pageMargins = 0.0` is inside its range and stays.
- `fontSize`, which has no null to fall back to, falls back to `1.0`
  and clamps a finite out-of-range value.

Clamping a negative into range would turn a corrupt byte into an
explicit override, which would then switch advanced styles on and
renormalize the book. The page would be rewritten because a file was
damaged.
`requiresAdvancedStyles` is computed from sanitized values so that
cannot happen.

`lineHeight` is sanitized even though it is **not** in Readium's
`require` list: a NaN there is not a crash but a broken CSS declaration
and a broken label.

Every door a number comes through calls `sanitized()`: the preference
store on read and write, a book's own typography row (after the merge,
not before; the row is the newer and less trustworthy source), and the
mapper itself, which is reached by paths that built a `ReaderPrefs`
directly.

A stored `lineHeight` or `pageMargins` that is valid but none of the
three offered values (from an older build, or a hand-edited row)
leaves its segmented row with nothing selected, and is left intact.
Snapping it to the nearest option would change the page to a setting the
reader never chose, silently, just because they opened the sheet.

### Snapping, and where exactness lives

Spacings snap by tick index, not by decimal arithmetic:
`((value - min) / increment).roundToInt()`, then
`min + ticks * increment`. Adding an increment repeatedly walks off the
notches; multiplying a whole count does not. Snapping happens in the
repository as well as in the slider, so a caller that is not the slider
cannot persist a value between notches.

A `Double` cannot promise `0.15`, so the exactness promise lives in the
**label**, formatted through `NumberFormat` with the reader's own
decimal separator. Tests assert tick indices or use a delta, never
literal decimals.

Liseur's ranges (0–0.25, 0–0.5, 0–2.0) are narrower than Readium's
(0–1.0, 0–1.0, 0–2.0) because the top of Readium's letter and word
spacing ranges is unreadable rather than merely spacious.

### The preview

`ui/reading/ReadingPreviewValues.kt` holds the conversions as pure
functions, so a preview that quietly disagrees with the page fails a
test rather than being believed. Letter spacing is **halved**, because
Readium halves it on the way to CSS (`Length.Rem(it / 2)`), and returned
in `em` as a real `TextUnit` so it cannot be mistaken for a figure in
`sp`. The paragraph gap stays in `sp` and is converted with the current
density by the composable, so it tracks both the size slider and the
system font scale.

Not everything is previewable, and the preview does not pretend
otherwise: hyphenation needs a line break to fall in the right place and
word spacing needs more words than two paragraphs hold.

### Ineffective rows are disabled

A row whose rule is not in the book's stylesheet is disabled, showing
its stored value, under a line saying why. Annotating it instead would
let a reader set letter spacing inside an Arabic book, see nothing
change and, before the scoped predicate, have silently normalized
that book's type scale. Now neither half can happen.

The settings screen passes `ReadingCss.Unknown`, where nothing is
disabled: no book is open, the reader is choosing a default for every
book they will open, and the wording names the writing systems rather
than claiming anything about a particular book.

### App-wide, not per-book

`book_typography` keeps its four columns and needs no migration. A book
set apart keeps its own font, size, line spacing and margins; alignment,
hyphenation, weight and the three spacings stay shared, because they are
about how the reader reads rather than about the book. The two
`reader_typography_own_*` strings were rewritten to say so, since "Font,
size and layout stay with this book" fairly reads as covering the rows
just above.

## Consequences

- Two narrowings of existing behaviour, both meaning less
  interference: page margins alone no longer normalizes a book's type
  scale, and an RTL or CJK book is no longer normalized for settings its
  stylesheet cannot apply.
- Turning advanced styles on reflows the page and can move the
  reading position. Unchanged in kind, but now it happens in fewer
  cases.
- A font weight override reaches everything that inherits it,
  headings included, so a book's own emphasis flattens towards the
  chosen weight. That is the setting working as asked, and why the
  default sends nothing.
- Three mirrors of `internal` Readium code, quirks and all, standing
  on an invariant a test pins. A copy drifts; re-check on upgrade.
- Fixed-layout books honour none of the six, and the rows say so. Font
  size, margins and columns are equally inert there and are *not*
  disabled. A pre-existing inconsistency this change deliberately did
  not widen its scope to fix.

  > Amended by [ADR-0020](0020-fixed-layout-reading-settings.md),
  > proposed: it would disable every reflowable-only setting in a
  > fixed-layout book, the scrolling toggle among them, and say the line
  > once per sheet rather than once above these six. Until that ships,
  > this bullet is what the app does.
- No new dependency, and no new network, file, credential or permission
  surface. The security-adjacent question is what a malformed stored
  value can do, and the policy above is the answer.

*Where:* `data/settings/ReaderPrefs.kt`,
`data/settings/ReaderPreferencesRepository.kt`,
`data/db/BookTypography.kt`, `reader/ReaderPreferencesMapper.kt`,
`reader/ReaderViewModel.kt`, `reader/chrome/AdvancedSheet.kt`,
`ui/reading/ReadingFineTypography.kt`,
`ui/reading/ReadingPreviewValues.kt`,
`ui/settings/ReadingAppearanceScreen.kt`.
