# shellcheck shell=bash
#
# Shared plumbing for the end-to-end scenarios in tests/.
#
# These run against a real device or emulator over adb, using the app's
# own database as the oracle: whatever the reader wrote is what the
# reader did, which is the only thing a black-box test can honestly
# claim to know.
#
# A sourcing script is expected to call parse_common_args "$@" and then
# require_device. Everything else here is opt-in.

set -euo pipefail

readonly APP_ID="com.chmouel.liseur"
readonly READER_ACTIVITY="$APP_ID/.reader.ReaderActivity"
readonly DB_PATH="/data/data/$APP_ID/databases/liseur.db"

serial="${SERIAL:-}"
_failures=0

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
note() { printf '    %s\n' "$*"; }

ok() { printf '    \033[32mPASS\033[0m %s\n' "$*"; }

bad() {
  printf '    \033[31mFAIL\033[0m %s\n' "$*"
  _failures=$((_failures + 1))
}

# Skips rather than fails: a scenario whose precondition the device does
# not meet has proved nothing, and pretending otherwise is worse than
# saying so. Exits SKIP_EXIT so a runner can tell "nothing to test here"
# from "everything passed" -- reporting an untested device as green is
# how a broken setup goes unnoticed.
readonly SKIP_EXIT=3
skip() {
  printf '    \033[33mSKIP\033[0m %s\n' "$*"
  exit $SKIP_EXIT
}

# Prints the tally and exits with the truth about it, so a runner (or an
# agent) can branch on the exit code alone.
report() {
  if ((_failures > 0)); then
    printf '\n\033[31m%d check(s) failed\033[0m\n' "$_failures"
    exit 1
  fi
  printf '\n\033[32mall checks passed\033[0m\n'
}

adb() { command adb ${serial:+-s "$serial"} "$@"; }

# Consumes the flags every scenario understands and leaves the rest in
# REMAINING_ARGS for the caller.
parse_common_args() {
  REMAINING_ARGS=()
  while (($#)); do
    case "$1" in
      -s | --serial)
        serial="${2:-}"
        [[ -n "$serial" ]] || die "-s needs a device serial"
        shift 2
        ;;
      -h | --help)
        # The header comment of the calling script is its help text, so
        # the documentation cannot drift away from the code.
        sed -n '2,/^$/s/^# \{0,1\}//p' "$0"
        exit 0
        ;;
      *)
        REMAINING_ARGS+=("$1")
        shift
        ;;
    esac
  done
}

require_device() {
  command -v adb >/dev/null || die "adb is not on PATH"
  local devices
  devices=$(command adb devices | awk 'NR>1 && $2=="device" {print $1}')
  [[ -n "$devices" ]] || die "no device is attached; try: make emulator"
  if [[ -z "$serial" ]]; then
    if [[ $(wc -l <<<"$devices") -gt 1 ]]; then
      die "several devices are attached; pick one with -s:"$'\n'"$devices"
    fi
    serial="$devices"
  fi
  note "device: $serial"
  adb shell "pm path $APP_ID" >/dev/null 2>&1 ||
    die "$APP_ID is not installed; try: make install"
}

