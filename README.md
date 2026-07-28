# Liseur

A beautiful, modern ebook reader for Android. First-class local EPUB
reading and calibre-web client, with a reading experience inspired by
the best commercial readers.

**Status: early development — not yet usable.**

## Goals

- **EPUB reading** built on the [Readium Kotlin
  Toolkit](https://readium.org/kotlin-toolkit/), with careful,
  Kindle-grade reading ergonomics: tap zones, page-turn animations,
  typography controls (bundled open fonts, size, spacing, margins),
  Light/Sepia/Dark/Black reading themes, brightness gesture, and
  time-left-in-chapter estimates.
- **Local library**: index EPUBs from folders you pick, cover-first
  library, highlights, notes, bookmarks, and in-book search.
- **calibre-web client**: browse, search, and download your library
  over OPDS, and sync reading positions through calibre-web's Kobo
  sync protocol.
- **Free software**: no trackers, no analytics, no proprietary
  dependencies — built for F-Droid.

## Building

```bash
./gradlew assembleDebug
```

See [DEVELOPER.md](DEVELOPER.md) for details.

## License

[MIT](LICENSE)
