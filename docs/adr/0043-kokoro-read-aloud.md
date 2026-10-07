# 43. Kokoro read-aloud, and a Read aloud screen of its own

Status: accepted

## Context

Read-aloud ([0042](0042-gemini-read-aloud.md)) speaks only with Gemini,
which bills every sentence to the reader's key and sends the text to
Google. Some readers run Kokoro, an open speech model, on a machine of
their own behind an OpenAI-style API (`POST /v1/audio/speech`,
`GET /v1/audio/voices`). It costs nothing per sentence and the text stays
on their network.

The settings for Gemini sat at the bottom of Reading & navigation,
Advanced. With a second service and its own server address, key and voice
list, that section would have become a screen within a screen.

## Decision

Kokoro is a second provider in the same `play` flavor. The `foss` build
still has no read-aloud code at all; a self-hosted service does not change
the reasons in 0042 for keeping the feature out of it, and one boundary is
simpler to check than two.

Read aloud moves to its own row on the main Settings list, next to Reading
& navigation, opening a screen where the reader picks the service and sets
it up. The row says which service and voice are in use, or that none is
set up.

Kokoro is asked for `response_format: "pcm"`, which is 24 kHz mono 16-bit
PCM, the same as Gemini's. The engine, the sentence cache and the
`AudioTrack` output are shared unchanged; a session is built with a
`SpeechSynthesizer` for the chosen provider and nothing else differs. MP3
or Opus would have needed a decoder for no gain on a local network.

The reader enters one server address and picks one voice for every book
from the list the server reports. The list is only fetched when the voice
menu is opened, never on opening the screen. An API key is optional,
sent as a bearer token when set, and stored like the Gemini key in its own
encrypted file under `noBackupFilesDir`. The address and voice are app
settings: in the settings backup, but not in liseur-sync settings sync,
because a LAN address means nothing on another device.

The address is read as an OpenAI-style API root: when its path has no
`v1` segment, `/v1` is added, and `audio/speech` and `audio/voices` go
after it. A Kokoro server's own address works as typed, and so does a
hosted service's root such as `https://api.example.com/v1/openai`.
Hosted services name the model differently, so the model is an optional
setting, `kokoro` when left empty. A service with no voice list answers
`audio/voices` with 404 or 405; the menu then says it found no voices.

Kokoro runs one request at a time with a 120-second read timeout. On a
Raspberry Pi 5 a sentence takes about three times its length to make, and
parallel requests only slowed each other down. Gaps between sentences are
expected on such hardware; the app cannot close them.

A refused key or a voice the server does not have stops the session with
a notice naming the service, since retrying the same sentence cannot
succeed. Other failures pause on the sentence, as with Gemini.

## Consequences

- The speech pipeline in `app/src/play/` is named for speech, not Gemini.
- No speed, language or per-book voice settings for Kokoro; they can be
  added to the request later without touching the engine.
- A plain `http://` address sends book text unencrypted on the reader's
  network. That is the reader's choice of server, and the privacy policy
  says so.
