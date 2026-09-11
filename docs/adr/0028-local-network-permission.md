# 28. Local network permission

Status: accepted

## Context

A reader on Android 17 could not reach their calibre-web server on their
own LAN. Connecting timed out; the same server over the internet was
fine (#195).

Android 17 makes Local Network Protections mandatory for apps targeting
SDK 37, which Liseur does. An app holding only `INTERNET` has its
traffic to and from local network addresses dropped inside the
networking stack, below OkHttp. A TCP connection to a LAN address does
not get refused — it hangs until the timeout. Apps targeting 36 or below
keep an implicit grant, so nothing changed for anyone on an older
phone, and every Android 17 device running 0.15.0 had this.

The permission is `android.permission.ACCESS_LOCAL_NETWORK`, in the
`NEARBY_DEVICES` group — hence the reporter's guess that Liseur needed
"Nearby devices", which is the group label the system prompt shows.

## Decision

Declare the permission, and ask for it only of a reader whose server is
actually on their own network.

### Asked conditionally

Liseur's readers self-host, and a good number chose this app for the
reasons that make an unexplained "allow Liseur to find devices on your
network" prompt unwelcome. An address on the open internet will never
need it, and a reader who only ever reaches their library that way is
never asked.

### Asked before the connection, not after its timeout

A reader who waits fifteen seconds for a timeout and is *then* asked a
question has already been told the app is broken. Every connect path
checks its addresses first and parks.

Four addresses can each be the local one, and they are asked about
separately: the catalog address, a Custom connection's second (kosync)
address, a kosync partner paired on its own, and — for an account
already connected — whichever of those is stored. A public catalog with
a sync server in the spare room is an ordinary setup here, and so is
its opposite.

### Judged by two halves, neither of which is `PrivateAddress`

`PrivateAddress` answers a narrower question: whether plain HTTP is
defensible, whether a catalog link is a probe nobody asked for.
Android's idea of a local address is wider, and it is not knowable from
a table — it is the directly connected routes of the networks the phone
is on, and it excludes what a VPN carries.

So `LocalNetworkAddress` has a fixed half — everything `PrivateAddress`
knows, plus IPv4 broadcast and IPv4 and IPv6 multicast — and a live
half, a route predicate that `AndroidLocalNetworkAccess` fills in from
`ConnectivityManager`. `PrivateAddress` is reused rather than copied, so
the IPv6 parsing has one home, and it gained a `matchesHost` for the
purpose.

Both halves earn their place. A home server on a global IPv6 address in
the phone's own /64 is on-link and blocked, and no list of private
ranges will ever say so — that is the reported bug with a different
address in it. And carrier-grade NAT space, `100.64.0.0/10`, is
deliberately *not* in the fixed set: that is where a Tailscale address
lives, that traffic goes down the tunnel and needs no permission, so a
table entry would prompt a tailnet reader for nothing. An ISP that hands
`100.64` out on the LAN still gets the prompt, through the route, which
is the honest reason.

VPN transports are left out of the live half because Android's
restriction does not reach what a tunnel carries. Routes are read across
every non-VPN network rather than the default one alone, so a Wi-Fi
library stays reachable while cellular is default.

### Two inaccuracies, both accepted, both erring the same way

A VPN carrying a private range can raise a prompt Android would not have
needed; and the routes are read across every network rather than the one
a socket will actually leave by. Deciding either correctly means
resolving the route a connection will take, which is a routing engine
this app has no business containing. Asking a question that turns out to
have been unnecessary costs one dialog, on a screen the reader opened to
connect a server. Not asking costs the fifteen seconds of silence that
opened #195.

### The address that will be dialled, not the text that was typed

The form takes `books.lan:8083` without a scheme, and every setup client
normalises before dialling. Feeding raw form text to `toHttpUrlOrNull()`
returns null, the gate says "not local", and the reader gets the same
timeout they reported. So the host comes out of
`RemoteUrl.normaliseBase`, which is safe for all four addresses: the
normalisers disagree about paths and defaults, never about the host.

An IPv6 literal has to be bracketed, because that is what
`normaliseBase` and OkHttp both require. A bare one is not an address
this app takes anywhere; making it one is a separate change.

### A name is resolved, but `.local` never is

A literal answers on its own. So does a `.local` or `.internal` name —
and it must, because resolving a `.local` name is mDNS, which is itself
blocked without the permission, so asking that way could only ever
answer no. Everything else is a DNS lookup, which the restriction
exempts, and every address that comes back is judged: one local answer
among several is enough. A name that resolves to nothing is not local;
it is about to fail as an unknown host anyway.

This is a preflight, and OkHttp resolves again when it dials. A rotating
record, split-horizon DNS, or outright rebinding can put a different
address on the wire than the one judged here. That time-of-check /
time-of-use gap is accepted rather than closed by sharing a resolver
with the HTTP client: the cost of being wrong is one timeout and a
notice saying what to do about it, on a screen the reader is already
looking at.

### A denial is re-askable

Android 17 ships a reset counter precisely so an app can ask again after
explaining itself, and a reader who dismissed the prompt by reflex and
then pressed Connect again means to be asked. So the first Connect
always launches the prompt, and only afterwards does
`shouldShowRequestPermissionRationale` decide what to offer — it answers
false in two opposite situations, before anything has been asked and
after a denial that will not be asked about again, so reading it first
would send a reader who has simply never been asked off to the settings
app. The app-settings intent is reserved for the denial that has become
permanent, and for a grant revoked later.

The parked request carries an immutable snapshot of what was submitted,
and is marked as launched *before* the dialog goes up and spent *before*
the answer is acted on. A rotation while the system dialog is on screen
therefore cannot raise a second one, and a duplicate callback cannot
connect twice. Resuming re-reads nothing from the form, which is live
behind the dialog.

### The notice on a connected account follows the stored account

An account already connected gets a notice rather than a prompt out of
nowhere, and the question behind it — has this account anywhere it can
no longer reach — is answered from the stored row every time that row
changes, not once when the screen resumes. Room delivers the account a
moment after the screen is built, so a check that only ran on resume
would look before it landed, find nothing stored, and leave a reader
who upgraded to Android 17 with the timeout and nothing to press. Each
run replaces the one before it, so a slow lookup cannot land on top of
a newer answer.

Which addresses count is the same question the background guard asks,
and it gets the same answer: both of the server's, because a Custom
connection catalogs at one and syncs at another, and the kosync
partner's only when the connected server is one the pairing belongs to.
A pairing stranded by an account switch is kept on screen so that it can
be disconnected, and nothing will ever dial it; asking for a permission
on its behalf would be a prompt for a machine this account does not talk
to.

### Refused per partner in the background

A worker cannot put a prompt in front of anybody, and a revoked
permission would leave position sync timing out on every scheduled run,
backing off, and trying again until the reader happened to open
Settings. `SyncFailure.LocalNetworkBlocked` is not worth retrying, on
the rule `Unauthorised` already lives by.

But it is refused per partner, not per run. `PositionSyncCoordinator`
sees one `PositionSync` and must go on seeing one; underneath,
`CompositePositionSync` holds a catalog peer and a kosync peer, and a
public catalog paired with a LAN sync server has one blocked partner and
one healthy one. Each peer is wrapped separately where they are
assembled, so the refusal carries its own `peerId` into `SyncReporting`
and `fold()` — which already turns one peer's failure beside another's
success into `Partial` — needed no change.

The decorator reports its own status, because the concrete syncs do and
a decorator that only returned a failure would leave the status line
saying Idle while nothing synced. `previewBook` returns a failure and
writes nothing: one reader asking one book a question must not overwrite
what the account's last real run did. And it asks whether the peer had
anything to do at all before it asks about the permission, so a
stored-but-idle LAN address cannot manufacture a failure for a run that
was never going to touch it.

That question is put to the peer rather than answered beside it.
`PeerPositionSync.dialledAddress()` is the address a run started now
would dial, and null is the whole of "nothing to do": no server
connected, a kind that does not sync positions, a pairing stranded on an
account that no longer uses it, a credential this Keystore can no longer
open. Each peer answers from the state its own run opens with —
`KosyncPositionSync` from the very `account()` its run calls first — so
the two cannot drift. Written as a lambda at the assembly point instead,
it drifted immediately: a kosync pairing left behind by an account
switch has a row and an address, its run answers "not applicable", and
the guard would have failed every scheduled run on behalf of a partner
the connected account does not use. Judging it there would also have
meant a `when (kind)` over which credential each kind of catalog needs,
at exactly the kind of call site the router exists to keep them out of.

Downloads and uploads are left alone. They fail in front of a reader who
can be told, and the notice on the connected card is what explains them.

### `NEARBY_WIFI_DEVICES` is not declared

That spelling matters only to Android 16's adb-enabled preview of the
same restriction, and it would put a scarier line on the F-Droid and
Play listings for a case nobody reaches by accident.

## Consequences

- A new `uses-permission` line appears on the F-Droid and Play listings.
  It is legitimate and the reproducible build is unaffected: no
  dependency, no build setting.
- The permission does not widen what the app may reach. `PrivateAddress`
  still governs cleartext, and `OpdsScope` still refuses a catalog's
  unsolicited private link: granting this does not make a feed's link to
  `192.168.1.1` followable.
- Nothing about what is stored changes, so the backup and data
  extraction rules are untouched. Play's data-safety declarations are
  edited in the console and are worth revisiting.
- Readers on Android 16 and below see no difference at all: every
  check answers "not blocked" below SDK 37.
