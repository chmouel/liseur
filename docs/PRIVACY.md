---
title: Liseur Privacy Policy
---

# Privacy Policy

**App:** Liseur (`com.chmouel.liseur`)
**Developer:** Chmouel Boudjnah
**Last updated:** 15 October 2026

Liseur is an open-source ebook reader. It has no account, no advertising,
no analytics and no trackers of its own, and the developer collects
nothing about you. The one exception is Google's ML Kit in the Google
Play build, used by a translator, which reports diagnostics to Google
once you choose it; it is described below. Its
source code is public at
<https://github.com/chmouel/liseur>, so every claim on this page can be
checked rather than taken on trust.

## What Liseur collects

The developer receives nothing. There is no server run by the
developer, no crash reporting, no advertising identifier, and no account
to sign up for, and Liseur's own code measures no usage. No data leaves
your device except to the servers described below, which are ones you
choose and enter yourself. The one exception to "no usage measurement"
is in the Google Play build: if you choose Google's on-phone translator,
Google's SDK reports its own usage to Google, as described under
"Google on this phone" below.

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
- The server or Gemini API keys you paste on the External services and API keys page, for
  reading aloud or translation, encrypted the same way. A server key is
  kept for the server you pasted it for, and is sent only to that
  server.
- The translated sentences of every page you translate in the reader,
  so a page you read again does not have to be translated again. They are
  kept for the book they came from, at most 50,000 of them, the least
  recently read going first. Deleting the book deletes them, and Settings,
  Translation has a button to clear them all. They are not backed up.
  A passage translated in the sheet is not saved.
- Which books you left translated, and between which two languages, so
  they open translated. Tapping Stop or deleting the book removes it.
- If you turn on background chapter recaps for the reading companion, the
  short recaps your chosen server wrote of the chapters you finished,
  and the chapters still waiting for one. Recaps written before you
  switched to another server or model are kept and used by
  the new one on the phone; nothing more is sent. They are kept for the book
  they came from, the oldest going first past a fixed limit. Deleting
  the book deletes them, and turning recaps off stops the work and drops
  the waiting chapters. They are not automatically backed up or synced.
  Completed recaps, and the summaries of the story so far written from
  them, are included in ZIP backups you export from Settings. Character
  profiles themselves are not saved.
- Which books you turned background recaps on or off for from the
  reader's menu, overriding the setting. Deleting the book removes it.
  It is not backed up and not synced.

Uninstalling the app removes all of it.

Liseur reads EPUB files from a folder you pick through Android's own
document picker. It only ever sees the folder you granted, and it does
not copy your books anywhere.

## Network access

Liseur talks only to addresses you choose. Every build knows the kinds
below; the Google Play build adds Google Gemini and Google's on-phone
translator, each used only if you choose it.

### Your book server

If you connect a book server, Liseur talks to the calibre-web, Komga or
liseur-sync server whose address you entered. It sends the credentials that
server asked for, downloads the books and covers you request, and exchanges
your reading position so the same book resumes in the right place on another
device. A liseur-sync server also keeps the sentence translations of the
pages you have translated, so another device reading the same book does not
have to call a translation service again. What travels is each sentence's
one-way hash — the same key the local cache uses — and its translation,
under the account you signed in with. The original sentence is not sent,
but the translation says what it means, and a hash is for matching, not
for secrecy: an operator who knows the book can test whether a given
sentence was translated. You can clear them from the account for one
book, from the reader's menu or when removing or finishing it, or for
every book from Settings, Translation. You operate that server, or
someone you trust does. This policy does not
cover what the server does with the data it receives.

### A dictionary site, if you enable it

Looking a word up online is off by default. When you switch it on, Liseur
sends the selected word to the dictionary site configured in Settings,
by default the public Wiktionary API, over HTTPS. For plural or conjugated
forms, it may also look up base words explicitly linked by that dictionary,
on the same site. It follows these links one step only, with at most four
additional requests. When the book declares a language other than Liseur's,
the alternative language for translations is Liseur's language unless you
choose another one or turn it off in Settings. With an alternative language,
each lookup also fetches that word's full entry page from the
same site, plus its translations page when the entry keeps them there, with
at most two additional requests. Opening the translations, or tapping a
translated word, looks that word up on the public Wiktionary of the
language, such as fr.wiktionary.org. This only happens when your
dictionary site is itself a Wikimedia Wiktionary. No identifier, book title
or account accompanies it. You may point this at any Wiktionary edition or
mirror, or leave the feature off and hand words to an offline dictionary app
installed on your device instead. When you pick or type a dictionary site in
Settings, Liseur checks it once with a fixed word ("book"). A dead address
fails then, and opening the screen makes no request.

