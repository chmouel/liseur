# 43. OpenAI-compatible read-aloud, and a Read aloud screen of its own

Status: accepted

## Context

Read-aloud ([0042](0042-gemini-read-aloud.md)) speaks only with Gemini,
which bills every sentence to the reader's key and sends the text to
Google. Some readers run Kokoro, an open speech model, on a machine of
their own behind an OpenAI-style API (`POST /v1/audio/speech`,
`GET /v1/audio/voices`). It costs nothing per sentence and the text stays
on their network. Others would rather pay OpenAI or another hosted
service that offers the same API. Supporting Kokoro alone, then adding
presets for hosted services one at a time, tied the app to whichever
service had a preset.

The settings for Gemini sat at the bottom of Reading & navigation,
Advanced. With a second service and its own server address, key and voice
list, that section would have become a screen within a screen.

## Decision

A second provider, "OpenAI-compatible", speaks OpenAI's speech API to
whatever address the reader enters. It lives in the same `play` flavor.
The `foss` build still has no read-aloud code at all; a self-hosted
service does not change the reasons in 0042 for keeping the feature out
of it, and one boundary is simpler to check than two.

Read aloud gets a screen of its own, where the reader picks the service
and sets it up. It is reached from one row at the end of Reading &
navigation, Advanced, where the Gemini settings were; read-aloud is an
add-on most readers never set up, and a row on the main Settings list
gave it more weight than it has. The row says which service and voice
are in use, or that none is set up. Back from the screen returns to
Advanced, still open.

The service is asked for `response_format: "pcm"`, which is 24 kHz mono
16-bit PCM, the same as Gemini's. The engine, the sentence cache and the
`AudioTrack` output are shared unchanged; a session is built with a
`SpeechSynthesizer` for the chosen provider and nothing else differs. MP3
or Opus would have needed a decoder for no gain on a local network.

The reader enters one address, an optional API key, a model and a voice
for every book. The address is read as an OpenAI-style API root: when
its path has no `v1` segment, `/v1` is added, and `models`,
`audio/speech` and `audio/voices` go after it. A Kokoro server's own
address works as typed, and so does a hosted root such as
`https://api.openai.com/v1` or `https://api.example.com/v1/openai`.

Once the address is saved, and again when the key changes, the screen
asks for `models` and `audio/voices` and offers what comes back in two
menus. Both fields also take a typed name, since not every service lists
everything. The model menu keeps ids that look like speech models
(`tts`, `speech` or `kokoro` in the name) and shows the whole list when
none does; OpenAI's list is mostly chat models that cannot answer
`audio/speech`. A service with no voice list (404 or 405, as OpenAI
answers) gets OpenAI's standard voices. A service with no model list
leaves the field to be typed. When nothing is chosen yet, the first
model and first voice are taken, so a new setup reads without more taps.
There is no hidden default model: reading needs an address, a model and
a voice.

The key is sent as a bearer token when set, and stored like the Gemini
key in its own encrypted file under `noBackupFilesDir`. The address,
model and voice are app settings: in the settings backup, but not in
liseur-sync settings sync, because a LAN address means nothing on
another device.

Notices name the server's host ("api.openai.com could not be reached")
rather than "OpenAI-compatible", which reads badly in a sentence and
does not say which server failed.

The provider runs one request at a time with a 120-second read timeout.
On a Raspberry Pi 5 a sentence takes about three times its length to
make, and parallel requests only slowed each other down. Gaps between
sentences are expected on such hardware; the app cannot close them.

A refused key or a voice the server does not have stops the session with
a notice naming the service, since retrying the same sentence cannot
succeed. Other failures pause on the sentence, as with Gemini.

## Consequences

- The speech pipeline in `app/src/play/` is named for speech, not Gemini.
- No speed, language or per-book voice settings for this provider; they
  can be added to the request later without touching the engine.
- One request at a time is slower than a hosted service needs. It is
  the safe choice for a small self-hosted server, and the provider cannot
  tell the two apart.
- Model and voice names are not checked against each other; a voice the
  model lacks stops reading on the first sentence with a notice.
- A plain `http://` address sends book text unencrypted on the reader's
  network. That is the reader's choice of server, and the privacy policy
  says so.
