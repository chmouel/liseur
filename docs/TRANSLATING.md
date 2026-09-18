# Translating Liseur

UI strings live in
[`app/src/main/res/values/strings.xml`](../app/src/main/res/values/strings.xml).
Locales ship in the tree as `app/src/main/res/values-*/strings.xml`.

French (`values-fr`), Spanish (`values-es`), Russian (`values-ru`),
Italian (`values-it`) and German (`values-de`) are maintained in the
repository. At runtime, missing keys fall back to English and the system
language picks which file Android loads. There is no in-app language
switcher yet.

Lint treats `MissingTranslation` as an error: every translatable key
in the English file must also be present in each of those locale files,
or `./gradlew lintDebug` (and CI / `make check`) fails. Do not leave an
empty `values-xx/` directory — that registers the locale with no strings
and fails the same check. Paragraph breaks inside a string must be the
literal escape `\n\n` on one line; a raw XML newline collapses to a
space at build time.

## Adding or updating a translation

1. Copy any missing keys from the English `strings.xml` into **every**
   locale file (skip entries marked `translatable="false"`).
2. Translate the text. Keep placeholders (`%1$s`, `%d`, `%d%%`) intact
   and grammatical for the target language.
3. For languages with richer plurals than English (Russian needs
   `one` / `few` / `many` / `other`), fill every quantity Android asks
   for.
4. Open a pull request that updates English and all five locales
   together.

Brand and product names (`Liseur`, `calibre-web`, `Komga`,
`liseur-sync`, font family names, and similar) stay untranslated —
they are marked `translatable="false"` in English and must not appear
in locale files.

XML comments above a string in the English file are translator context;
they do not need to be copied into locale files.

## Store listing

F-Droid / Play listing text lives under
`fastlane/metadata/android/en-US/`. Extra locales go beside it as
`fastlane/metadata/android/<locale>/` when someone translates the
title and descriptions.

## Out of scope

**Book text translation** (LibreTranslate on a selection) is a reading
feature, not UI i18n. See
[ADR 0010](adr/0010-translation-on-selection.md).
