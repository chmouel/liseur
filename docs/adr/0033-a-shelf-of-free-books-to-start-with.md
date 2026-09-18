# 33. A shelf of free books to start with

Status: accepted

## Context

Liseur's first screen offers three ways to fill an empty library: watch
a folder of EPUBs, add one file, or connect a book server. All three
assume the reader already has something. Somebody who installed the app
to find out what it is has no folder of EPUBs, no server, and — after a
minute of tapping around — still nothing to open. The app cannot
demonstrate the thing it is for.

A reading app's first minute is the whole of its argument, and ours was
an empty grid.

## Decision

A fourth route on the empty-library screen: a card that asks which free
books and how many, and then connects Project Gutenberg and starts
shelving them.

It is not a new kind of connection. Custom (OPDS) already works
(ADR-0015), Gutenberg publishes a plain OPDS catalog, and the card
makes exactly the connection the reader could have made by hand —
anonymously, over HTTPS, to an address held in
`data/opds/StarterCatalog.kt`. Nothing downstream knows this catalog is
special.

### Gutenberg, and not Standard Ebooks

Standard Ebooks produces far better editions of much the same books, and
would have been the nicer shelf. Its OPDS feed answers `401`: it is
behind the Patrons Circle, and an onboarding route that begins by asking
for an account is not an onboarding route. Gutenberg asks for nothing,
gives everything away, and has been doing it since 1971.

### The downloads feed, and not the root

The default address is:

    https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads

not the root at `/ebooks.opds/`. Both are the same seventy-odd thousand
books, and neither can be mirrored: the catalog walk spends one request
per book, because Gutenberg lists every book as a navigation entry
pointing at a one-book feed.

What differs is which books come first. The root's slice is whatever
happens to come up; this one is the most downloaded books in the
archive, which is a genuinely good starter shelf.

### Curated bookshelves, and not subject searches

A reader who wants science fiction rather than the archive's greatest
hits picks a category, and each category is one more address. Two ways
of naming one were available.

`search.opds/?query=s.<subject>&sort_order=downloads` needs no table of
ids, works for any word, and is sorted the same way the default feed is.
Its `s.` prefix is a substring match over subject strings, though, so
the words that read best are the ones that go wrong: `s.philosophy`
leads with *The Picture of Dorian Gray* and `s.history` with *The Blue
Castle*. A first minute that offers Philosophy and shelves a novel is
worse than no category at all.

`ebooks/bookshelf/<id>.opds` is curated by hand at Gutenberg, and the
feeds are visibly better — Science Fiction leads with science fiction.
The cost is that the ids are opaque numbers with no OPDS index to
discover them from (`bookshelves.opds` answers `403`), so
`StarterCatalog.Category` hardcodes a table scraped from Gutenberg's
HTML index, each entry fetched by hand before it shipped. A shelf that
is reorganised away leaves an empty category, which is a legible failure
and the price of curation.

**A category is a different account.** `OpdsScope.fingerprint` takes in
the path and the query, so each category's books live in their own
`books.url` namespace and switching would retire the previous
category's undownloaded rows. What contains that is where the choice is
made: the card is only drawn on an empty library with no server, so it
can only ever make the first choice. Changing afterwards goes through
the address editor on the Book server screen, which already says what
disconnecting costs.

### Language-aware shelves and English fallback

When connecting the starter catalog, Liseur queries Project Gutenberg
for books in the reader's active language using `query=l.<lang>` (or
`query=s.<subject>+l.<lang>` for categories). If the requested language
collection yields no matching books, it transparently falls back to the
curated English shelf and displays a notice.

The reader's own language is the answer, not the only one. The sheet
asks it first, above the shelves, because language narrows everything
under it: a shelf picked before a language is a shelf picked in the
dark. The menu opens on the phone's language where Gutenberg has a
collection for it and on English where it does not — so a reader whose
phone agrees with them says nothing, and one learning German or reading
in a second language says so once.

It is a menu rather than a third row of chips because fifteen
categories and four sizes are already as tall as that sheet should get,
and it opens short: the reader's language and English, with the rest
behind one more tap. On an English phone, or one set to a language
Gutenberg has no collection for, that short list is English alone. The
names in it come from the platform's
own locale data rather than from `strings.xml`: seventeen languages
across six translations is a hundred strings Android already knows, and
it spells each the way the reading locale spells it.

Choosing English deliberately is not a fallback and does not raise the
notice. Only a language collection that came back with no books does.

One shelf exists in English and nowhere else. "Best books ever" is a
list somebody at Gutenberg wrote down, and unlike the genre shelves it
has no subject to search a language by. Asked for in French it would
have to fall back to something, and the something was the language's
most-downloaded feed — the identical address "Most popular" already
builds, which put two chips on one account with no way to tell from the
shelf which had been tapped. So it keeps its own English address, and
the sheet stops offering it once another language is chosen. That is
also why language is asked first: the shelves below it are the shelves
that exist in the language above.