### Reading a translated book aloud

Reading aloud a book you are reading translated in the page reads the
translation. Each sentence goes first to the translation service you
chose, as page translation sends it, with the book's text before it, a few
sentences ahead of the voice; then its translation goes to the speech
service you chose for reading aloud, as described below for that
service, in place of the book's words. This carries on with the screen
off or the book closed, for as long as the voice plays, and stops when
you stop the voice or the translation. Nothing else goes with either
request.

### Device voices, for reading aloud

Reading aloud with Device voices uses the text-to-speech engine already
on your device, and only its voices that work offline. The text being
read goes to that engine and nowhere else; Liseur sends nothing over
the network for it. The engine is an app on your device with its own
terms, chosen in Android's settings.

### A speech server, for reading aloud

Liseur can read aloud with any speech server that speaks OpenAI's API:
a server of your own running a model such as Kokoro, OpenAI itself, or
another hosted service. Nothing is sent until you add that server in Settings, External services and API keys,
typing its address or picking it from the services listed on that field
(picking one only fills in its address), and choose it in Settings, Read
aloud. Liseur then
asks it for its lists of models and voices, with your API key if you entered one, when you save the address
or the key and when you open that screen. When the address is
DeepInfra's, `https://api.deepinfra.com/v1/openai`, it also asks
`https://api.deepinfra.com/models/` followed by the model's name for
that model's voices, without your key or any text. Tapping a voice there, or
typing one, sends a fixed sample sentence so you can hear it; it is not
text from a book. "Test connection" on the External services and API keys page asks only for
the list of models; on the Read aloud screen it asks for the same lists and sends
the single word "Hello." with the model and voice you picked. Once you press play it sends
the text being read, one sentence at a time by default or up to five
together if you raise that in the read-aloud settings, a few sentences
ahead of the voice, to that address together with the model and voice you picked
and, if you entered one for that server, your API key. No book title, file, identifier
or reading position goes with it. Who else can see
that text depends on the server and the network you chose; a plain
`http://` address is not encrypted. With a hosted service, the text goes
to that service under its own terms and privacy policy. "Get a key" on
the API key row opens that service's key page in your browser (Liseur
sends nothing there), and the paste button inside the field reads your
clipboard once when tapped.
Saving a key checks it against that server's models endpoint, sending
the key without any book text.

### This phone's translator, for translation

Translating a passage with This phone uses the translator built into
Android 12 and later. The passage goes to that system service and
nowhere else; Liseur sends nothing over the network for it. Android may
download language packs for it, which you manage in the system's
settings.

### A server, for translation

Liseur can translate a selected passage with any server on the External
services and API keys page that speaks OpenAI's chat API, such as
OpenRouter, Together AI (`https://api.together.ai/v1`), or a server of
your own (LM Studio or Ollama, for example).
Nothing is sent until you choose that server in Settings, Translation.
Liseur then asks it for its list of models, with your API key if you
entered one for that server, so you can pick one. "Translate a test
sentence" sends one fixed English sentence, not text from a book. When
you tap Translate on a passage, it sends that passage, up to 2,000
characters, with its language and the language you want, the model you
picked and, if you entered one, your API key. If you set any of the
server's Advanced options in Settings, Translation, such as extra
instructions, they go with every request to that server. No book title,
file, identifier or reading position goes with it. Who else can see that
text depends on the server and the network you chose; with a hosted
service, the passage goes to that service under its own terms and
privacy policy. When you translate a page in the reader, each sentence
goes the same way, with some of the book's text before it from the same
chapter, as set by "Earlier text for page translation" in Settings,
Translation: by default up to 8 sentences and 4,000 characters, and
some of it may come from before the place you started translating. Set
it to Off, or turn off the server's Advanced option to send earlier
text, and only the sentence goes. TranslateGemma models never get it.
[Local AI servers](LOCAL_AI_SERVERS.md) explains how to
run a translation server on your own computer.

