# 62. Gradium is a speech server, not a provider

Status: accepted

## Context

Read aloud has three providers: device voices, Gemini (Play only) and
"Speech server", one row for any server speaking OpenAI's speech API,
whose address field offers hosted services by name (OpenAI, DeepInfra,
Groq, ...). Every hosted service so far spoke that API, so a preset was
only an address and a key page.

Gradium (`api.gradium.ai`) does not. Its key goes in an `x-api-key`
header, it has no model list, its voices come from `voices/` with a name
and a language each, and speech is asked of `post/speech/tts` as a JSON
body answered with a 48 kHz WAV. A refused key can come back as a plain
text `HTTP 500` reading `error from server 1008: ...`.

The first draft gave it what Gemini has: its own provider, account, key
row and settings. That is a fourth row on the Read aloud screen, a
second key page, a second backup field and a second set of voice
preferences for what is, to the reader, one more hosted service with one
more key.

## Decision

Gradium is one expandable entry on the speech server's address field,
with three region presets (`api.gradium.ai`, `eu.api.gradium.ai`, `us.api.gradium.ai`,
each with the `/api` root), and the client speaks Gradium's protocol
when the server's host is one of those. The provider is renamed "Speech
server"; its id stays `openai`.

- Host rules live where the other host rules live (`OpenAiTts.isOpenAi`,
  `isGroq`, DeepInfra's `modelDescription`): `GradiumTts` and branches
  in `OpenAiTtsClient`. `OpenAiSpeechService`, `ServerConnections` and
  the screens do not branch on the host.
- `models` and `allModels` answer one model, `default`; `allModels`, the
  key check, lists the voices instead. `baseUrl` keeps requests under the
  `/api` root; `identityUrl` retains the earlier `/api/v1` form for saved
  server IDs and voice scopes.
- The voice list now carries a name and a language per voice
  (`SpeechVoice`); OpenAI-style servers fill the id only, and
  `VoiceLabel.of(SpeechVoice)` shows a listed voice by what its server
  said, else by what its id says, as before. The service keeps those
  labels so the picker, the chips and the player show "Emma", not a uid.
- Gradium is asked for WAV with `only_audio`, decoded by the shared
  `WavPcm` (48 kHz to 24 kHz). There is no MP3 fallback. Speed stays a
  playback setting; Gradium's `padding_bonus` is not used.
- 401, 403 and a body matching `error from server 1008` are a refused
  key; a body naming the voice is a missing voice. Bodies are read only
  to classify.

Each region is its own server with its own key file, as every origin
is. Gemini is unchanged.

## Consequences

- Keys, the server list, backup, the per-origin generation, unsettled
  servers, "Test connection", voice preferences and the voice sheet work
  for Gradium with no new code: they are keyed by server, not protocol.
- A Gradium server also appears in the translation and companion server
  pickers, where it fails like a Kokoro server does. Those pickers list
  servers, not what each can do.
- The emulator stub (`hack/ai-stub`) answers Gradium's two routes, but
  the app speaks Gradium's protocol to its real hosts only, so hearing a
  sentence on the emulator takes a real key.
- A later service with its own protocol can follow the same path, as
  long as it fits a server, a key, one or more models and a voice list.
  One that needs more than that (a region row, a second credential)
  argues for a provider after all.