# Runs SQL against the app's database. Needs a debuggable build, which
# is what these scenarios are for.
#
# The statement crosses two shells -- the host's and the device's -- so
# it is single-quoted for the device and the single quotes are escaped
# for adb. Build any literal with sql_quote rather than pasting a value
# straight in: a configured server URL is reader-supplied text, and one
# apostrophe in it would otherwise end the string and run the rest.
query() {
  local sql=${1//\'/\'\\\'\'}
  adb shell "run-as $APP_ID sqlite3 $DB_PATH '$sql'" | tr -d '\r'
}

# A value as a SQL string literal, with its quotes doubled.
sql_quote() {
  local v=${1//\'/\'\'}
  printf "'%s'" "$v"
}

# A value as one argument for the device's shell.
#
# Everything handed to `adb shell` is a string the device re-parses, so
# a value taken off the device -- a book's URL, a local file name -- has
# to be quoted for that second parse or an apostrophe in a title ends
# the argument and runs whatever follows as the shell user.
shell_quote() {
  local v=${1//\'/\'\\\'\'}
  printf "'%s'" "$v"
}

# Points the connected server at a URL, with the app stopped.
#
# Stopped first because a sync already in flight would otherwise read
# the old address or race this write, and the whole point of these
# scenarios is to control which address the reader tries. Checked
# afterwards rather than announced: leaving a dead address behind
# quietly breaks the next thing anyone does with the device.
set_server_url() {
  local url="$1" seen
  adb shell "am force-stop $APP_ID" >/dev/null
  query "update remote_server set base_url = $(sql_quote "$url");" >/dev/null
  seen=$(query "select base_url from remote_server limit 1;")
  [[ "$seen" == "$url" ]] || die "could not point the server at $url (it reads $seen)"
}

# The stored server credentials go wherever the base URL points, so a
# diversion must never point at a machine that could answer. Blanking
# them for the diversion sounds safer but is impossible here: the app
# deletes any account whose secret cannot be decrypted (that is what a
# database restored without its Keystore looks like), so a blanked
# credential silently disconnects the server and the scenario measures
# nothing. Instead, the device itself checks that the diversion address
# is dark before anything is pointed at it.
#
# ICMP is a proxy for "something lives there" -- a host that answers
# ping is a host that might answer TCP. TEST-NET addresses are dropped
# by every well-behaved network, so an answer means this network is not
# one, and the only safe move is to not divert at all.
#
# Two ways this check could lie are closed off. A configured HTTP proxy
# would carry the request (and the credentials on it) somewhere else
# entirely, ping or no ping — and Android can get one globally, from
# the network itself, or as a PAC script, so all three are looked for
# and any sign of one refuses the diversion. And the ping must
# demonstrably have run and heard nothing: a missing binary, a
# permission error or an unknown host is not darkness, and fails the
# same way an answer does.
assert_address_dark() {
  local host="$1" proxy links out
  proxy=$(adb shell settings get global http_proxy 2>/dev/null | tr -d '\r')
  case "$proxy" in
    "" | null | :0) ;;
    *) die "this device routes HTTP through a proxy ($proxy); \
a diverted request would go there, not to $host" ;;
  esac
  # Per-network proxies and PAC scripts never appear in that setting;
  # they show up on the network's link, which only mentions a proxy
  # when one is set. An unreadable answer is treated as a proxy.
  links=$(adb shell dumpsys connectivity 2>/dev/null | tr -d '\r')
  if [[ -z "$links" ]] || grep -qi 'HttpProxy\|PacFileUrl\|PAC Script' <<<"$links"; then
    die "cannot show this device's network has no proxy of its own; \
a diverted request might not go to $host at all"
  fi
  out=$(adb shell "ping -c 1 -W 2 $host" 2>&1 | tr -d '\r') || true
  if ! grep -q '100% packet loss' <<<"$out"; then
    die "cannot show $host is dark from this device; \
refusing to point the app (and its credentials) there"
  fi
}

# Points the server at a URL after checking, from the device, that
# nothing answers there.
divert_server_to() {
  local url="$1" host
  host=${url#*://}
  host=${host%%:*}
  host=${host%%/*}
  assert_address_dark "$host"
  set_server_url "$url"
}

# The device's own clock in milliseconds. Timings are computed from this
# rather than the host's, so adb round-trips are not counted as time the
# reader spent working.
device_now_ms() { adb shell 'date +%s%3N' | tr -d '\r'; }

# Whether airplane mode is on right now: "on" or "off". Read before
# changing it, so a scenario puts the device back the way its owner had
# it rather than the way the scenario assumed.
#
# Only the two settings the device actually stores are accepted. An adb
# that failed, or a device that answered something else, must not read
# as "off": a scenario would then restore a state it never established
# and announce it had put things back.
airplane_mode_state() {
  local raw
  raw=$(adb shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r') ||
    return 1
  case "$raw" in
    1) printf 'on\n' ;;
    0) printf 'off\n' ;;
    *) return 1 ;;
  esac
}

airplane_mode() {
  local current
  current=$(airplane_mode_state) || die "could not read the airplane mode setting"
  [[ "$current" != "$1" ]] || return 0
  case "$1" in
    on) adb shell cmd connectivity airplane-mode enable >/dev/null ;;
    off) adb shell cmd connectivity airplane-mode disable >/dev/null ;;
    *) die "airplane_mode takes on or off" ;;
  esac
  # The radios take a moment to follow the setting.
  sleep 3
}

# Puts the device back the way a scenario found it, and says so only if
# that is true.
#
# Both arguments may be empty, meaning there is nothing to put back.
# Every step is attempted even after an earlier one fails, because a
# half-restored device is worse than a loud one, and each is read back
# rather than assumed: a scenario that leaves a device pointed at a
# blackhole or with its radios off must not be able to report success.
# Returns non-zero if anything is still wrong.
restore_device() {
  local want_url="$1" want_airplane="$2" seen failed=0

  adb shell "am force-stop $APP_ID" >/dev/null 2>&1 || true

  if [[ -n "$want_url" ]]; then
    query "update remote_server set base_url = $(sql_quote "$want_url");" \
      >/dev/null 2>&1 || true
    seen=$(query "select base_url from remote_server limit 1;" 2>/dev/null || true)
    if [[ "$seen" == "$want_url" ]]; then
      note "server address restored to $want_url"
    else
      printf '    \033[31mcould not restore the server address\033[0m\n' >&2
      printf '    it is now %s; set it back to %s by hand\n' \
        "${seen:-unreadable}" "$want_url" >&2
      failed=1
    fi
  fi

  if [[ -n "$want_airplane" ]]; then
    airplane_mode "$want_airplane" >/dev/null 2>&1 || true
    seen=$(airplane_mode_state 2>/dev/null || true)
    if [[ "$seen" == "$want_airplane" ]]; then
      note "airplane mode back to $want_airplane"
    else
      printf '    \033[31mcould not put airplane mode back to %s\033[0m\n' \
        "$want_airplane" >&2
      printf '    it is %s; set it by hand\n' "${seen:-unreadable}" >&2
      failed=1
    fi
  fi

  return "$failed"
}

# Picks a downloaded book to open: the most recently read one, because
# it is the likeliest to have sync state worth exercising. Sets
# BOOK_URL and BOOK_LOCAL_URI.
# shellcheck disable=SC2034 # both are read by the sourcing scenario
pick_downloaded_book() {
  local row
  row=$(query "select url || char(9) || local_uri from books \
where local_uri is not null and local_uri != '' \
order by coalesce(last_opened_at, 0) desc limit 1;")
  [[ -n "$row" ]] || skip "no downloaded book on this device to open"
  BOOK_URL="${row%%	*}"
  BOOK_LOCAL_URI="${row#*	}"
}

# Opens a book and returns how long the reader took to become ready, in
# milliseconds, or the string "timeout".
#
# The marker is books.last_opened_at, which the reader writes one line
# before it shows the page. It is the last thing that happens on the
# opening path, so it is the honest end of "the book opened" -- and it
# lives in the database, so it can be read without instrumenting the
# app or scraping a screen.
time_book_open() {
  local url="$1" local_uri="$2" cap_ms="${3:-60000}"
  local before t0 elapsed now quoted
  quoted=$(sql_quote "$url")

  adb shell "am force-stop $APP_ID" >/dev/null
  before=$(query "select coalesce(last_opened_at, 0) from books where url = $quoted;")
  t0=$(device_now_ms)
  adb shell "am start -n $READER_ACTIVITY \
-e url $(shell_quote "$local_uri") -e id $(shell_quote "$url")" >/dev/null

  while :; do
    local stamp
    stamp=$(query "select coalesce(last_opened_at, 0) from books where url = $quoted;")
    if [[ -n "$stamp" && "$stamp" -gt "$before" ]]; then
      elapsed=$((stamp - t0))
      # A clock that ran backwards means the marker was not ours.
      ((elapsed >= 0)) || elapsed=0
      printf '%s\n' "$elapsed"
      return 0
    fi
    now=$(device_now_ms)
    if ((now - t0 > cap_ms)); then
      printf 'timeout\n'
      return 0
    fi
    sleep 1
  done
}

# Asserts an open finished inside a budget, and says by how much it did
# or did not.
expect_open_under() {
  local label="$1" measured="$2" budget_ms="$3"
  if [[ "$measured" == "timeout" ]]; then
    bad "$label: the book never opened (over the ${budget_ms}ms budget)"
  elif ((measured <= budget_ms)); then
    ok "$label: opened in ${measured}ms (budget ${budget_ms}ms)"
  else
    bad "$label: opened in ${measured}ms, over the ${budget_ms}ms budget"
  fi
}

# --- driving the app's own screens -------------------------------------
#
# Everything above reads the database. These few drive the interface,
# for the one thing the database cannot be asked: whether a reader can
# actually get there. Signing into a server is that -- the credentials
# are sealed by the Android keystore, so a row cannot be written from
# here, and a scenario that skipped the sign-in would be testing a state
# no reader can reach.
#
# Compose publishes no resource ids, so elements are found by the text
# on them. That is the same handle a person uses, which makes these
# brittle in exactly the way the interface is: if the label moved, the
# reader is lost too and the scenario should say so.

readonly UI_DUMP="/sdcard/liseur-ui.xml"

# The accessibility tree of whatever is on screen, as XML on stdout.
ui_dump() {
  adb shell "uiautomator dump $UI_DUMP >/dev/null 2>&1" >/dev/null || return 1
  adb shell "cat $UI_DUMP" | tr -d '\r'
}

# The centre of the first node whose text or description matches an
# extended regular expression, as "X Y". Empty if nothing matched.
#
# The dump is one long line, so nodes are split apart before matching --
# otherwise a pattern would match one node's text and take another
# node's bounds.
#
# awk reads to the end rather than stopping at the first match, and the
# first line is taken here instead. Quitting early closes the pipe on
# whatever is still writing into it, and under `set -o pipefail` that
# SIGPIPE fails the whole pipeline -- which, with `set -e`, ends the
# scenario at its first successful lookup.
ui_find() {
  local pattern="$1" found
  found=$(ui_dump | tr '<' '\n' | awk -v pat="$pattern" '
    /^node / {
      text = ""; desc = ""; bounds = ""
      if (match($0, /text="[^"]*"/))
        text = substr($0, RSTART + 6, RLENGTH - 7)
      if (match($0, /content-desc="[^"]*"/))
        desc = substr($0, RSTART + 14, RLENGTH - 15)
      if (match($0, /bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"/))
        bounds = substr($0, RSTART + 8, RLENGTH - 9)
      if (bounds == "") next
      if (text !~ pat && desc !~ pat) next
      split(bounds, p, /[^0-9]+/)
      # p[1] is empty: the split leading the string. Corners are 2..5.
      printf "%d %d\n", (p[2] + p[4]) / 2, (p[3] + p[5]) / 2
    }')
  printf '%s\n' "${found%%$'\n'*}"
}

# Waits for something matching a pattern to appear, printing where it is.
ui_await() {
  local pattern="$1" timeout_s="${2:-20}" waited=0 at
  while ((waited < timeout_s)); do
    at=$(ui_find "$pattern")
    if [[ -n "$at" ]]; then
      printf '%s\n' "$at"
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

# Taps whatever matches, once it is there. Fails the scenario if it
# never arrives, since every later step assumed it would.
ui_tap() {
  local pattern="$1" label="${2:-$1}" at
  at=$(ui_await "$pattern" "${3:-20}") || {
    bad "could not find $label on screen"
    return 1
  }
  # shellcheck disable=SC2086 # two words on purpose: x and y
  adb shell input tap $at >/dev/null
  sleep 1
}

# Types into the field that currently has focus.
#
# `input text` takes a single argument and reads %s as a space, so
# anything reader-supplied is quoted for the device's shell first.
ui_type() {
  adb shell "input text $(shell_quote "${1// /%s}")" >/dev/null
  sleep 1
}

# Puts the keyboard away by telling the field being typed into that it
# is finished.
#
# It has to go. An open keyboard covers the bottom of the screen while
# leaving every element it hides in the accessibility tree at its
# ordinary place, so a tap aimed at the button below the fold lands on a
# letter key instead and types a character into whichever field still
# has focus -- corrupting the very password the scenario is about to
# submit, and then reporting that the server rejected it.
#
# Enter is the way to do it. Every last field in these forms carries
# ImeAction.Done, so this is the same "done" the reader presses, and it
# closes the keyboard without submitting anything. Back would also close
# it, but the same key pops the screen when the keyboard is already
# gone, and the input method reports itself as showing for a moment
# after it has left -- so a press decided on that reading is a coin toss
# whose losing side walks out of the form and then out of the app.
#
# Switching the input methods off with `ime disable` is not the answer
# either: Android will not be left with none, and quietly brings one
# back.
ui_done() {
  adb shell input keyevent 66 >/dev/null
  sleep 1
}

# Scrolls the screen up by one swipe, inside whatever is scrollable.
#
# The swipe has to begin *within* the scrolling container: a form's
# fields commonly reach further down the screen than the container
# does, and a drag starting below it moves nothing at all -- silently,
# which is the whole difficulty in noticing it.
ui_scroll_up() {
  local box from to
  # Every match, then the first line in bash: `head -1` would close the
  # pipe on awk mid-write, and under `pipefail` that SIGPIPE fails the
  # assignment and takes the scenario with it. Only on a screen with a
  # second scrollable node, which is why it would wait for a bad day.
  box=$(ui_dump | tr '<' '\n' | awk '
    /scrollable="true"/ {
      if (match($0, /bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"/))
        print substr($0, RSTART + 8, RLENGTH - 9)
    }')
  box=${box%%$'\n'*}
  [[ -n "$box" ]] || return 1
  local p
  # shellcheck disable=SC2206 # deliberate split on the bounds' punctuation
  p=(${box//[^0-9]/ })
  from=$((p[1] + (p[3] - p[1]) * 4 / 5))
  to=$((p[1] + (p[3] - p[1]) / 5))
  adb shell input swipe $(((p[0] + p[2]) / 2)) "$from" $(((p[0] + p[2]) / 2)) "$to" 400 >/dev/null
  sleep 1
}

# The bottom of whatever is scrolling, or the bottom of the screen.
#
# A node reported outside it is not on screen, whatever bounds it
# claims, and tapping where it says it is hits whatever is really drawn
# there instead.
ui_scroll_bottom() {
  local box p
  # First line in bash rather than `head -1`, as in `ui_scroll_up`.
  box=$(ui_dump | tr '<' '\n' | awk '
    /scrollable="true"/ {
      if (match($0, /bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"/))
        print substr($0, RSTART + 8, RLENGTH - 9)
    }')
  box=${box%%$'\n'*}
  if [[ -z "$box" ]]; then
    printf '%s\n' 999999
    return 0
  fi
  # shellcheck disable=SC2206 # deliberate split on the bounds' punctuation
  p=(${box//[^0-9]/ })
  printf '%s\n' "${p[3]}"
}

# Scrolls until something matching a pattern is really on screen, then
# taps it.
#
# A form long enough to need this is the ordinary case on a phone: the
# button that submits it sits below the fold. A match outside the
# scrolling area is not taken -- it is off screen, and tapping where it
# claims to be hits whatever is actually drawn there.
ui_tap_below() {
  local pattern="$1" label="${2:-$1}" tries="${3:-8}" at i floor
  for ((i = 0; i < tries; i++)); do
    at=$(ui_find "$pattern")
    floor=$(ui_scroll_bottom)
    if [[ -n "$at" ]] && ((${at##* } < floor)); then
      # shellcheck disable=SC2086 # two words on purpose: x and y
      adb shell input tap $at >/dev/null
      sleep 1
      return 0
    fi
    ui_scroll_up || break
  done
  bad "could not reach $label, even after scrolling"
  return 1
}

# Moves focus to the next field.
#
# Cheaper and steadier than aiming at each field in turn: one tap
# establishes where focus is, and Tab walks the rest in the order the
# form declares them.
ui_tab() {
  adb shell input keyevent 61 >/dev/null
  sleep 1
}
