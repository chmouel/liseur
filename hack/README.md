# hack/

Scripts used to build, test, and release Liseur. Most have a `make`
target wrapping them. Run `make help` for the short list. See
`DEVELOPER.md` for the full release workflow.

- `screenshots`: Captures the screenshots used by the README,
  `docs/SCREENSHOTS.md` and the F-Droid listing. `--setup` builds a demo
  shelf first (downloads a set of Standard Ebooks EPUBs, pushes them to
  the device, grants the library folder, seeds a highlight/note/bookmark
  and writes six weeks of reading sessions for the stats screens) before
  capturing; without it, captures from a device already set up.
  `--setup-only` builds the shelf and stops, to check it.
  `--no-dictionary` skips the definition card, which is the one capture
  that needs the device to reach a site. `hack/screenshots --help` for
  the rest of the flags.
- `reset-books`: Wipes the app's storage and the device's `Books`
  folder on a connected device/emulator, then reseeds it with the same
  demo book shelf as `screenshots --setup`, without capturing anything.
  Backs `make reset`. Downloaded books are cached under `tmp/books`,
  shared with `screenshots`.
- `e2e-upload` / `e2e-delete`: End-to-end checks of the two
  actions that reach a liseur-sync server's disk. Both drive the app
  through its own screens against a real server and then read that
  server's folder and the app's database to see what happened, so
  neither can pass on a mock. `e2e-delete -o DIR` also writes
  screenshots of the sheet and the confirmation, which is where the
  pictures in a pull request come from. `--help` for the flags.
- `e2e-open-external`: End-to-end check that a book handed over by
  another app lands on the shelf. Pushes an EPUB somewhere nothing
  watches, opens it through the same `VIEW` intent a file manager
  sends, and then asserts against the app's database that the book was
  shelved, that the reading position hangs off that shelved row, that a
  second handover of the same file does not shelve it twice, and that
  the upload offer can reach it. It runs under the "keep them here"
  upload policy. The script sets it through the settings screen and puts
  it back afterwards. This also proves that shelving a book and sending
  it are separate: the book reaches the shelf while no upload is attempted.
  Pass `-u URL` to name the liseur-sync server. `--help` for the flags.
- `lib/demo-books.sh`: Library sourced by `screenshots`,
  `reset-books` and the `e2e-*` scripts: the demo book list, the uiautomator-driven UI helpers,
  and the fetch/push/grant-folder logic. Not meant to be run directly.
- `install`: Builds a release-signed APK (key fetched from `pass`)
  and installs it in place over the device's existing release install,
  keeping its data. A debug build can't do this: Android refuses an
  update whose signature doesn't match.
- `install-release`: Builds the release APK, signs it with the
  release key from `pass`, and installs it on a device picked via `fzf`.
- `release`: Runs the release process: bumps `versionCode`/
  `versionName`, checks that F-Droid will see final tags but not RC tags,
  tags, builds, publishes the GitHub release, and submits the F-Droid
  update. `--rc` publishes only to a GitHub prerelease and Google Play's
  testing tracks. Final releases are also published to Google Play
  production. With no version given, it drafts the F-Droid changelog
  with `pi -p` (model `openai/$LISEUR_NOTES_MODEL`, default `gpt-6-luna`),
  falling back to `copilot -p` (model `LISEUR_NOTES_COPILOT_MODEL`, default
  `gpt-6-luna`) when pi fails.
  See `DEVELOPER.md` for the full workflow and flags.
- `prune-prereleases`: Deletes every legacy `vX.Y.Z-test.N` GitHub release
  page and all but the newest `vX.Y.Z-rc.N` page for each target version.
  It never deletes tags. The release workflow runs it after publishing an
  RC.
- `test-prune-prereleases`: Runs the deterministic mocked-GitHub tests for
  prerelease retention. It is part of `make check` and CI.
- `verify-fdroid-tags`: Reads the live F-Droid metadata and fails closed
  unless a plain `vX.Y.Z` tag is discoverable and the supplied non-final tags
  are not. The release script runs it before pushing or submitting a tag.
- `test-fdroid-tags`: Runs the deterministic fixture-based tests for the
  tag-policy verifier. It does not contact F-Droid and is part of `make check`
  and CI.
- `generate-release-notes`: Writes the GitHub release notes for a
  tag by asking an OpenAI model to turn the commits since the previous tag into
  prose. It also reads the pull requests associated with those commits,
  links each described change back to its PR, and carries over a relevant
  screenshot from the PR body when one is present. It falls back to the
  F-Droid changelog if generation fails. `hack/release` runs it before
  tagging (`--range`, `--changelog`, `--pi`, `--copilot`, `--no-fallback`) and
  commits the reviewed result as `docs/release-notes/vX.Y.Z.md`, which
  the release workflow then uses as it is.
