# Liseur

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/banner-dark.png">
    <img src="docs/banner-light.png" alt="A woman reading on a couch under a lamp" width="640">
  </picture>
</p>

An open-source ebook reader for Android, and a client for [calibre-web](https://github.com/janeczku/calibre-web), [Komga](https://komga.org), [liseur-sync](https://github.com/chmouel/liseur-sync) and [Grimmory](https://github.com/grimmory-tools/grimmory): EPUBs on your phone, in sync with your own book server.

<table>
  <tr>
    <td width="33%"><img src="docs/screenshots/01-library.png" alt="Library"></td>
    <td width="33%"><img src="docs/screenshots/02-reading.png" alt="Reading"></td>
    <td width="33%"><img src="docs/screenshots/04-typography.png" alt="Typography"></td>
  </tr>
  <tr>
    <td align="center"><sub>The shelf, sorted by what you are currently reading.</sub></td>
    <td align="center"><sub>Distraction-free page with notes and bookmarks.</sub></td>
    <td align="center"><sub>Themes, open typefaces, spacing, and brightness.</sub></td>
  </tr>
</table>

<sub><a href="docs/SCREENSHOTS.md">More screenshots</a> — the notebook, search,
definitions, series, reading stats, the dark theme and a tablet. Captures use
<a href="https://standardebooks.org">Standard Ebooks</a> editions (public domain).</sub>

## About Liseur

Liseur renders EPUBs with the Readium engine, in open typefaces (Literata,
Vollkorn, Atkinson Hyperlegible, Inter) or whatever the publisher shipped.
Four reading themes: Light, Sepia, Dark, OLED Black. Margins, line spacing,
brightness, and page-turn vs. continuous-scroll are adjustable per book or
library-wide. Auto-scroll runs at a set pace and continues across chapter
boundaries.

A footer shows time remaining in the chapter. Highlights, margin notes,
bookmarks, and dictionary lookups are inline; book-level notes live in the
notebook. Footnotes open as a card over the page rather than sending you to
the back of the book.

The library is one shelf whatever the source: local folders, calibre-web,
Komga, liseur-sync, or Grimmory. Series are grouped into stacks tracking
reading order, progress, and missing volumes. "Download all books" fills the
shelf from a connected server in one go.

Reading position syncs across devices through calibre-web, Komga and
liseur-sync, down to the exact sentence on the last two. liseur-sync also
syncs standalone EPUBs that never came from a catalog. A KOReader sync
(kosync) server can be paired alongside any catalog — it is how Grimmory
syncs positions, and it works with any kosync-compatible server. What
each server can and cannot do is in
[`docs/SERVER_CAPABILITIES.md`](docs/SERVER_CAPABILITIES.md).

No trackers, no analytics, no ads, no subscriptions. Liseur only talks to the
servers and dictionary sources you configure. See the
[privacy policy](https://chmouel.github.io/liseur/PRIVACY).

## Install

- [F-Droid](https://f-droid.org/en/packages/com.chmouel.liseur/)
- [GitHub Releases](https://github.com/chmouel/liseur/releases)

## Related Projects

- [liseur-desktop](https://github.com/chmouel/liseur-desktop): Desktop version of Liseur
- [liseur-sync](https://github.com/chmouel/liseur-sync): Lightweight self-hosted library and sync server. Watched folders, browse, download, positions and reading stats
- [Grimmory](https://github.com/grimmory-tools/grimmory): Self-hosted library manager. Liseur browses and downloads from it

## Development

See [DEVELOPER.md](DEVELOPER.md) for build instructions and architecture notes,
and [`docs/SERVER_CAPABILITIES.md`](docs/SERVER_CAPABILITIES.md) for what each
server exposes, what Liseur implements against it, and why the gaps remain.

## What's in a name?

**Liseur** ([li.zœʁ], *lee-ZUR*) is the French word for an avid reader or book lover. It sounds dignified, like the English word *leisure*.

Here is a painting of Pierre-August Renoir portraying C.Monet as "Le liseur"

[<img width="336" height="568" alt="image" src="https://github.com/user-attachments/assets/3a412231-9d5d-4131-8afd-b1a1f6da2a90" />
](https://fr.wikipedia.org/wiki/Fichier:Pierre-Auguste_Renoir_-_Claude_Monet_(Le_Liseur).jpg)
## Author

[![Sponsor](https://img.shields.io/badge/Sponsor-❤️-ff69b4?style=for-the-badge&logo=github)](https://github.com/sponsors/chmouel)

### Chmouel Boudjnah

- Fediverse - <[@chmouel@chmouel.com](https://fosstodon.org/@chmouel)>
- Twitter - <[@chmouel](https://twitter.com/chmouel)>
- Blog  - <[https://blog.chmouel.com](https://blog.chmouel.com)>

## Licence

[MIT](LICENSE). Bundled fonts are under the SIL Open Font License.
