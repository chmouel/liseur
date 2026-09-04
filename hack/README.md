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
- `grimmory-dev`: Brings up a throwaway
  [Grimmory](https://github.com/grimmory-tools/grimmory) server
  in containers, seeded and ready for Liseur to connect to, so the
  Grimmory client is developed against the real thing rather than a
  reading of its source. `--up` starts and seeds it, `--down` deletes it
  and everything it held, `--reset` does both, `--info` reprints the
  credentials. Grimmory hides its Komga compatibility shim behind an
  admin setting and a separate set of credentials, an "OPDS user" rather
  than the browser login. The script sets up both because either missing
  one fails in a way that does not say so. The image tag in
  `hack/grimmory/docker-compose.yml` is pinned deliberately: a
  compatibility shim is exactly the thing that moves under you. The
  seeded shelf is larger than one catalog page on purpose, and holds one
  CBZ, so paging and the EPUB-only filter both have something real to
  work on. Everything lives under `tmp/`. This is developer tooling only.
  Nothing in Gradle, CI or the release path may depend on it.
- `grimmory/seed-books.py`: Generates the books `grimmory-dev`
  seeds: filler EPUBs, each with a cover, plus one CBZ. Everything is
  generated, so no downloads and nothing copyrighted. Not meant to be run
  directly.
- `install`: Builds a release-signed APK (key fetched from `pass`)
  and installs it in place over the device's existing release install,
  keeping its data. A debug build can't do this: Android refuses an
  update whose signature doesn't match.
- `install-release`: Builds the release APK, signs it with the
  release key from `pass`, and installs it on a device picked via `fzf`.
- `release`: Runs the release process: bumps `versionCode`/
  `versionName`, checks that F-Droid will see final tags but not test tags,
  tags, builds, publishes the GitHub release, and submits the F-Droid
  update. See `DEVELOPER.md` for the full workflow and flags.
- `verify-fdroid-tags`: Reads the live F-Droid metadata and fails closed
  unless a plain `vX.Y.Z` tag is discoverable and the supplied non-final tags
  are not. The release script runs it before pushing or submitting a tag.
- `test-fdroid-tags`: Runs the deterministic fixture-based tests for the
  tag-policy verifier. It does not contact F-Droid and is part of `make check`
  and CI.
- `generate-release-notes`: Writes the GitHub release notes for a
  tag by asking Gemini to turn the commits since the previous tag into
  prose. It also reads the pull requests associated with those commits,
  links each described change back to its PR, and carries over a relevant
  screenshot from the PR body when one is present. It falls back to the
  F-Droid changelog if generation fails.
- `store-status`: Answers "where is Liseur published?" for all
  three channels at once: the last GitHub releases, what F-Droid has
  published and how old its index is, what its last build run did with
  the app, the fdroiddata metadata, any open merge request with its
  pipeline state, and what sits on each Google Play track. It also says
  when the closed track that testers are recruited into falls behind
  internal, who may install from it, which countries it reaches, and
  whether its testers come from a Google Group or an email list the API
  cannot read. It also prints the opt-in link they are handed.
  Everything but the Play section reads public sources; Play has no
  public answer while the app is in testing, so that one signs a token
  with the service account from `pass` and is skipped with a line when it
  cannot. Backs `make store-status`.
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
  `./gradlew assembleDebug` (Readium's CSS is unpacked from the AAR by the
  build), so it is a check to run by hand when that script
  changes, not part of `make check`. `PORT=` picks another port.
- `verify-footnotes`: The same kind of check for the notes in issue #152:
  that a book's notes stay out of the page until they are asked for, and
  that a marker drawn as an image is the size of the words around it. The
  script comes out of `FootnoteLayout.kt`, and the cases run against
  Readium's CJK stylesheets, which are what it picks for the book in the
  issue and which nothing else here exercises. Same requirements and same
  reasons for staying out of `make check`. `PORT=` picks another port.
- `make-notes-book`: Builds the fixture book those notes are reproduced
  with — deliberately badly behaved in the two ways the reporter's edition
  is, and well behaved everywhere else, so a fix can be seen to leave the
  second alone. Seeded onto the shelf by `reset-books` and `screenshots`,
  and buildable on its own for opening by hand.
- `icon`: Renders the adaptive launcher icon (two vector drawables)
  to a flat PNG for the F-Droid listing and the README.
- `feature-graphic`: Renders the 1024x500 store feature graphic
  from the app's own emblem.

See also `tests/`, which holds the headless, assertion-shaped end-to-end
scenarios. They measure behaviour rather than walk the screen.
The `e2e-*` scripts here drive the UI and need a visible device.
