
# Liseur

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/banner-dark.png">
    <img src="docs/banner-light.png" alt="A woman reading on a couch under a lamp" width="640">
  </picture>
</p>

An open-source EPUB books reader and calibre-web / Komga client for Android: a
modern reader that is equally at home with the files on your phone and with a
[calibre-web](https://github.com/janeczku/calibre-web) or
[Komga](https://komga.org) server.

<table>
  <tr>
    <td width="25%"><img src="docs/screenshots/01-library.png" alt="Library"></td>
    <td width="25%"><img src="docs/screenshots/02-reading.png" alt="Reading"></td>
    <td width="25%"><img src="docs/screenshots/03-chrome.png" alt="Reading controls"></td>
    <td width="25%"><img src="docs/screenshots/04-typography.png" alt="Typography"></td>
  </tr>
  <tr>
    <td align="center"><sub>The shelf, with whatever you were last reading on top.</sub></td>
    <td align="center"><sub>A full page of text, a highlight, a bookmark ribbon.</sub></td>
    <td align="center"><sub>Tap the middle for progress, the scrubber and time left.</sub></td>
    <td align="center"><sub>Reading themes, fonts, size, spacing, brightness.</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/05-contents.png" alt="Contents"></td>
    <td><img src="docs/screenshots/06-highlights.png" alt="Highlights"></td>
    <td><img src="docs/screenshots/07-search.png" alt="Search"></td>
    <td><img src="docs/screenshots/12-dictionary.png" alt="Definition card"></td>
  </tr>
  <tr>
    <td align="center"><sub>Contents on a full screen, so long books stay navigable.</sub></td>
    <td align="center"><sub>Everything you marked up, exportable as Markdown.</sub></td>
    <td align="center"><sub>Search the whole book, with a snippet around each hit.</sub></td>
    <td align="center"><sub>Hold a word for a definition, without leaving the page.</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/13-series.png" alt="Series"></td>
    <td><img src="docs/screenshots/14-series-detail.png" alt="One series"></td>
    <td><img src="docs/screenshots/09-server.png" alt="Server setup"></td>
    <td><img src="docs/screenshots/11-reading-dark.png" alt="Reading in the dark theme"></td>
  </tr>
  <tr>
    <td align="center"><sub>Each series on the shelf as one stack, however many books it holds.</sub></td>
    <td align="center"><sub>The series in order, how far you are into it, and the volumes you are missing.</sub></td>
    <td align="center"><sub>calibre-web or Komga: an address and a way in. Liseur does the rest.</sub></td>
    <td align="center"><sub>The Dark page theme, chosen separately from the app's.</sub></td>
  </tr>
</table>

<sub>Screenshots use <a href="https://standardebooks.org">Standard Ebooks</a>
editions, which are in the public domain.</sub>

## What it does

Read an EPUB rendered full screen. Tap the
right of the page to go forward, the left to go back, the middle to bring up
the chrome; volume keys work too if you would rather not touch the screen.

Four reading themes (Light, Sepia, Dark, Black) that are
independent of the app's own theme, four bundled open fonts (Literata,
Vollkorn, Atkinson Hyperlegible, Inter) or the publisher's own, plus size, line
spacing, margins and an in-app brightness slider.

A footer that shows time left in the chapter, time left in the
book, page, or percentage; tap it to cycle. A scrubber with chapter ticks, and
a pill that takes you back if you jumped somewhere by accident.

Highlights in four colours, notes, bookmarks with a Kindle-style
corner ribbon, and a notebook of everything you have marked in a book,
exportable as Markdown.

Search the whole book with snippets and jump-to
highlighting, and a definition card on any selected word. The card asks
Wiktionary, which means leaving the device, so it stays off until you turn
it on and you can point it at whichever Wiktionary edition or mirror you
prefer. The hand-off to an offline dictionary app needs no network and
works either way.

Point `Liseur` at folders of EPUBs and it indexes them, covers and
all. Books you are actually using sort to the front, finished ones get a tick,
and pull to refresh picks up whatever changed behind the app's back.

Books that belong to a series say so. calibre-web, Komga and any EPUB
exported from calibre all carry the series and the volume number, and Liseur
reads them from whichever of the three a book came from. The title on the
shelf gains a quiet `Sherlock Holmes · #2`, searching for a series name finds
every volume of it, and a Series view stacks each one into a single card. Open
the stack and you get the series in order, where you are in it, which volumes
you are missing between the ones you have, and a button to carry on with the
next. Finish a book and the one after it is offered on the last page.

Where nobody said, you can say. Long-press a book and you can put it into a
series — one you already have or a new name — give it a volume number, or take
it out of a series it never belonged in. Your answer outranks the server's and
survives every refresh, and there is a way back to whatever the file or the
catalog originally claimed.

Two books make a series. calibre gives most standalones a series of their own,
so a book alone in one is shown as a book, with no card and no volume number;
file a second beside it and the series appears.

Sometimes the numbers are wrong: a folder of EPUBs that carries none, or a
translation a publisher numbered to suit itself. Open the series and put it in
order, by the drag handle or the arrows, and Done renumbers it 1, 2, 3 the way
you left it. Everything that asks what to read next reads that order, so
Continue and the offer on the last page follow it too.

Renumbering is all it does, so a `#1.5` becomes a 2 and the series stops
counting the volumes it thinks are missing between the ones you have. Clear
the custom numbers and the server's and the files' numbering comes back,
hand-typed ones included. Nothing is sent to your server; the order lives on
the device.

Pick [calibre-web](https://github.com/janeczku/calibre-web) or [Komga](https://komga.org/), connect to it and Liseur works
out the rest: the catalog, whether the account may download, and how to sync. 
Your server's books merge into the same library with a cloud badge; tap one and
it downloads and opens.

Reading positions sync both ways, through calibre-web's Kobo protocol or
Komga's own read-progress API. Komga carries a full locator rather than a
percentage, so a book reopens on the exact word you left it on, whichever
device you pick it up on. If both sides moved since they last agreed, Liseur
says so instead of silently picking one.

You can also connect a [liseur-sync](https://github.com/chmouel/liseur-sync)
server, alongside your catalog server or instead of one. It holds no books,
only places: it syncs the exact spot, and it does it for books that came off an
SD card and have never been near a catalog at all, which nothing else here can.
Books are matched by the file's own hashes where possible, and where the match
rests on nothing but a title and an author, Liseur asks you before it syncs
anything — two translations of the same novel look identical from a title.
It also keeps your reading time, so the stats screen can show the hours you put
in on your other devices as well as this one.

Free software. No trackers or analytics, no proprietary dependencies. The only
network traffic is to the book server you configured, the sync server if you
added one, and, if you switch online definitions on, to the dictionary site you
chose.

## Install

Install from the [F-droid repository](https://f-droid.org/en/packages/com.chmouel.liseur/) or download the apk from the [Github Relesaes](https://github.com/chmouel/liseur/releases/).

## Development

***See [DEVELOPER.md](DEVELOPER.md) for how to build Liseur from source.***

## Related

* Check out [liseur-desktop](https://github.com/chmouel/liseur-desktop) for the desktop (electron based) version of liseur.

## Author
[![Sponsor](https://img.shields.io/badge/Sponsor-❤️-ff69b4?style=for-the-badge&logo=github)](https://github.com/sponsors/chmouel)
### Chmouel Boudjnah 


- Fediverse - <[@chmouel@chmouel.com](https://fosstodon.org/@chmouel)>
- Twitter - <[@chmouel](https://twitter.com/chmouel)>
- Blog  - <[https://blog.chmouel.com](https://blog.chmouel.com)>

## Licence

[MIT](LICENSE). Bundled fonts are under the SIL Open Font License.
