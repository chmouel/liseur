# 43. OpenAI-compatible read-aloud, and a Read aloud screen of its own

Status: accepted

Keeping this provider in the `play` flavor is superseded by
[ADR-0044](0044-read-aloud-in-the-f-droid-build.md), which also renames
it "Speech server (OpenAI-compatible)".

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
and sets it up. It is reached from a row on the main Settings list,
just under Reading & navigation, so it can be found without opening
Advanced. The row says which service and voice are in use, or that none
is set up.

The service is asked for `response_format: "wav"` and the reply is
converted to 24 kHz mono 16-bit PCM, the same as Gemini's. Raw `pcm`
was tried first, but it carries no rate: DeepInfra's Audio8 answers it
at 44.1 kHz, which then played slowed down, and MiMo VoiceDesign
answers it empty. The WAV header says the rate and channels, and a
reply without one is still taken as raw 24 kHz PCM, since some models
(DeepInfra's Higgs) send that whatever is asked. The engine, the
sentence cache and the `AudioTrack` output are shared unchanged; a
session is built with a `SpeechSynthesizer` for the chosen provider and
nothing else differs. MP3 or Opus would have needed a decoder for no
gain on a local network, so WAV stays the first ask. OpenRouter,
though, refuses WAV and offers only MP3 or a PCM whose rate it
documents for one family of models, so a refusal that names
`response_format` is asked again for MP3, decoded by the platform's
own decoder (no new dependency), and that model asks MP3 first from
then on. Mistral answers WAV inside JSON, base64 under `audio_data`;
replies are therefore decoded by what they are, not by what was asked.

The reader enters one address, an optional API key, a model and a voice
for every book. The address is read as an OpenAI-style API root: when
its path has no `v1` segment, `/v1` is added, and `models`,
`audio/speech` and `audio/voices` go after it. A Kokoro server's own
address works as typed, and so does a hosted root such as
`https://api.openai.com/v1` or `https://api.example.com/v1/openai`.

Once the address is saved, and again when the key changes, the screen
asks for `models` and `audio/voices`. Models come back in a menu; voices
show as chips grouped by language, with a name and gender read from
Kokoro's ids (`af_bella` is Bella, a woman's voice, American English).
Ids that follow no such pattern, such as OpenAI's `alloy`, are shown as
they are, without a language. Tapping a voice picks it and plays a
short fixed sentence in its language, so voices can be compared without
opening a book. Both fields also take a typed name, since not every
service lists everything; a typed voice is played the same way, which
shows at once whether the server has it. A Kokoro server lists more than
fifty voices, so "Choose voices" lets the reader tick the few worth
offering; ticking none, or all, offers every one, and the voice in use
always stays offered. When the list says what its models do, the menu
keeps the speech models: DeepInfra tags them `tts` and shows each one's
price per million characters; Groq and OpenRouter give their output
(OpenRouter lists them only when asked for `output_modalities=speech`,
and its per-character price is shown too); Mistral gives an
`audio_speech` capability. Names alone missed models such as
Chatterbox. A list that says none of this keeps ids that look like speech models (`tts`,
`speech` or `kokoro` in the name) and shows the whole list when none
does; OpenAI's list is mostly chat models that cannot answer
`audio/speech`. Voices belong to a model, so they are listed again when
the model changes, and a saved voice the new model does not list is
replaced by that model's default. Mistral's voice list comes ten at a
time, so it is read to the total it states; a list that stops short is
an error rather than a partial list, since a partial list would replace
a saved voice. A service with no voice list (404 or
405) offers OpenAI's standard voices only when it is OpenAI, and
otherwise the voices its model list names for the model (OpenRouter's
`supported_voices`). DeepInfra
has no voice list on its OpenAI root, but each model's public
description at `https://api.deepinfra.com/models/<model>` names its
preset voices, and every one of them was checked to speak; the app asks
for it without the key. Any other service, or a model with no presets,
leaves the voice to be typed, since a list of voices the model refuses
is worse than none. A service with no model list
leaves the field to be typed. When nothing is chosen yet, the first
model and first voice are taken, so a new setup reads without more taps.
There is no hidden default model: reading needs an address, a model and
a voice. "Test connection" fetches both lists again and asks for one
spoken word with the chosen model and voice, so a refused key, a model
the server does not have, or an unknown voice shows up in settings
rather than after pressing play. The player has the same voices in a
menu; a pick there, or in settings, applies to the book being read from
the start of the current sentence.

The address field also offers a short list of services by name
(OpenAI, DeepInfra, OpenRouter, Mistral, Groq) whose OpenAI-compatible
root was each checked to answer, plus an example address for a Kokoro
server on the local network. Picking a hosted one saves its address and
moves to the key field; picking the example fills the field with its
host selected, to be typed over. They only fill in an address: nothing
else about the service is special-cased by the list, so a service that
is not on it works the same way when typed.

The key, the address and a typed model are saved when the field loses
focus, as well as on Done, and when the screen closes with text still in
them: pasting a key and tapping the next field kept nothing before. The
saves run in the service's own scope so they finish after the screen is
gone; key saves and removals run in the order they were made.

The key is sent as a bearer token when set. One key is kept per server,
by scheme, host and port, each stored like the Gemini key in its own
encrypted file under `noBackupFilesDir` (named by a hash of the server).
Every request reads the key of the server it is about to reach, so
switching from one service to another never sends the first one's key
to the second; a key typed while one server was shown is saved for that
server even if the address changes before it is saved. Removing a key
removes the shown server's only. The single key kept before this, whose
server is unknown, is deleted unused, so it is pasted again.

A new address is remembered as unsettled (locally, not in the settings
backup) until its own lists have chosen a model and voice. Until then its
lists replace the old server's model, even when the key is pasted only
after the first lists failed for lack of it, and even after the screen or
the app was closed. A model choice is saved only if the address and the
model choice are still the ones it was made for, so a slow reply from
the old server never writes onto the new one. A model the reader picks or
types for the new server settles it too, once that model's voices are
asked for. The address and model fields keep what the reader is typing
when a save lands meanwhile; they follow the saved value only while
they still show it.

Groq lists its speech models with `output_modalities`, bills them per
character with a `pricing.prompt` and no completion price (shown only for
Groq's host, since elsewhere a missing completion price may hide token
billing), and has no voice list. Its documented voices for each Orpheus
model, every one checked to speak, are offered for Groq's host only.
Orpheus reads text in square brackets as a vocal direction, so a book's
`[...]` may be acted rather than read. Until a model's terms are accepted
in its console, Groq answers every request with `model_terms_required`;
that stops the session with a notice saying so, and the settings screen
says the same. The address,
model, voice and offered voices are app settings: in the settings backup, but not in
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
