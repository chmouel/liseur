# Liseur

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/banner-dark.png">
    <img src="docs/banner-light.png" alt="A woman reading on a couch under a lamp" width="640">
  </picture>
</p>

An open-source ebook reader for Android and a [calibre-web](https://github.com/janeczku/calibre-web) / [Komga](https://komga.org) / [liseur-sync](https://github.com/chmouel/liseur-sync) client: EPUBs on your phone, in sync with your own book server, for people whose idea of a great evening is a warm blanket and a 900-page book.

<table>
  <tr>
    <td width="25%"><img src="docs/screenshots/01-library.png" alt="Library"></td>
    <td width="25%"><img src="docs/screenshots/02-reading.png" alt="Reading"></td>
    <td width="25%"><img src="docs/screenshots/03-chrome.png" alt="Reading controls"></td>
    <td width="25%"><img src="docs/screenshots/04-typography.png" alt="Typography"></td>
  </tr>
  <tr>
    <td align="center"><sub>The shelf, sorted by what you are currently reading.</sub></td>
    <td align="center"><sub>Distraction-free page with notes and bookmarks.</sub></td>
    <td align="center"><sub>Reading progress, chapter scrubber, and time remaining.</sub></td>
    <td align="center"><sub>Themes, open typefaces, spacing, and brightness.</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/05-contents.png" alt="Contents"></td>
    <td><img src="docs/screenshots/06-highlights.png" alt="Highlights"></td>
    <td><img src="docs/screenshots/07-search.png" alt="Search"></td>
    <td><img src="docs/screenshots/12-dictionary.png" alt="Definition card"></td>
  </tr>
  <tr>
    <td align="center"><sub>Full-screen table of contents for easy navigation.</sub></td>
    <td align="center"><sub>A personal notebook of highlights, exportable to Markdown.</sub></td>
    <td align="center"><sub>In-book search with context snippets.</sub></td>
    <td align="center"><sub>Instant word definitions without losing your place.</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/13-series.png" alt="Series"></td>
    <td><img src="docs/screenshots/14-series-detail.png" alt="One series"></td>
    <td><img src="docs/screenshots/09-server.png" alt="Server setup"></td>
    <td><img src="docs/screenshots/11-reading-dark.png" alt="Reading in the dark theme"></td>
  </tr>
  <tr>
    <td align="center"><sub>Series grouped into compact shelf stacks.</sub></td>
    <td align="center"><sub>Reading order, progress, and missing volumes at a glance.</sub></td>
    <td align="center"><sub>Connect to calibre-web, Komga or liseur-sync in moments.</sub></td>
    <td align="center"><sub>Dark reading theme, independent of system settings.</sub></td>
  </tr>
</table>

<sub>Screenshots use <a href="https://standardebooks.org">Standard Ebooks</a> editions (public domain).</sub>

## About Liseur

[Liseur](#whats-in-a-name) is designed to get out of the way and let you read.
Pages open cleanly from edge to edge with the Readium engine, set in open
typefaces like Literata, Vollkorn, Atkinson Hyperlegible, and Inter, or
whatever quirky typography the publisher insisted on. Four dedicated reading
themes (Light, Sepia, Dark, and true OLED Black) let you settle into a story at
2 a.m. without being blinded by your phone. Margins, line spacing, brightness,
and page turns stay quietly out of your way. Prefer one long scroll to turning
pages? Switch it on for the whole library, or for the one book that wants it,
and keep scrolling past the end of a chapter to fall into the next one.

A discreet footer shows the time remaining in the chapter (just enough to
convince yourself that one more chapter won't hurt), while highlights, margin
notes, bookmarks, and instant dictionary lookups remain right under your
fingers when you stumble upon a word you pretend to know. Footnotes open as a
small card over the page rather than throwing you to the back of the book, so
an author's aside costs you nothing more than a tap — and when a note is long
enough to deserve the full page, "Go to note" takes you there and leaves a way
back.

Your library gathers onto a single shelf, whether your books live in messy folders on your phone or on a self-hosted [calibre-web](https://github.com/janeczku/calibre-web), [Komga](https://komga.org) or [liseur-sync](https://github.com/chmouel/liseur-sync) server. Books that belong to a series politely group into compact stacks that track your reading order, your progress, and the missing volumes you still need to hunt down. When you reach the final page of a novel, the next volume is already sitting there, gently enabling your binge-reading habits.

When you switch between devices, you have the choice to have your place travel with you across your devices. Two-way synchronization with [calibre-web](https://github.com/janeczku/calibre-web), [Komga](https://komga.org) and [liseur-sync](https://github.com/chmouel/liseur-sync) keeps your progress aligned — down to the exact sentence on Komga and liseur-sync, so you never have to play the guessing game of where you were. liseur-sync goes one further: standalone EPUB files that never came from a catalog sync their position too, matched by the file itself. Its books come from folders it watches on the server, so anything you drop there shows up on the shelf.

Everything runs privately and without distraction. There are no trackers, no
analytics, no ads, and no attempts to sell you a monthly subscription for books
you already own. Liseur only talks to the book servers and dictionary sources
you choose to add. The [privacy policy](https://chmouel.github.io/liseur/PRIVACY)
spells out exactly what that means.

## Install

- [F-Droid](https://f-droid.org/en/packages/com.chmouel.liseur/)
- [GitHub Releases](https://github.com/chmouel/liseur/releases)

## Related Projects

- [liseur-desktop](https://github.com/chmouel/liseur-desktop): Desktop version of Liseur
- [liseur-sync](https://github.com/chmouel/liseur-sync): Lightweight self-hosted library and sync server — watched folders, browse, download, positions and reading stats

## Development

See [DEVELOPER.md](DEVELOPER.md) for build instructions and architecture notes.
What each supported server exposes, what is implemented against it, and
why the remaining gaps are or are not fixable is in
[`docs/SERVER_CAPABILITIES.md`](docs/SERVER_CAPABILITIES.md).

## What's in a name?

**Liseur** ([li.zœʁ], *lee-ZUR*) is the French word for an avid reader or book lover. It sounds dignified just like the English word *leisure*, which is exactly what you should be doing when you curl up with a good book.

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
