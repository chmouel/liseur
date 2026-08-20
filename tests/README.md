# tests/

End-to-end scenarios that run against a real device or emulator over
`adb`. They exist for the behaviour that only shows up on a device —
timing, connectivity, the reader actually appearing — which is
everything the JVM unit tests in `app/src/test` cannot see.

They are meant to be run by a person or by an agent, unattended:

```bash
tests/run-all                  # every scenario, on the only attached device
tests/run-all -s emulator-5554 # or a named one
tests/offline-open --help      # one scenario, and what it does
```

Every scenario prints one `PASS`/`FAIL`/`SKIP` line per check and exits
`0` when it passed, `3` when it skipped itself, and anything else when
it failed. An agent can branch on the exit code alone. Skipping is kept
separate on purpose: a device that tested nothing must not be reported
as a device that passed.

## What is here

| Scenario | What it proves |
| --- | --- |
| `offline-open` | A book opens promptly with no network, and — the case that regressed — with a network whose server is unroutable. |
| `bench-open` | Not pass/fail: prints how long a book takes to open under each network condition. |

## Preconditions

- A device or emulator with a **debug** build installed (`make install`).
  The scenarios read the app's database through `run-as`, which a
  release build does not allow.
- A seeded library. `make reset` gives you one.
- For anything touching sync, a connected server row in `remote_server`.
  A scenario with no server to work with skips itself rather than
  failing.

`make emulator` boots the default AVD; `make run-bg` installs and
launches without `scrcpy`.

## Writing a new one

Copy the shape of `offline-open`:

```bash
#!/usr/bin/env bash
#
# tests/my-scenario -- one line on what must be true.
#
# ... prose on why this is worth a device test ...
#
# Usage: tests/my-scenario [-s SERIAL]

source "$(dirname "$(readlink -f "$0")")/lib/device.sh"

parse_common_args "$@"
require_device
# ... checks ...
report
```

`--help` prints the header comment, so the documentation cannot drift
away from the code. Make the file executable — `run-all` picks up
whatever is executable in this directory.

### What `lib/device.sh` gives you

- `die`, `step`, `note` for output; `ok`, `bad`, `skip`, `report` for
  results. `report` exits non-zero if anything called `bad`.
- `adb` — a wrapper pinned to the chosen serial. Always use it.
- `query "<sql>"` — SQL against the app's database, and `sql_quote` to
  build a literal. Use it for anything that came off the device: a
  configured server address is text the reader typed, and one
  apostrophe pasted straight into a statement ends the string and runs
  whatever follows.
- `shell_quote` — the same care for the *device's* shell. Everything
  handed to `adb shell` is re-parsed there, so a book title with an
  apostrophe in its file name would otherwise end the argument and run
  the rest as the shell user.
- `set_server_url "<url>"` — points the connected server somewhere,
  with the app stopped first and the result checked afterwards.
- `airplane_mode_state` — `on` or `off`, to read before you change it.
- `device_now_ms` — the *device's* clock, so adb round-trips are not
  counted as time the app spent working.
- `pick_downloaded_book` — sets `BOOK_URL` and `BOOK_LOCAL_URI`.
- `time_book_open URL LOCAL_URI [CAP_MS]` — milliseconds, or `timeout`.
- `expect_open_under LABEL MEASURED BUDGET_MS`.
- `airplane_mode on|off`.

### Two things to get right

**Use the database as the oracle.** Screen scraping is slow and lies.
Book-open timing keys off `books.last_opened_at`, which the reader
writes one line before it shows the page, so it is the honest end of
"the book opened".

**Restore what you changed, from a trap.** These run against a device
someone else will use next. Anything a scenario sets — airplane mode, a
server address, app data — is put back on the way out, however the
script leaves:

```bash
trap restore EXIT
```

Put back what was *there*, not what you assume: read airplane mode
before turning it on, so a device that had it on keeps it. Stop the app
before writing to its database, and check the value afterwards rather
than announcing success — leaving a blackhole address behind quietly
breaks the next thing anyone does with the device. `set_server_url`
does all three.

**Point a server nowhere real.** To make a server unreachable these
scenarios change only its address, so the app keeps the credentials it
had and sends them to whatever answers. Use `192.0.2.1` (TEST-NET-1,
reserved by RFC 5737 and routed nowhere), never a private `10.x` or
`192.168.x` address, which on somebody's network is perfectly capable
of answering.

## Related

`hack/e2e-*` holds the older interactive UI walkthroughs (deletion,
opening from another app, upload), which drive the screen with `input
tap` and need a visible device. Scenarios here are the headless,
assertion-shaped ones. See `hack/README.md`.