### A server, for the reading companion

Selecting a name in the reader and tapping Ask companion asks a server on the
External services and API keys page that speaks OpenAI's chat API who
that character is, from the book up to your selection.
Nothing is sent until you choose that server and a model in Settings,
Intelligence, Reading companion, and Liseur then asks it for its list of models the same way
as for translation. A lookup sends the name you selected, how many
chapters the book has and how many come before yours, and passages from
the earlier chapters and from your chapter up to the paragraph you
selected in: the ones that mention the name with the paragraphs around
them, then the ones the model asks to search or read, up to 64,000
characters of book text in all. Nothing from the paragraph you selected
in or anything after it is sent. The model, and your API key if you entered one, go
with every request. No book title, file, identifier or precise reading
position goes with it, and the recaps
described below are only used on the phone to find passages; a lookup
never sends their wording.

If you also turn on background chapter recaps, then while the book is
open on screen and you read on from the end of one chapter into the
next, Liseur sends the whole chapter you just finished to the same
server, in parts of about 12,000 characters, and keeps the short recap
it writes back. Chapters you skip, or that you read before turning
recaps on, are not sent. The reader's Aa > Advanced sheet turns recaps on or off
for the book you are reading, whatever the setting says. Requests to a hosted service may be charged
to your account there. A model may also know a well-known book from
its training and let something from later in it slip into its answer;
Liseur asks it not to, and shows next to every statement the passage
from the book it relied on, but it cannot guarantee what a model
writes.

Codex's Story tab, shown once a companion server is chosen, lists the
chapters before yours with their recaps. Recapping a chapter from there,
with or without background recaps on, sends that whole chapter the same
way. The first time it opens it asks before doing anything on its own;
once you allow it, each time you open it at a new place it recaps the
chapter right before your place if it has no recap, and, once at least
two chapters have one, writes a summary of the
story so far by sending the recaps' summary sentences with their chapter
numbers, and no book text. Not now, Stop, and the switch in the
companion settings stop this; the buttons on the tab still work.

### Google Gemini, for reading aloud (Google Play build only)

The Google Play build can read a book aloud with a voice made by Google's
Gemini service. The F-Droid and GitHub builds do not contain this voice
at all.

It does nothing until you choose Gemini in Settings, Read aloud, and
paste a Gemini API key of your own from Google AI Studio. Once you press play, Liseur sends the text being
read, one sentence or short passage at a time (up to five sentences
together if you raise that in the read-aloud settings) and a few
sentences ahead
of the voice, to `generativelanguage.googleapis.com` over HTTPS,
together with your key and the voice you picked. Nothing else goes with
it: no book title, no file, no identifier, no reading position. Requests
are billed to your key under Google's terms.