### As many books as the reader asks for, up to two hundred

The walk would read about 380 books before its 400-request budget ran
out. That is four hundred requests to somebody else's free server, a
wait of minutes, and more books than anybody gets through in a month.
So the shelf is capped — but at a number the reader picks, because
neither the person who wants a taste nor the person filling a phone for
a flight is wrong.

`StarterCatalog.SIZES` offers 25, 50, 100 and 200, and
`OpdsCatalogClient` stops walking once it has handed over that many.
Two hundred is the ceiling because a book costs one request plus one
per page of twenty-five, so two hundred books is about 208 requests and
the next step up would not fit. Fifty is the default: enough that the
shelf looks like a library rather than a sample, few enough that the
walk finishes while the reader is still watching it.

The size is **stored on the connection**, in `remote_server.shelf_limit`,
not decided afresh from the address. The walk re-runs on every refresh,
including from a background worker that never saw the card, and a size
that lived only in the tap would be forgotten by the second refresh and
the shelf would grow back to the default.

It is deliberately **not part of `RemoteServer.accountKey`**. Changing
fifty to a hundred is not a change of account, and a key that moved
would strand every peer-keyed row and stop the downloads running
against the old one.

The stored column is the **only** record that a shelf has a size.
Nothing works one out from an address. That is tempting — the card
connects a known list of Gutenberg feeds, so matching against them looks
like a free answer — but an address is not ownership. A reader is free
to type Gutenberg's most-downloaded feed into the Book server form
themselves, and reading that as a shelf Liseur offered would cap their
catalog at fifty books. Worse, it would cap it while claiming the shelf
was *whole*, so the second refresh would reconcile everything past the
fiftieth book as gone. A connection with no stored size is walked in
full, like any other catalog.

Nothing is backfilled, not even the address this card connects. The
card arrives in the same version as the column, so no database that
predates it was ever made by one: a Custom catalog on the old schema was
typed into the Book server form by hand and has never been capped.
Giving it a number now would cap somebody's own catalog, and then, on
the refresh after that, delete everything past the cap.

A shelf with fewer books in it than the number asked for is not a
failure and needs no special case: Mystery Fiction holds thirteen, the
walk runs out of pages, ends naturally, reports itself complete, and the
catalog is correctly marked current. The awkward version of that is a
shelf which fills on the very last book there is, where counting says
"full" and the catalog says "that was all". The walk asks whether the
shelf is full only after it has taken in the page's links, so those are
two separate facts; treating them as one put a Partial notice on a
shelf that no refresh could ever clear.

A capped walk reports itself incomplete, like any other walk that
stopped short, so `CatalogStatus.Partial` shows and the catalog is
never marked current.

It is nonetheless a complete answer about the shelf, and says so with
`CatalogWalk.shelfWasFilled`. The shelf's size is this app's rule, not
the server's, so everything the shelf is meant to hold was seen, and a
book absent from it has dropped off rather than gone unasked-about.
Without that distinction the shelf grows without end as Gutenberg's
popularity ordering shifts: each refresh adds whatever has risen into
the shelf and lets go of nothing, which is how a thirty-book shelf
became seven hundred and sixty-six rows in testing. Reconciling applies
the ordinary two-strikes rule, so one refresh only suspects a book and
a downloaded one keeps its file.

`shelfWasFilled` is false whenever anything else went short first — a
refused link, a feed nested past the depth limit, the request budget.
Then what was not seen is more than the tail, and nothing may be let
go.

The trailing slash is load-bearing, and the two routes disagree about
it. Gutenberg answers `403` to the slash-less spelling of
`search.opds/`, which is what issue #219 was about and why
`RemoteUrl.normaliseBase` learned `keepTrailingSlash`; it answers `403`
to `bookshelf/68.opds` **with** a slash, which is the same rule in
reverse. Nothing has to choose between them, because `OpdsSetupClient`
tries the address as it was written before it tries the other spelling.
`StarterCatalogTest` pins every address to the spelling that answers.

### Offered only while nothing is connected

One server is connected at a time. Taking up this offer with a
calibre-web account already connected would replace it, and
`RemoteAccountRepository.disconnect()` deletes the remote books that
have not been downloaded. That is not a consequence to put behind a
single tap on a card that reads like an invitation, so the card is not
drawn when `LibraryUiState.hasServer` is true, and `connectStarterCatalog()`
refuses as well — a tap can outlive the state that allowed it.

A reader who tries Gutenberg and later connects their own server keeps
whatever they downloaded: `disconnect()` unlinks downloaded books rather
than deleting them.

