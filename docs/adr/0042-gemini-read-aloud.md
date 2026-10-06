# 42. Gemini read-aloud, in the Play build only

Status: accepted

## Context

Readers asked for books to be read aloud. Android's own text-to-speech
voices are free and offline but sound flat over a whole chapter. Google's
Gemini speech model sounds far better, but it is a non-free network
service: the text being read leaves the device, and every request is
billed to an API key.

F-Droid flags an app that depends on a non-free network service with the
NonFreeNet anti-feature, and Liseur's F-Droid build has none today. The
feature should not cost that build its clean listing, and nobody should
send book text to Google without having chosen to.

## Decision

Read-aloud ships only in a new `play` product flavor. The `foss` flavor,
which F-Droid rebuilds and the GitHub release carries, has none of its
code, none of Readium's TTS module or media3, and no Gemini endpoint.
`main` knows the feature only through the `ReadAloudFeature` interface and
`OpenBookHandle`; the `foss` factory returns `ReadAloudFeature.None`.

Nothing is sent until the reader pastes a Gemini API key of their own in
Settings. The key is encrypted with a Keystore key in a file under
`noBackupFilesDir`, so it is in neither Android backup nor the settings
export. The voice is one of twelve curated prebuilt voices, each shown
with its style; Kore is the default.

Playback runs on Readium's `TtsNavigator` with a custom engine. The engine
sends one sentence per request to the Interactions API with
`store: false`, asks for 24 kHz 16-bit PCM, and plays it through an
`AudioTrack`. Sentences are capped at 600 characters. Three sentences
ahead are prefetched, two requests at a time, into an in-memory cache of
at most 16 MB. Nothing is written to storage.

A foreground `mediaPlayback` service owns the session, so the voice keeps
going with the screen off or the reader in the background, and the
notification and lock screen carry previous sentence, play or pause, and
next sentence.

A failed request pauses on the sentence that failed and says why, in
words: no network, quota used up, key refused, a service error, or a
device that cannot play the audio. Play retries that sentence. Nothing is
skipped.

### Where the reader is

The voice and the reader share one place. While the voice plays, it owns
the place and the page follows the sentence being spoken. Any move the
reader makes, a page turn, a scroll, a jump, pauses the voice and the
place becomes the reader's again. Auto-scroll and the voice are the same
kind of conflict: starting either pauses or disarms the other.

A heard sentence is saved the way a page turn to it would be, through the
book's `prepareLocator` and positions, so the two store the same
`total_progression`. While playing, checkpoints every 30 seconds stay
local; the checkpoint written on pause or stop is the one that syncs.
Listening is not counted as reading time.

### Measurements

The engine was measured on an emulator with a real key before the rest
was built:

- 15 minutes of continuous playback, 126 requests, 6 to 11 a minute, no
  429 and no failure. A request took 2.4 to 6 seconds for 6 to 14 seconds
  of audio.
- Gaps between sentences: p50 1 ms, p95 5 to 6 ms, maximum 20 ms. Across
  three chapter boundaries: p50 2 ms, p95 10 ms, maximum 1221 ms.
- About 1.6 MB of extra memory against a control run without audio; the
  cache peaked at 2.24 MB.
- Stop silenced the speaker within 2 ms and cancelled the request in
  flight within 3 ms. No request followed.
- With the screen off and no foreground service, the network failed after
  about 5 seconds. With the service, playback ran 90 seconds with the
  screen off without an error, so no wake lock is taken.
- The first audio arrives about 3 seconds after the tap.

## Consequences

The Play listing must declare in its data-safety form that book text is
sent to Google when the feature is used. `docs/PRIVACY.md` describes the
endpoint as Play-only and says what `store: false` does and does not do.

Some things came out differently from the plan:

- The cache is keyed by the sentence text alone. The voice is read once
  when a session starts, and a new choice applies to the next session.
- There is no speed or pitch setting in this version.
- When a sentence fails, the place to resume from is taken at the
  moment of failure. Resuming recreates the navigator there, because
  Readium's player steps past a failed sentence on its own.
- A selection is found by the first 60 characters of its first sentence,
  cut with Readium's own sentence tokenizer rather than the bounded one,
  and the text before it tells repeated phrases apart.
- The session owns the place only while it plays, not while paused, so
  the guard on leaving the reader is simpler.
- Follow-along turns the page only when the spoken sentence leaves the
  screen, and for 1.5 seconds after a turn it treats the page moving as
  its own.
- The notification reopens the reader with `singleTop` and
  `onNewIntent`, so it never stacks a second reader.
- Starting from the page rather than a selection begins at the first
  block visible on screen, and alignment may step up to 80 sentences to
  reach it, because Readium's content iterator ignores a progression
  without a CSS selector.
- BookOrbit needs no special flag. A listening checkpoint carries no CFI,
  so like any CFI-less write it clears the verified one, and nothing is
  pushed until there is a CFI again. A session that starts on a pending
  catch-up offer settles it with its first checkpoint.

A heard sentence's progression counts paragraphs, not screens. Reopening
on it would land a page or so away, and a wide-content fit pass, which
captures the page before Readium has scrolled to it, used to throw the
reader back to the start of the chapter. Two changes fix that. When the
voice stops with the reader in the foreground, the page on screen is
captured and saved as an exact anchor, with its CFI for a BookOrbit book.
While the opening is still gated on a non-exact target, the fit pass
restores to that target instead of capturing the page.

One gap remains. If the voice is stopped from the notification while the
reader is in the background and the process then dies before the reader
comes back, the place stays the heard sentence, and reopening lands about
one page past it.
