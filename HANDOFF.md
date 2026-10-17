# Handoff: reading companion features inspired by Readest's Reedy

This file is for whoever picks up the work next, person or agent. Start
with `git checkout story-so-far-355`, read AGENTS.md and the
"Reading companion" section of DEVELOPER.md, then finish the
Story so far list below before starting the next feature. Delete this file
in the last pull request of the series.

Readest has an unreleased agent called Reedy (readest/readest,
`services/reedy/`). Four of its ideas were picked for Liseur's reading
companion. Each one gets its own GitHub issue, its own ADR reviewed with
the `codex-review` skill until it approves, its own branch, and its own
pull request.

| Feature | Issue | ADR | State |
| --- | --- | --- | --- |
| Story so far | #355 | 0057 (approved) | Implemented on this branch, not finished |
| Summarize a finished chapter on demand | #356 | 0058 (to write) | Not started |
| Ask the book | #357 | 0059 (to write) | Not started |
| Book memory | #358 | 0060 (to write) | Not started, depends on #357 |

Before writing an ADR, check again which number is free, since other
branches may already use it. Every new ADR amends ADR-0051, which ruled
out a recap browser and follow-up chat. Add the amendment to ADR-0051's
status line.

## Story so far (#355, this branch)

The design is in
[docs/adr/0057-the-story-so-far-shows-stored-recaps.md](docs/adr/0057-the-story-so-far-shows-stored-recaps.md).
Codex approved it after one round of changes.

What is done:

- The reader's ⋮ menu has a "Story so far" entry. It shows once the
  book has a saved recap for its current content sha and `Recap.FORMAT`.
- It opens a sheet listing the chapters before the reader's place. Each
  row shows the chapter title, the saved recap sentences, or a note when
  there is no summary, the recap can't be read, or no recap exists.
  "Go to chapter" jumps there as a peek, so Return comes back and the
  reading position is not saved.
- Nothing is sent over the network.
- `ChapterMap.startsBefore` lists a chapter only when its start is
  provably before the place, so a contents page listed out of order
  cannot reveal a later chapter. A peek origin is treated as a
  translated page.
- `BookRecaps.stored()` reads without deleting rows from another sha or
  format. Only rows shown with a summary are touched for trimming.
- Strings exist in English, fr, es, ru, it, de and zh-Hans.
- DEVELOPER.md, README.md and docs/PRIVACY.md have been updated.
- Tests are in `ChapterMapTest`, `StoryTest`, `CompanionStoreTest` and
  `PublicationCatalogTest`. The companion tests pass with
  `./gradlew testFossDebugUnitTest --tests 'com.chmouel.liseur.companion.*'`.

Still to do:

- Run the full checks. That run was stopped before it finished:
  `./gradlew testFossDebugUnitTest testPlayDebugUnitTest lintFossDebug lintPlayDebug assembleFossDebug assemblePlayDebug`
  (or `make check`).
- Check it on the emulator: `make emulator` then `make run-bg`. On the
  Mac, the AVD `liseur_phone_api36` lives in `~/.config/.android/avd`, so
  set `ANDROID_AVD_HOME=~/.config/.android/avd`. Seed `chapter_recap`
  rows in `companion.db` with `run-as`, or turn on background recaps and
  read a few chapters.
- Things to look at on the device: the menu entry shows and hides
  correctly; the sheet's states (loading, empty, rows); Go to chapter
  lands on a chapter that starts at an anchor inside a file (it relies on
  the navigator honouring `locations.fragments` in a locator built with
  `locatorFromLink`, which has not been verified); Return and Show in the
  peek bar; the sheet closing when Codex opens and the other way round;
  auto-scroll staying paused while the sheet is visible.
- Save screenshots to `~/tmp/liseur/sshot`, and add one to the pull
  request.
- Open the pull request using the repository template. Link #355 and
  ADR-0057, and run deslop on the text first.

Known gap left alone on purpose: Codex opened from the ⋮ menu during a
peek still uses `pageTranslated` for the peek origin. Story so far treats
a peek origin as translated. Fixing Codex belongs in its own change.

## Summarize a finished chapter on demand (#356, ADR-0058)

