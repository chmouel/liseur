# hack/

Scripts used to build, test, and release Liseur. Most have a `make`
target wrapping them — run `make help` for the short list. See
`DEVELOPER.md` for the full release workflow.

- **`screenshots`** — Captures the screenshots used by the README and
  the F-Droid listing. `--setup` builds a demo shelf first (downloads a
  set of Standard Ebooks EPUBs, pushes them to the device, grants the
  library folder, and seeds a highlight/note/bookmark) before capturing;
  without it, captures from a device already set up. `--setup-only`
  builds the shelf and stops, to check it. `hack/screenshots --help` for
  the rest of the flags.
- **`reset-books`** — Wipes the app's storage and the device's `Books`
  folder on a connected device/emulator, then reseeds it with the same
  demo book shelf as `screenshots --setup`, without capturing anything.
  Backs `make reset`. Downloaded books are cached under `tmp/books`,
  shared with `screenshots`.
- **`e2e-upload`** / **`e2e-delete`** — End-to-end checks of the two
  actions that reach a liseur-sync server's disk. Both drive the app
  through its own screens against a real server and then read that
  server's folder and the app's database to see what happened, so
  neither can pass on a mock. `e2e-delete -o DIR` also writes
  screenshots of the sheet and the confirmation, which is where the
  pictures in a pull request come from. `--help` for the flags.
- **`e2e-open-external`** — End-to-end check that a book handed over by
  another app lands on the shelf. Pushes an EPUB somewhere nothing
  watches, opens it through the same `VIEW` intent a file manager
  sends, and then asserts against the app's database that the book was
  shelved, that the reading position hangs off that shelved row, that a
  second handover of the same file does not shelve it twice, and that
  the upload offer can reach it. It runs under the "keep them here"
  upload policy — which it sets through the settings screen and puts
  back afterwards — so it also proves that shelving a book and sending
  it are separate: the book reaches the shelf while not one upload is
  attempted. Pass `-u URL` to name the liseur-sync server. `--help` for
  the flags.
- **`lib/demo-books.sh`** — Library sourced by `screenshots`,
  `reset-books` and the `e2e-*` scripts: the demo book list, the uiautomator-driven UI helpers,
  and the fetch/push/grant-folder logic. Not meant to be run directly.
- **`install`** — Builds a release-signed APK (key fetched from `pass`)
  and installs it in place over the device's existing release install,
  keeping its data. A debug build can't do this: Android refuses an
  update whose signature doesn't match.
- **`install-release`** — Builds the release APK, signs it with the
  release key from `pass`, and installs it on a device picked via `fzf`.
- **`release`** — Runs the release process: bumps `versionCode`/
  `versionName`, tags, builds, publishes the GitHub release, and submits
  the F-Droid update. See `DEVELOPER.md` for the full workflow and flags.
- **`generate-release-notes`** — Writes the GitHub release notes for a
  tag by asking Gemini to turn the commits since the previous tag into
  prose. It also reads the pull requests associated with those commits,
  links each described change back to its PR, and carries over a relevant
  screenshot from the PR body when one is present. It falls back to the
  F-Droid changelog if generation fails.
- **`store-status`** — Answers "where is Liseur published?" for all
  three channels at once: the last GitHub releases, what F-Droid has
  published and how old its index is, what its last build run did with
  the app, the fdroiddata metadata, any open merge request with its
  pipeline state, and what sits on each Google Play track. Everything
  but the Play section reads public sources; Play has no public answer
  while the app is in internal testing, so that one signs a token with
  the service account from `pass` and is skipped with a line when it
  cannot. Backs `make store-status`.
- **`verify-reproducible`** — Builds the release APK twice from two
  independent clean checkouts and diffs them byte for byte, the check
  F-Droid's reproducible-builds requirement demands before submission.
- **`verify-wide-content`** — Checks that content wider than the page is
  measured and constrained rather than painted over the page after it
  (issue #67). The fix is JavaScript that runs inside the book's own
  document, and none of what it has to get right is visible from the
  JVM, so the script is lifted straight out of `WideContentFit.kt` —
  never a copy — and run against Readium's own stylesheets in headless
  Chrome, in a frame the size of a phone. Needs Chrome or Chromium and a
  prior `./gradlew assembleDebug` (Readium's CSS is unpacked from the
  AAR by the build), so it is a check to run by hand when that script
  changes, not part of `make check`. `PORT=` picks another port.
- **`icon`** — Renders the adaptive launcher icon (two vector drawables)
  to a flat PNG for the F-Droid listing and the README.
- **`feature-graphic`** — Renders the 1024x500 store feature graphic
  from the app's own emblem.

See also `tests/`, which holds the headless, assertion-shaped end-to-end
scenarios — the ones that measure behaviour rather than walk the screen.
The `e2e-*` scripts here drive the UI and need a visible device.
