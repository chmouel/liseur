---
title: Liseur Privacy Policy
---

# Privacy Policy

**App:** Liseur (`com.chmouel.liseur`)
**Developer:** Chmouel Boudjnah
**Last updated:** 6 October 2026

Liseur is an open-source ebook reader. It has no account, no advertising,
no analytics and no trackers, and it collects nothing about you. Its
source code is public at
<https://github.com/chmouel/liseur>, so every claim on this page can be
checked rather than taken on trust.

## What Liseur collects

Nothing. There is no server run by the developer, no crash reporting, no
usage measurement, no advertising identifier, and no account to sign up
for. No data leaves your device except to the servers described below,
which are ones you choose and enter yourself.

## What Liseur stores on your device

All of this stays in the app's own private storage:

- Your library: the books you added, their metadata and their covers.
- Where you are in each book, along with your reading history and the
  time you have spent reading.
- Your highlights, notes and bookmarks.
- Your reading preferences: theme, font, size, spacing, margins.
- If you connect a book server, its address and the credentials or token
  it issued. Those are encrypted with a key held in the Android Keystore,
  which cannot be exported from the device.
- The speech server or Gemini API key you paste for reading aloud,
  encrypted the same way.

Uninstalling the app removes all of it.

Liseur reads EPUB files from a folder you pick through Android's own
document picker. It only ever sees the folder you granted, and it does
not copy your books anywhere.

## Network access

Liseur talks only to addresses you choose. Every build knows three
kinds; the Google Play build adds a fourth, used only if you set it up.

### Your book server

If you connect a book server, Liseur talks to the calibre-web, Komga or
liseur-sync server whose address you entered. It sends the credentials that
server asked for, downloads the books and covers you request, and exchanges
your reading position so the same book resumes in the right place on another
device. You operate that server, or someone you trust does. This policy does not
cover what the server does with the data it receives.

### A dictionary site, if you enable it

Looking a word up online is off by default. When you switch it on, Liseur
sends the single selected word to the dictionary site configured in Settings,
by default the public Wiktionary API, over HTTPS. No identifier, book title
or account accompanies it. You may point this at any Wiktionary edition or
mirror, or leave the feature off and hand words to an offline dictionary app
installed on your device instead. When you pick or type a dictionary site in
Settings, Liseur checks it once with a fixed word ("book"). A dead address
fails then, and opening the screen makes no request.

### Device voices, for reading aloud

Reading aloud with Device voices uses the text-to-speech engine already
on your device, and only its voices that work offline. The text being
read goes to that engine and nowhere else; Liseur sends nothing over
the network for it. The engine is an app on your device with its own
terms, chosen in Android's settings.

### A speech server, for reading aloud

Liseur can read aloud with any speech server that speaks OpenAI's API:
a server of your own running a model such as Kokoro, OpenAI itself, or
another hosted service. Nothing is sent until you choose Speech server
in Settings, Read aloud, and enter that server's address. Liseur then
asks it for its lists of models and voices, with your API key if you entered one, when you save the address
or the key and when you open that screen. When the address is
DeepInfra's, `https://api.deepinfra.com/v1/openai`, it also asks
`https://api.deepinfra.com/models/` followed by the model's name for
that model's voices, without your key or any text. Tapping a voice there, or
typing one, sends a fixed sample sentence so you can hear it; it is not
text from a book. "Test connection" asks for the same lists and sends
the single word "Hello." with the model and voice you picked. Once you press play it sends
the text being read, one sentence at a time and a few sentences ahead of
the voice, to that address together with the model and voice you picked
and, if you entered one, your API key. No book title, file, identifier
or reading position goes with it. Who else can see
that text depends on the server and the network you chose; a plain
`http://` address is not encrypted. With a hosted service, the text goes
to that service under its own terms and privacy policy.

### Google Gemini, for reading aloud (Google Play build only)

The Google Play build can read a book aloud with a voice made by Google's
Gemini service. The F-Droid and GitHub builds do not contain this voice
at all.

It does nothing until you choose Gemini in Settings, Read aloud, and
paste a Gemini API key of your own from Google AI Studio. Once you press play, Liseur sends the text being
read, one sentence or short passage at a time and a few sentences ahead
of the voice, to `generativelanguage.googleapis.com` over HTTPS,
together with your key and the voice you picked. Nothing else goes with
it: no book title, no file, no identifier, no reading position. Requests
are billed to your key under Google's terms.

Each request asks Google not to store it (`store: false`). That turns off
the optional storage of the request on Google's side; it does not change
Google's own retention policy for the Gemini API, which this policy does
not cover. The audio that comes back is kept in memory while you listen
and is never written to storage.

Pressing "Hear this voice" on that screen sends one fixed sample
sentence, not text from a book, the same way and billed the same way.

With a key saved, opening that screen also asks the same address which
speech models the key can use, so you can pick one. That request carries
only your key.

Stopping the voice stops the requests. Remove the key in Settings and no
more are made.

Liseur never contacts any other host. It requests the `INTERNET` and
`ACCESS_NETWORK_STATE` permissions for the purposes above and for nothing
else.

## Android backup

Liseur takes part in Android's standard backup, so your library, reading
positions, highlights, notes and settings can follow you to a new device.
That backup is handled by Android and stored in your own Google account,
under Google's terms, not the developer's. Liseur has no access to it.
Downloaded book files and generated covers are deliberately excluded.
Server credentials are included but arrive unreadable on a new device,
because the key that encrypts them never leaves the old one. Liseur notices
this and asks you to sign in again. A speech server or Gemini API key
is not backed up at all; you paste it again on a new device. The speech
server's address, model and voice are backed up with your other settings.

You can turn this off in your device's backup settings.

## Children

Liseur is not directed at children and collects no personal information
from anyone, of any age.

## Sharing

Nothing is shared or sold, because nothing is collected. The developer
receives no data from the app whatsoever.

## Changes

Any change to this policy will be published on this page with a new date
above, and its history is visible in the repository.

## Contact

Questions, or a problem with this policy: open an issue at
<https://github.com/chmouel/liseur/issues> or write to
<chmouel@chmouel.com>.
