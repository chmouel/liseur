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
| Story so far, with chapter recaps on demand | #355, #356 | 0057 | Implemented on this branch, being verified |
| Ask the book | #357 | 0058 (to write) | Not started |
| Book memory | #358 | 0059 (to write) | Not started, depends on #357 |

#356 (recap a chapter on demand) became part of #355: the Story tab is
where a reader asks for it. Close #356 with the pull request for #355.

Before writing an ADR, check again which number is free, since other
branches may already use it. Every new ADR amends ADR-0051, which ruled
out a recap browser and follow-up chat. Add the amendment to ADR-0051's
status line.

## Story so far (#355 and #356, this branch)

The design is in
[docs/adr/0057-codex-tells-the-story-so-far.md](docs/adr/0057-codex-tells-the-story-so-far.md).
The plan behind it was reviewed with `codex-review` until it approved.

What is done:

- Codex has a Story tab, first and opened by default from the ⋮ menu,
  shown only while a companion service is chosen. Codex freezes the
  full asking scope on open; a peek origin counts as translated, which
  also closes the gap where the ⋮ Codex entry did not do so.
- The tab shows a running summary of the story so far, a timeline of
  the earlier chapters with their recap sentences and Go to chapter (a
  peek), and Recap, Recap again, Retry and "Recap k missing chapters".
- `RecapScheduler` has lanes: lookups, requested recaps, Story's
  summary nodes, background recaps. Requested recaps work with
  background recaps off and never send a chapter at or after the
  asking place.
- A consent card asks once (`story_auto_ok`, also a switch in companion
  settings). Once allowed, each frozen scope recaps the chapter before
  the place once if it is missing, and writes the summary from at least
  two recaps. Stop and Not now hold for that
  scope.
- The summary is a bounded tree of windows of eight (`StorySummaries`),
  cached in `companion.db`'s new `story_summary` table (schema 4) and
  included in the exported ZIP.
- Strings in all seven locales; DEVELOPER.md, README.md, PRIVACY.md and
  the ADR updated. `StorySheet` and the old ⋮ Story entry are gone.

Still to do:

- Full checks:
  `./gradlew testFossDebugUnitTest testPlayDebugUnitTest lintFossDebug lintPlayDebug assembleFossDebug assemblePlayDebug`
  (or `make check`).
- Emulator pass: the tab with and without a service, consent and
  autofill, Recap, Recap missing chapters, Stop, a cached summary on
  reopen, Go to chapter with Return and Show, three tabs on a narrow
  phone. Screenshots go to `~/tmp/sharei/liseur/shots`.
- Open the pull request using the repository template. Link #355, #356
  and ADR-0057, and run deslop on the text first.

## Ask the book (#357, ADR-0058)

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
- Recap prose is sent only by Story's running summary (ADR-0057); keep
  it out of questions.
- UI: add a question view to `CompanionSheet` (input, claims with source
  pills, follow-up field), cached per open book like lookups.
- Update the "Only these go into a request body" rule in DEVELOPER.md
  and the companion section of PRIVACY.md.
- Tests: a sentinel test that no later-chapter text leaves for questions
  or follow-ups; citation checks; limits; the follow-up cap.

## Book memory (#358, ADR-0059)

- Needs Ask the book first.
- New `companion.db` table (migration 4 to 5) keyed by book URL and
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
  New companion work goes through `RecapScheduler`'s lanes.
- Do not change `Recap.FORMAT`, because that drops every saved recap.
- Ask the user before committing, pushing, opening a pull request, or
  creating or editing an issue. Run deslop on any public text first.
  No co-author trailers.
- Codex review command (from the `codex-review` skill):
  `copilot -p "..." --model gpt-6.1-sol --deny-tool create --deny-tool edit --deny-tool bash --deny-tool task --allow-all-tools --allow-all-paths -s`
