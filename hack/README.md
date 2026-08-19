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
- **`verify-reproducible`** — Builds the release APK twice from two
  independent clean checkouts and diffs them byte for byte, the check
  F-Droid's reproducible-builds requirement demands before submission.
- **`icon`** — Renders the adaptive launcher icon (two vector drawables)
  to a flat PNG for the F-Droid listing and the README.
- **`feature-graphic`** — Renders the 1024x500 store feature graphic
  from the app's own emblem.