"Get a key" next to the key field opens Google AI Studio's key page in
your browser; Liseur itself sends nothing there. "Paste" reads your
clipboard once, when you tap it, and saves what it holds as the key.
Under [Google's terms](https://ai.google.dev/gemini-api/terms), unpaid
requests may be used to improve its products and reviewed by people.
Requests through a project with active billing are not used for product
improvement. The paid-service data terms also apply to unpaid use in the
European Economic Area, Switzerland, and the United Kingdom.
Saving a Gemini key checks it against Google's models endpoint, sending
the key without any book text.

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

### Google Gemini, for translation (Google Play build only)

The Google Play build can also translate a selected passage with Gemini,
using the same key as reading aloud. It does nothing until you choose
Gemini in Settings, Translation. Opening the model list on that screen
asks `generativelanguage.googleapis.com` which text models the key can
use, carrying only your key. "Translate a test sentence" sends one fixed
English sentence. When you tap Translate on a passage, Liseur sends that
passage, up to 2,000 characters, with its language and the language you
want, to the same address over HTTPS, together with your key and the
model you picked. No book title, file, identifier or reading position
goes with it. A page translated in the reader sends each sentence with
some of the book's text before it, as described for a server above.
Requests are billed to your key under Google's terms, and
Google's retention policy for the Gemini API applies to them. A
passage's translation is shown on screen and is never written to
storage; sentences translated in the page are saved on your device as
described above, whichever service translated them.

### Google on this phone, for translation (Google Play build only)

The Google Play build can translate with Google's ML Kit translator,
which runs on your device. The F-Droid and GitHub builds do not contain
it. None of it runs until you choose "Google on this phone" in Settings,
Translation, and agree to the notice shown at that point.

The passages and pages you translate with it stay on your device; it
does not send book text, titles or reading positions anywhere. It needs
a language model for each language, about 30 MB each, which it downloads
from Google only after you agree, and only over Wi-Fi. You can delete
those models in Settings, Translation. English is built in.

Google's SDK sends Google diagnostic and usage data about itself,
starting when you choose this translator. Android may upload events it
has already recorded after you switch to another translator, and the
identifier it created stays on the device until you clear the app's
storage or uninstall it. Per Google's
[ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure),
that covers the device model and Android version, the app's name and
version, an identifier generated for this installation, timings, sizes
of the text in and out, the kinds of events (such as a translation or a
model download) and error codes. It goes to
`firebaselogging.googleapis.com` over HTTPS; the first model download
also creates that identifier with Firebase and fetches the SDK's
settings from Google. Google says it does not share this data with
third parties. It is covered by the
[ML Kit terms](https://developers.google.com/ml-kit/terms) and Google's
privacy policy, which this policy does not cover.

### Google Gemini, for the reading companion (Google Play build only)

The Google Play build can also run the reading companion on Gemini,
using the same key as reading aloud. It does nothing until you choose
Gemini as the companion's service in Settings, Intelligence, Reading
companion. Opening the model list on that screen asks
`generativelanguage.googleapis.com` which text models the key can use,
carrying only your key. From then on, lookups, chapter recaps and
the story so far send exactly what is described above for a server, the same
passages from the book up to your selection, the same whole chapters and the
same recap sentences, to that
address over HTTPS, together with your key and the model you picked.
Requests are billed to your key under Google's terms, and Google's
retention policy for the Gemini API applies to them.

Liseur never contacts any other host. It requests the `INTERNET` and
`ACCESS_NETWORK_STATE` permissions for the purposes above and for nothing
else.

## Manual ZIP backups

Backups exported from Settings include reading data, companion recaps, fonts,
settings and service addresses. They exclude passwords, tokens and API keys by
default. If you check “Include accounts, services and passwords”, Liseur also
includes your library account, KOReader pairing, service API keys and, in the
Google Play build, your Gemini key. These credentials are encrypted with a
backup password you choose, using AES-256-GCM and PBKDF2-HMAC-SHA256 with a
random salt and 600,000 iterations. Only this connection payload is encrypted;
the other ZIP contents remain readable. Keep the password to restore the
credentials; Liseur does not save or send it anywhere.

Restoring such a backup replaces saved library and KOReader accounts, service
configuration and credentials without contacting the servers to sign in.
Credentials are encrypted again with the new device’s Keystore. Sync cursors,
acknowledgements and queued work are not transferred. Backups without
credentials leave existing accounts and API keys alone.

## Android backup

Liseur takes part in Android's standard backup, so your library, reading
positions, highlights, notes and settings can follow you to a new device.
That backup is handled by Android and stored in your own Google account,
under Google's terms, not the developer's. Liseur has no access to it.
Downloaded book files, generated covers, saved translations, chapter
recaps and, in the
Google Play build, ML Kit's language models and installation identifier
are deliberately excluded.
Server credentials are included but arrive unreadable on a new device,
because the key that encrypts them never leaves the old one. Liseur notices
this and asks you to sign in again. A server or Gemini API key
is not backed up at all; you paste it again on a new device. The servers'
addresses, and the models, voices and languages you chose for reading
aloud, translation and character profiles, are backed up with your
other settings.

You can turn this off in your device's backup settings.

## Children

Liseur is not directed at children. The developer collects no personal
information from anyone, of any age. The Google Play build's optional
Google translator sends Google the diagnostic data described above.

## Sharing

Nothing is shared or sold. The developer receives no data from the app
whatsoever. In the Google Play build, if you choose Google's on-phone
translator, Google receives the diagnostic data described above under
its own terms.

## Changes

Any change to this policy will be published on this page with a new date
above, and its history is visible in the repository.

## Contact

Questions, or a problem with this policy: open an issue at
<https://github.com/chmouel/liseur/issues> or write to
<chmouel@chmouel.com>.
