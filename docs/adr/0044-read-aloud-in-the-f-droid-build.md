# 44. Read aloud in the F-Droid build

Status: accepted

## Context

Read aloud shipped in the Google Play build only
([0042](0042-gemini-read-aloud.md)), because its first voice was Gemini,
a non-free network service that would earn the F-Droid listing the
NonFreeNet anti-feature. The OpenAI-compatible provider
([0043](0043-openai-compatible-read-aloud.md)) stayed in the same flavor
so there was one boundary to check.

That provider has no built-in address. It reads with whatever server the
reader enters, most usefully one they host themselves, such as Kokoro.
That is the same position as the online dictionary lookup, which F-Droid
accepts: the app talks to a service the reader chose, and nothing in the
APK points at a non-free one. Keeping it out of the F-Droid build denied
F-Droid readers a feature with no policy reason behind it.

## Decision

The read-aloud engine, player, settings screen and the speech server
provider move to `main`, so both builds have them. Only Gemini stays in
`play`: its client, its voices, its settings rows and its key.

Each provider is a `SpeechService`. The flavor's `ReadAloudFeatureFactory`
passes the list it offers to `SpeechReadAloud`, and the first one is the
choice of a reader who never made one. Play offers Gemini first, so a
reader who set it up in v0.21.0 keeps it. The F-Droid build offers
device voices first, since they work with no setup, then the speech
server. The provider picker hides itself when there is only one choice.

Device voices are a third provider, in both builds: the voices of
Android's default text-to-speech engine that are installed and do not
need a network connection, so no text leaves the device. They are named
"Voice 1", "Voice 2" within each language, since engine voice names
such as `en-us-x-iob-local` mean nothing to a reader. Each sentence is
synthesized to a throwaway file and the PCM is taken from the engine's
audio callbacks, converted to 24 kHz mono 16-bit, so the cache, speed,
highlight, player and media session are the same as for the other
providers. A button opens Android's text-to-speech settings to install
more voices. This replaces the `TtsNavigator` plan of
[0003](0003-read-aloud-tts.md), which would have been a second player
with its own rules.

The provider is now called "Speech server (OpenAI-compatible)". The old
name, "OpenAI-compatible", read as if it needed an OpenAI account, and
in the F-Droid build a self-hosted server is the case worth naming.

Readium's TTS navigator and media3-session become ordinary dependencies.
Both are FOSS and on Maven Central or Google's Maven. The foreground
service and its two permissions move to the main manifest.

## Consequences

- The F-Droid APK grows by the size of the engine, Readium's TTS module
  and media3-session. It still has no Gemini code, strings or endpoint;
  checking for `generativelanguage` and `Gemini` in the release dex is the
  boundary test now, rather than the absence of `com.chmouel.liseur.tts`.
- Strings shared by both builds live in `app/src/main/res`. Gemini's live
  in `app/src/play/res`, which also overrides the provider explanation to
  mention Gemini.
- Device voices depend on the engine the device has. Engines that only
  offer network voices, or none, leave the list empty with a line saying
  so; the reader can install voices or pick another provider. The
  engine is released after a minute without a request.
- F-Droid readers can send book text to a hosted speech service if they
  enter its address. That is their choice, made the same way as with the
  dictionary site, and the privacy policy says so.
