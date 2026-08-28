# 15. A Custom server: OPDS, KOReader sync, or one of the two

Status: accepted

## Context

[Issue #102](https://github.com/chmouel/liseur/issues/102). Liseur
speaks to four servers, and each one is a client written against a
particular product: calibre-web's OPDS routes and Kobo endpoints, Komga's
REST API, liseur-sync's op log, Grimmory through the Komga shim
(ADR-0012). A reader whose books live on anything else — Calibre's own
content server, COPS, Kavita, a static feed on a NAS — has no way in.

OPDS is the standard those servers all speak, and Liseur has parsed it
since the beginning: `data/calibre/OpdsParser.kt`. But it was never a
connection type. calibre-web's client jumps to a known books path and
reads the feed there; nothing in the app can be handed a server root and
asked to find the library.

ADR-0014 added a KOReader sync pairing for Grimmory, which catalogs
books without carrying a position in them. The same gap is wider for a
plain catalog: a static OPDS feed carries no position either, and there
is no obvious server to pair with.

## Decision

**One kind, two addresses, either of which may be blank.** A Custom
connection holds an OPDS catalog address and a KOReader sync address.
Filling in both is the ordinary case; a catalog with no sync and a sync
server with no catalog are both real, and both are things a reader has.

Two kinds — "OPDS" and "kosync" — was the alternative. It was rejected
because one server is connected at a time: a reader with a catalog and a
sync server would have had to choose, and the two halves are not
alternatives to each other.

**Catalog absence is a column, not an inference.** `remote_server` gains
a nullable `catalog_url`. It is the only address any catalog path reads.
Null means this connection catalogs nothing, and every catalog path
answers "nothing to do" rather than reporting a failure.

Modelling it as `baseUrl` plus `canDownload = false` was tried on paper
and does not work: `RemoteCatalogRepository.refresh()` reads
`server.credentials` and treats null as a lost account, then asks the
router for a client and hands it `baseUrl` — so a sync-only connection
would either report a broken account or try to parse a kosync endpoint
as an Atom feed.

For every other kind `catalog_url` equals `base_url`, and the
migration backfills it for every row already on a phone. Left null, the
column would tell every existing calibre-web, Komga, Grimmory and
liseur-sync account that it has no catalog, and those libraries would
stop refreshing the moment the reader updated.

**A book's identity carries the catalog that issued it.** OPDS entry ids
are opaque and unique only within their own feed: `1` is legal, and two
unrelated servers can both use it. A downloaded book keeps its URL
across an account switch, so `custom:{entry-id}` would let a second
Custom server adopt the first one's rows — the wrong file, the wrong
metadata, somebody else's reading history behind it.

So the URL is `custom:{fingerprint}:{hashed-entry-id}`, the fingerprint
being six bytes of SHA-256 over the canonical catalog origin and path.
This is schema: it is written into `books.url`, which every reading
position, highlight and session hangs off.

The entry id is hashed rather than carried through, because it also
becomes `books.remote_uuid`, and `BookDownloadRepository.fileFor()`
writes that straight into a filename. An id is an arbitrary string the
server picks: one containing `/` is a download that fails, and
`../../databases/liseur` is a write outside the books directory. A
sixteen-byte digest is fixed-length, safe as a filename, and the same
one on every refresh, which is the whole of what the identity has to do.

**The catalog's password goes to the catalog's origin and nowhere
else.** A feed is a document written by someone else, and every link in
it — the next page, a shelf, a cover, the book itself — is an address
the server chose. OPDS is federated: pointing at another host is a
feature. `RemoteHttp` signs whatever URL it is handed, so following
those links as written would post the reader's password wherever the
feed said.

The rule is by origin — scheme, host and port — and not by origin plus
path prefix, which is what the plan for this work proposed. Catalogs
routinely serve their files from a path beside the feed rather than
beneath it (`/opds` and `/get`), so a prefix rule breaks the ordinary
case in order to defend against another document on the reader's own
server. A browser scopes a Basic credential the same way.

That same origin rule decides which covers are signed. Cover URLs go
through `RemoteOrigin`, which matches by path prefix, and a path prefix
would have left every cover on an authenticated `/opds` catalog
unsigned. `RemoteOrigin.ofOrigin()` drops the path for the kinds whose
links are absolute, so the rule the catalog is fetched under is the rule
its covers are fetched under.

**The fingerprint includes the query.** `?shelf=…` and `?library=…` are
how catalogs commonly pick which books to show, so two shelves of one
server are two catalogs. Trimmed off, they would share one namespace and
each could adopt the other's books. `RemoteUrl.normaliseBase` drops a
query for every other kind, where it is noise; OPDS setup opts in.

**Redirects are walked by hand.** Checking where a redirect landed is
too late: OkHttp re-sends the `Authorization` header on a same-host hop
and has already delivered the password by the time anything can object.
`OpdsHttp` disables automatic redirects and decides per hop, before each
one is sent. An https catalog is never followed down to http, whatever
the server says — a redirect is not the reader agreeing to send their
password in the clear.

The downgrade rule is asked of the *scope*, not of the current call. An
https feed naming an absolute `http://` cover starts a fresh request
whose own start is http, so a per-call check never fires. Asking
`OpdsScope.mayFetch` covers both routes with one predicate, and it is
asked of the first URL as well as of every hop.

Setup is where a redirect costs the most. Storing where one landed is
right for a path correction and wrong across origins: the stored address
becomes the credential origin on every later refresh, so a catalog that
answers setup with a redirect elsewhere would be choosing where the
password goes from then on. The first request is safe, because the scope
refuses to sign a stranger; the second would not be. An authenticated
setup redirect that leaves the origin is refused, and the reader is one
retyped address away from the same connection. An anonymous one is
followed, because nothing is being handed out.

**A catalog on the internet does not reach into the house.** A feed can
name `192.168.1.1`, `169.254.169.254` or `printer.local` and have the
phone go and fetch it, which is a reachability probe from outside that
the reader never asked for and cannot see. Refused — but only from a
public catalog. Self-hosting is the ordinary case here and lives at
`192.168.…`, and a catalog already inside the house gains nothing by
naming its neighbour, so a private root may fetch privately. Judged from
the address as written, so a public name resolving to a private address
is not caught; closing that needs the resolved socket rather than the
URL.

An address wearing a disguise is read through it. `::ffff:c0a8:0101` and
`::ffff:192.168.1.1` are both 192.168.1.1, and matched against IPv6
prefixes neither looks like anything private, so the literal is expanded
and an IPv4-mapped or IPv4-compatible one is judged as the IPv4 address
it holds.

A refused link costs that link and nothing else. Handing one to
`OpdsHttp` anyway would throw and take the whole refresh with it, so a
single federated shelf on another host would empty the reader's library.
The walk skips it and reports itself incomplete, which is what stops the
books behind it being read as deletions.

The rule is applied where a link is first written down, not only where
it is used. A cover is handed to the image loader and an acquisition to
the download worker, and neither of those consults an `OpdsScope`, so a
refused link stored today is a request made tomorrow. `OpdsFileSource`
asks again on the way out, for the rows written before the rule existed.

The same reasoning governs the sync half. `x-auth-key` is compared as
given, so anyone who reads one off the wire can replay it for good: it
is the password in every sense that matters, and unlike registration it
goes out on every call. Plain HTTP to a public sync server is refused;
plain HTTP across a network the reader controls is their call to make,
and refusing it would break most of the sync servers actually in use.

**Links are resolved against the document they were written in.**
`RemoteUrl.resolve()` throws an absolute href's host away and rewrites
it onto the configured base. That is right for a reverse-proxied
calibre-web and catastrophic for an arbitrary catalog: it would silently
retarget a CDN acquisition link at the OPDS host. The walker resolves
against the URL that answered, honouring `xml:base`, and stores absolute
URLs. `ServerKind.linksAreAbsolute` is where that difference lives.

**The walk is bounded, and says when a bound stopped it.** A catalog
root may hold books, or shelves, or shelves of shelves, so the walk has
to find the books — and stop. Three bounds: a visited set, a depth limit
of four, and a total request budget of four hundred. Depth alone does
not bound breadth; an author index is one level deep and ten thousand
feeds across.

When a bound stops the walk it reports `CatalogWalk(complete = false)`,
which is what stops the library treating a walk cut short as proof that
everything it did not reach has been deleted.

**Only formats Readium can open are offered.** An entry commonly
advertises EPUB, PDF, CBZ, an audiobook and a DRM-protected variant side
by side. The client picks a DRM-free EPUB; an entry with none is listed
without a download rather than dropped, because dropping it leaves the
reader hunting for a book the server does show. `buy`, `borrow`,
`sample` and `subscribe` are acquisitions in the specification's sense
and files the reader does not have, so they are not downloads.

**A blank credential is `Anonymous`, not absent.** Plain OPDS is often
unauthenticated. A null `credentials` already means "the stored secret
cannot be decrypted, this account is lost", so an open catalog spelled
that way would be reported as broken for ever.

**Both halves are proved before either is written.** Two addresses mean
two servers that can refuse independently. Both are probed first, and
publication — retiring the old account, storing the server, adopting or
dropping the pairing — happens in one transaction. Half a connection is
not what the reader filled in, and a process death between the two
writes would leave a catalog with last week's pairing attached.

**The form is authoritative on every successful Custom connection.** A
filled sync address replaces any existing pairing; an empty one removes
it. Without that, choosing a catalog-only Custom after Grimmory would
leave Grimmory's pairing running against a field the reader
deliberately left blank.

**A sync-only connection speaks about the books on the device.** The
kosync run starts from `bookDao.allRemote()`, whose query is
`WHERE remote_uuid IS NOT NULL`, so changing a predicate could not have
done this: a sideloaded book is gone before any predicate sees it. A
connection with a catalog keeps the bounded query; one without reads the
locally openable books instead. One function decides which, so the scan
and the push cannot drift.

## Consequences

The OPDS parser is shared now. A change made for a generic server can
break calibre-web browsing, which has no server in CI; the calibre
parser tests are the only guard, and they must keep passing untouched.

`custom:` and the fingerprint are schema. Neither can change without
orphaning every position and highlight hanging off them.

Server-side search is not implemented. OPDS search is an OpenSearch
description document advertised by the feed, not a path that can be
guessed, and every server fills it in differently. A Custom catalog is
searched through the books already listed locally.

Real servers disagree about profiles, relative hrefs and paging, so the
walk will meet catalogs it reads only partly. The bounds matter more
than the coverage: a walk that stops early is a smaller library, and a
walk that does not stop is a refresh that never ends.

Nothing stops a future kind from having both a native sync and a
pairing. `ServerKind.hostsKosyncPeer` is where that answer lives.