### A sheet, because there are now two things to choose

The first version of this card connected on tap, and that was right when
there was nothing to decide: no account, no password, no address, no
cost beyond the catalog walk, and a sheet explaining a free catalog of
public-domain books before letting anyone see one would have been a
worse first minute than the empty grid.

A category and a size are two decisions, and they cannot be made by
tapping a card. So the tap opens `StarterCatalogSheet`: a row of
category chips with Popular selected, a row of size chips with 50
selected, and a button that connects. Both defaults are good answers, so
the sheet can still be dismissed by opening it and pressing the button —
one extra tap over the original, in exchange for the shelf being the
reader's rather than ours.

The button spins for the second or two the probe takes, and then the
sheet closes: once there is a server the shelf's own refresh indicator
takes over and books land as the walk reads pages. A failure goes to the
library's snackbar, because a sheet that silently closed would read as a
tap that missed.

## One publication, one entry

Gutenberg publishes every book as *two* OPDS entries in the same
one-book feed, identical in title, author and cover, differing only in
which files they offer:

    urn:gutenberg:1342:2  →  1342.epub.noimages   (558 KB)
    urn:gutenberg:1342:3  →  1342.epub3.images    (24.8 MB)

Both carry an EPUB acquisition link, so both read as books, and a
Gutenberg shelf held every title twice. Shipping a one-tap button at
that catalog without fixing this would be shipping a doubled shelf.

`OpdsParser` now collapses them, per feed. Three parts of that rule are
deliberate:

**The key is the work, the title, the author and the same cover file.**
Two entries name the same work when their ids differ only in a trailing
number after a colon, and the part in front is itself a named number —
which is how a URN spells "this work, in this form". That last
condition is what keeps `urn:isbn:0451450523` out: its number is the
whole identifier, `urn:isbn` is not a work, and without it two ISBNs
would read as two forms of one book. That is what makes the test safe:
title, author and cover alone
would collapse a whole shelf of a catalog that hands every book one
placeholder cover and no author, and ids of `book-1` and `book-2` name
two works however much else their entries share. An entry with no cover
is never a duplicate of anything, and an entry's own `xml:base` is part
of the key too, since the same relative href under two bases is two
different files.

**The lowest entry id survives, and the choice must never move.**
`books.url` is derived from the entry id. A rule that picked differently
on the next refresh would delete the book and add it back under another
name, taking its reading position, its highlights and its sessions with
it. An id is the entry's own and travels with it; its place in the feed
is not, because OPDS promises no order and a catalog may list the pair
the other way round tomorrow. Nothing that varies at all — a clock, a
locale, the iteration order of a set — may be consulted here. Which of
the pair survives matters much less than that the answer is stable,
though for Gutenberg the lowest id is the no-images edition, which is a
small bonus on a phone.

**Within one feed and no further.** Recognising the halves of a pair
across pages would mean carrying every publication the walk has ever
seen, which is unbounded on a catalog this size. No catalog splits a
pair that way: the point of the second entry is to sit beside the first.

## Consequences

- The app can be used within a minute of installing, by somebody who
  arrived with nothing, and the shelf they arrive at is the one they
  asked for.
- Every Gutenberg refresh shows the partial-catalog notice, unless the
  category ran out of books first, since a capped walk is a walk that
  stopped short. That is correct: there is far more in the archive. It
  can be put away, and stays away for that catalog, because a notice
  raised on every refresh of a shelf that is deliberately capped is a
  permanent band across the library.
- A generic rule now runs over every OPDS feed for the sake of one
  catalog's habit. It fires only on entries whose ids name the same work
  and which agree on a cover file as well as a title and an author, and
  `OpdsDuplicateEntryTest` pins the shelves that must not collapse.
- A bigger shelf reaches parts of Gutenberg a thirty-book one never
  did, and found a walk bug waiting there: its author feeds end with an
  entry pointing at the author's Wikipedia page, marked as navigation.
  Liseur followed it, was refused, and lost the whole refresh. A link
  that declares itself a web page is now neither a shelf nor a book, and
  a feed that cannot be read costs its own page rather than the refresh.
- `StarterCatalog.Category` holds the only addresses in the app nobody
  types, so nothing in front of a reader would report one going wrong.
  `StarterCatalogTest` holds them to the properties Gutenberg cares
  about: the spelling of the trailing slash, HTTPS, a scope that admits
  the one-book feeds the walk descends into, and a fingerprint no other
  category shares.

## Still not done

Browsing a catalog live instead of mirroring it into the database, and
honouring OPDS search. Both are what a catalog of seventy thousand books
actually wants; `CatalogStatus.Partial` only makes the limit legible.
