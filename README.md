# Liseur

<p align="center">
  <a href="https://github.com/chmouel/liseur/releases/latest">
    <img src="https://img.shields.io/github/v/release/chmouel/liseur?style=flat-square&color=10b981" alt="Latest Release">
  </a>
  <img src="https://img.shields.io/github/downloads/chmouel/liseur/total?style=flat-square&color=3b82f6&logo=github" alt="Total Downloads">
  <a href="https://github.com/chmouel/liseur/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/chmouel/liseur?style=flat-square&color=3b82f6" alt="License">
  </a>
</p>


<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/banner-dark.png">
    <img src="docs/banner-light.png" alt="A woman reading on a couch under a lamp" width="640">
  </picture>
</p>

An open-source ebook reader for Android, and a client for [calibre-web](https://github.com/janeczku/calibre-web), [Komga](https://komga.org), [BookOrbit](https://bookorbit.app), [liseur-sync](https://github.com/chmouel/liseur-sync) and any [OPDS](https://specs.opds.io/opds-1.2) catalog: EPUBs on your phone, in sync with your own book server.

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

<sub><a href="docs/SCREENSHOTS.md">More screenshots</a> here</sub>

## About Liseur

Liseur renders EPUBs with the Readium engine, in open typefaces (Literata,
Vollkorn, Atkinson Hyperlegible, Inter) or whatever the publisher shipped.
Four reading themes: Light, Sepia, Dark, OLED Black. Margins, line spacing,
brightness, and page-turn vs. continuous-scroll are adjustable per book or
library-wide. Auto-scroll runs at a set pace and continues across chapter
boundaries.

A footer shows the page you are on, how much of the chapter is left, and
time remaining. Highlights, margin notes,
bookmarks, and dictionary lookups are inline; book-level notes live in the
notebook. Marking a passage offers three colours, and you tick which of
the six you want along with the colour new marks use. Footnotes open as a
card over the page rather than sending you to the back of the book.

The library is one shelf whatever the source: local folders, calibre-web,
Komga, BookOrbit, liseur-sync, or any OPDS catalog. Series are grouped into stacks tracking
reading order, progress, and missing volumes. "Download all books" fills the
shelf from a connected server in one go. An empty library also offers a
shelf of free books from [Project Gutenberg](https://www.gutenberg.org) —
pick a category and how many books you want, and they are there before
there is anything to connect to. Once that shelf is populated, you can
ask for 50 more books at a time from the same category and language.

Reading position syncs across devices through [calibre-web](https://github.com/janeczku/calibre-web), [Komga](https://komga.org/) or
[liseur-sync](https://github.com/chmouel/liseur-sync), down to the exact sentence on the last two.
[BookOrbit](https://bookorbit.app) is connected for browsing and downloads today;
its exact positions wait on an EPUB CFI bridge, and the app says so in the picker.
A Custom connection can be an [OPDS](https://en.wikipedia.org/wiki/Open_Publication_Distribution_System) address, a kosync address, or both, so a plain catalog and a sync server that know nothing about each other still add up to a library that follows you. What each server can and cannot do is in [`docs/SERVER_CAPABILITIES.md`](docs/SERVER_CAPABILITIES.md).

With liseur-sync you get extras, like settings sync, and better insights of your reading progress across devices.

No trackers, no analytics, no ads, no subscriptions. Liseur only talks to the
servers and dictionary sources you configure. See the
[privacy policy](https://chmouel.github.io/liseur/PRIVACY).

## Install

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=com.chmouel.liseur">
    <img alt="Get it on Google Play" src="https://appure.io/badges/playstore/en.svg" height="80" />
  </a>
  <a href="https://f-droid.org/en/packages/com.chmouel.liseur/">
    <img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="80" />
  </a>
</p>
<p align="center">
  <a href="https://github.com/chmouel/liseur/releases">
    <img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png"
         alt="Get it on GitHub" height="80" />
  </a>
  <a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/chmouel/liseur">
    <img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png"
         alt="Get it on Obtainium" height="80" />
  </a>
</p>

## Related Projects

- [liseur-sync](https://github.com/chmouel/liseur-sync): Lightweight self-hosted library and sync server. Watched folders, browse, download, positions and reading stats. This will give you the best "kindle/just work" experience compared to the other sync servers.
- ~[liseur-desktop](https://github.com/chmouel/liseur-desktop): Desktop version of Liseur (abandoned).~

## Development

See [DEVELOPER.md](DEVELOPER.md) for build instructions and architecture notes,
and [`docs/SERVER_CAPABILITIES.md`](docs/SERVER_CAPABILITIES.md) for what each
server exposes, what Liseur implements against it, and why the gaps remain.

## What's in a name?

**Liseur** ([li.zœʁ], *lee-ZUR*) is the French word for an avid reader or book lover. It sounds dignified, like the English word *leisure*.

Another inspiration was a painting by [Pierre-Auguste Renoir](https://en.wikipedia.org/wiki/Pierre-Auguste_Renoir) portraying [Claude Monet](https://en.wikipedia.org/wiki/Claude_Monet) as "Le Liseur":

<p align="center">
  <picture>
    <img width="30%" height="30%" alt="image" align="center" src="https://github.com/user-attachments/assets/3a412231-9d5d-4131-8afd-b1a1f6da2a90" />
  </picture>
</p>

## Author

[![Sponsor](https://img.shields.io/badge/Sponsor-❤️-ff69b4?style=for-the-badge&logo=github)](https://github.com/sponsors/chmouel)

### Chmouel Boudjnah

- Fediverse - <[@chmouel@chmouel.com](https://fosstodon.org/@chmouel)>
- Twitter - <[@chmouel](https://twitter.com/chmouel)>
- Blog  - <[https://blog.chmouel.com](https://blog.chmouel.com)>

## Licence

[MIT](LICENSE). Bundled fonts are under the SIL Open Font License.