- From Story so far, a row with no recap offers "Summarize". It works
  even when background recaps are off, because the reader asked for it.
  It needs a companion service to be chosen.
- Reuse the `Recap` batching, prompt, parsing and parts storage, and save
  a normal `chapter_recap` row, as `RecapScheduler` does. Lookups and
  Story so far then use it too.
- Keep the same limit: only chapters strictly before the asking one,
  never the chapter on screen or a peek origin.
- Coordinate with `RecapScheduler`: one batch at a time, step aside for a
  lookup, and skip a chapter already queued. Show progress and the
  existing failure view.
- Privacy: the request is the same as a background recap. Update
  PRIVACY.md to say recaps can also be asked for one chapter at a time.
- Tests: capture requests and check that no text from the asking chapter
  or later leaves the device; scheduler dedupe; lookups reuse the saved
  row.

## Ask the book (#357, ADR-0059)

- Turn `Investigation` from "who is NAME" into an open question. Name
  lookups must behave exactly as before, and their existing tests must
  pass unchanged.
- Entry points: ⋮ menu "Ask the book" (the scope is the current place)
  and the selection bar (the scope is the selection). The scope is fixed
  when the panel opens and stays the same through sources and Return.
- The limit is the same as name lookups: earlier chapters plus the
  asking chapter up to the paragraph before the place
  (`Eligibility.allows` with a `ReadPoint`).
- The request carries the question text (this is new: text the reader
  wrote leaves the device), the scope, session refs and eligible
  passages. Never chapter titles, notes, recap prose or model replies.
- Seed passages with keyword matches across eligible chapters plus recap
  hints, then use the existing `search`/`read` action loop and
  `InvestigationLimits`. The answer is a list of claims, each citing refs
  sent in that lookup. An "insufficient" answer is allowed and shown
  plainly.
- Follow-ups in the same panel, up to a number set in the ADR (about 5).
  Each turn passes the gate again. Earlier model answers are not sent
  again, only earlier questions and the refs they cited.
- UI: add a question view to `CompanionSheet` (input, claims with source
  pills, follow-up field), cached per open book like lookups.
- Update the "Only these go into a request body" rule in DEVELOPER.md
  and the companion section of PRIVACY.md.
- Tests: a sentinel test that no later-chapter text leaves for questions
  or follow-ups; citation checks; limits; the follow-up cap.

## Book memory (#358, ADR-0060)

- Needs Ask the book first.
- New `companion.db` table (migration 3 to 4) keyed by book URL and
  content sha: past questions, asking scope, cited refs with a passage
  hash, and timestamps. Model prose is not stored.
- When a new question is asked, the closest earlier entries (keyword
  overlap and recency) add their question text and refs. The refs are
  fetched again from the local catalog, hash-checked and passed through
  the gate for the current scope, so memory can never reach further than
  the current place.
- UI: past questions in the Ask panel with "ask again" and "forget"; a
  per-book clear; a switch under Companion settings (the ADR decides the
  default).
- Lifecycle: swept with the book like `recap_choice`, dropped when the
  content sha changes, kept out of Android auto-backup. The ADR decides
  whether it goes in the exported ZIP (suggested: yes, like recaps) and
  whether the switch travels in settings backup. It is not a liseur-sync
  setting.
- Privacy: PRIVACY.md says earlier questions about the same book may be
  sent again with a new question.
- Tests: migration, the gate check on reuse, dropping on sha change, the
  sweep.

## Working rules for this work

- All four features live in `app/src/main/` so both flavors get them.
  No Gemini-specific code.
- Keep the existing limits: one corrective request and 10 HTTP requests
  per lookup, and recap work only while the app is in the foreground.
- Do not change `Recap.FORMAT`, because that drops every saved recap.
- Ask the user before committing, pushing, opening a pull request, or
  creating or editing an issue. Run deslop on any public text first.
  No co-author trailers.
- Codex review command (from the `codex-review` skill):
  `copilot -p "..." --model gpt-6.1-sol --deny-tool create --deny-tool edit --deny-tool bash --deny-tool task --allow-all-tools --allow-all-paths -s`
