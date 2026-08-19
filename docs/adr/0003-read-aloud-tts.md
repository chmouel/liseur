# 3. Read aloud

Status: proposed
GitHub issue: [#42](https://github.com/chmouel/liseur/issues/42)

## Context

There is no way to have a book read to you. Commuting, cooking, tired
eyes at night: all of them end the session where a Kindle keeps going.
Readium ships a TTS navigator, and Android ships a speech engine on
every device, so the pieces exist; only the wiring does not.

## Decision

Read-aloud through Readium's `TtsNavigator` backed by the system
`TextToSpeech` engine. Play and pause, skip forward and back by
utterance, and the sentence being spoken highlighted on the page.
Nothing more: no voice store, no bundled voices, no speed presets
beyond the engine's own rate slider.

Fit with Liseur's simplicity: one "Read aloud" row in the Advanced
sheet starts it; while it runs, a small play/pause bar sits where the
reading footer is, and stopping it gives the footer back.

## Design

The system engine keeps the feature inside the hard constraints: no
network, no proprietary blob, whatever voices the user already has.
An AOSP device without an engine gets the row greyed out with one line
saying why.

`ReaderViewModel` owns the TTS session the way it owns search: started,
observed as a `StateFlow` of the current utterance's locator, stopped
when the reader closes. The spoken sentence reuses the decoration
machinery from `reader/annotations/Annotations.kt` with a transient
decoration that is never persisted.

Position: pausing writes the current utterance's locator through the
same path as a page turn, so a book stopped by ear reopens at the same
place by eye, and position sync needs no new rules.

## Consequences

A media session and audio focus have to be handled, which is the real
cost of the feature; keeping playback tied to the open reader (no
background service, screen may stay on via the existing keep-screen-on
toggle) keeps that cost small for a first version. Background playback
is a separate decision for a separate ADR if anyone asks for it.

*Where:* `reader/ReaderViewModel.kt`, `reader/ReaderScreen.kt`,
`reader/chrome/TypographySheet.kt`, new `reader/tts/`.
