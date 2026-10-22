# 54. Read aloud retries a failed request before it stops

Status: implemented

## Context

When a speech request fails, read aloud stops on the sentence that
failed and waits for the listener to press play. It never skips a
sentence: a missing sentence in a book is worse than a pause. That rule
stays.

Many failures do not need the listener at all. A phone changing from
Wi-Fi to mobile data drops a request in flight, a small home server
answers 503 while it loads its model, a proxy answers 502 for a moment.
Today each of these stops the reading. With the screen off and the
phone in a pocket, the listener has to take it out, unlock it and press
play, and the request then succeeds at once.

`SpeechCache.request` is where every request for audio is made, for the
sentence being spoken and for those fetched ahead. It already sorts
errors: an invalid key, voice or terms, a server that makes no speech,
and a rate limit stop all requests (`halted`); anything else fails only
that sentence. `SpeechTtsEngine` reports the failure, and the session
pauses on it.

## Decision

Each piece of audio a request asks for is tried up to three times when
the failure is likely to pass on its own:

- `SpeechError.Network`, a connection that failed, was reset or timed
  out before an answer;
- `SpeechError.Service` with HTTP 408, 500, 502, 503 or 504.

Nothing else is retried. A blocked local network needs the listener's
permission. A rate limit (429) keeps its current handling, since
retrying would spend more of the quota it reports. `InvalidResponse`
covers both bad audio and the device engine's errors, and a second try
of either is not expected to help. Errors that need the listener to act
are never retried.

The waits are one second before the second try and three seconds
before the third. A new try is not started once 30 seconds have passed
since the first began. That is checked after the retry has its
concurrency permit, so time spent queued behind other requests counts.
The 30 seconds only limit when a try may start. A
try started at 29 seconds can still run to the client's own timeout
(180 seconds for OpenAI-compatible servers). A server that times out
after two minutes is therefore tried once. The concurrency permit is
released during a wait, so a retry does not hold up the sentences
behind it.

Retries happen inside `request`, so the same rule serves the sentence
being spoken and those fetched ahead. Each request takes the
synthesizer and the cache generation when it starts, and its retries
use that synthesizer. A speculative request stops retrying when the
cache restarts (a jump) or swaps voice, since the restart cancels it
and its wait with it. A
request the engine has claimed for the sentence being spoken is not
cancelled by a restart today, so that a pause keeps it; its retries
follow the same rule and continue through a pause, and stop when the
engine drops the request (a skip, a stop, a voice change). While a
retry waits, the sentence is shown as waiting for its audio, as it is
for any slow request. A request that still fails after its tries fails
exactly as today: read aloud stops on that sentence and does not skip
it.

Retries are not shown or counted for the listener. They are logged at
debug level.

## Consequences

- Short network losses and server restarts no longer stop reading; the
  listener hears a longer pause instead.
- A failure that does not pass is reported later than today: four
  seconds of waits plus the time of two more tries, at most about 30
  seconds plus one client timeout.
- A server that fails on every request receives three times the
  requests before reading stops. The cap keeps that to the sentences in
  the prefetch window.
- Paid servers may bill a request that failed after the server did the
  work. That is the same risk as the listener pressing play again and
  is accepted.
- Tests drive `SpeechCache` with a fake synthesizer and virtual time:
  a network failure then success plays without a pause; each error kind
  that must not be retried is tried once; a restart during a wait stops
  a speculative request's retries; a claimed request keeps retrying
  through a restart and a pause and stops when dropped; a swap during a
  wait does not send the old request to the new voice; the 30-second
  budget is checked after a permit wait.