- `store-status`: Answers "where is Liseur published, and how is it
  doing?" for all three channels at once: the last GitHub releases, what
  F-Droid has published and how old its index is, what its last build run
  did with the app, the fdroiddata metadata, any open merge request with
  its pipeline state, what sits on each Google Play track (production
  first, with any staged rollout), and how many crashes and ANRs Android
  vitals counted over the last seven days (`--days N`, or `make
  store-status DAYS=N`) with the top five issues. The Play sections sign
  a token with the service account from `pass` and are skipped with a line
  when they cannot. The vitals need the Google Play Developer Reporting
  API enabled in the service account's Google Cloud project. Backs `make
  store-status`.
- `verify-reproducible`: Builds the release APK twice from two
  independent clean checkouts and diffs them byte for byte, the check
  F-Droid's reproducible-builds requirement demands before submission.
- `verify-wide-content`: Checks that content wider than the page is
  measured and constrained rather than painted over the page after it
  (issue #67). The fix is JavaScript that runs inside the book's own
  document, and none of what it has to get right is visible from the
  JVM. The script is lifted straight out of `WideContentFit.kt`, never a
  copy, and run against Readium's own stylesheets in headless Chrome, in a
  frame the size of a phone. Needs Chrome or Chromium and a prior
  `./gradlew assembleFossDebug` (Readium's CSS is unpacked from the AAR by the
  build), so it is a check to run by hand when that script
  changes, not part of `make check`. `PORT=` picks another port.
- `verify-footnotes`: The same kind of check for the notes in issue #152:
  that a book's notes stay out of the page until they are asked for, and
  that a marker drawn as an image is the size of the words around it. The
  script comes out of `FootnoteLayout.kt`, and the cases run against
  Readium's CJK stylesheets, which are what it picks for the book in the
  issue and which nothing else here exercises. Same requirements and same
  reasons for staying out of `make check`. `PORT=` picks another port.
- `make-font-size-book`: Builds the local EPUB fixture for the publisher
  absolute-font-size failure. It contains separate chapters for body,
  descendant, relative, mixed, unstyled, and wide-table cases, and writes
  `tmp/books/liseur_font-size-fixture.epub` by default. The fixture is for
  manual Android validation; the desktop verification scripts do not prove
  native text zoom.
- `make-notes-book`: Builds the fixture book those notes are reproduced
  with — deliberately badly behaved in the two ways the reporter's edition
  is, and well behaved everywhere else, so a fix can be seen to leave the
  second alone. Seeded onto the shelf by `reset-books` and `screenshots`,
  and buildable on its own for opening by hand.
- `icon`: Renders the adaptive launcher icon (two vector drawables)
  to a flat PNG for the F-Droid listing and the README.
- `feature-graphic-font`: Subsets Noto Serif SC to the Chinese
  characters in `promo/feature-graphic.json`, for the feature graphic.
  Run it with fontTools whenever the zh copy changes.
- `store-images`: Converts screenshots in place to what Google Play
  accepts (no alpha, no more than 2:1, padded by repeating the edge
  pixels). `screenshots` runs it on every image it files with fastlane.
  `--check DIR...` validates without changing anything; the fastlane
  `screenshots` lane runs that before it contacts Play.
  `--feature-graphic FILE...` flattens a 1024x500 feature graphic
  without padding, and `--check --feature-graphic FILE...` checks one.
- `test-store-images`: Runs the deterministic tests for `store-images` on
  synthetic PNGs, and checks the committed store screenshots. It is part
  of `make check` and CI.
- `ai-stub`: A fake OpenAI-compatible server for checking AI translation
  and read-aloud speech on the emulator without a real provider. It
  answers the model list, chat completions (the passage back, prefixed
  with `[T] `), speech (silent WAV) and a voice list, and logs every
  request it gets to `tmp/ai-stub/requests.jsonl`, with the prompt's
  `<book>`, `<context>` and `<passage>` blocks split out. `--fail`,
  `--delay`, `--key` and `--think` reproduce provider errors, slow
  replies, API keys and reasoning models. Backs `make ai-stub`. See
  `DEVELOPER.md` for using it from the app.
- `test-ai-stub`: Starts `ai-stub` on a free port and checks every
  endpoint and flag. It is part of `make check` and CI.
- `openai-voices`: Compares the voices OpenAI's API spec accepts for
  speech with the built-in list Liseur offers for api.openai.com, and
  with `--write` adds the missing ones. Run monthly by the
  `openai-voices` workflow, which opens a pull request when it finds
  any. `test-openai-voices` checks it on sample specs; it is part of
  `make check` and CI.

See also `tests/`, which holds the headless, assertion-shaped end-to-end
scenarios. They measure behaviour rather than walk the screen.
The `e2e-*` scripts here drive the UI and need a visible device.
