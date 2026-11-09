# shellcheck shell=bash
# Callers provide APP_ID, MAIN_ACTIVITY, work, width, height and a serial-scoped adb().
# shellcheck disable=SC2154

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

step() { printf '\n== %s\n' "$*"; }
note() { printf '   %s\n' "$*"; }

# ---------------------------------------------------------------- the UI

# The current window, as XML. uiautomator occasionally comes back empty
# while a screen is still settling, so an empty dump is worth retrying.
dump_ui() {
  local i
  for i in 1 2 3; do
    if adb shell uiautomator dump /sdcard/liseur-ui.xml >/dev/null 2>&1 &&
      adb shell cat /sdcard/liseur-ui.xml >"$work/ui.xml" 2>/dev/null &&
      [[ -s "$work/ui.xml" ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# Centre of the first node whose text or content-desc matches, printed as
# "x y". Matching is case-insensitive and on a prefix, so a label that
# gained a suffix still resolves.
#
#   find_node <needle> [exact]
find_node() {
  python3 - "$work/ui.xml" "$1" "${2:-prefix}" <<'PY'
import re, sys, xml.etree.ElementTree as ET

path, needle, mode = sys.argv[1], sys.argv[2].lower(), sys.argv[3]
try:
    root = ET.parse(path).getroot()
except ET.ParseError:
    sys.exit(1)

def hit(value):
    value = (value or "").strip().lower()
    if not value:
        return False
    if mode == "exact":
        return value == needle
    if mode == "contains":
        return needle in value
    return value.startswith(needle)

for node in root.iter("node"):
    if hit(node.get("text")) or hit(node.get("content-desc")):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = (int(g) for g in m.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PY
}

# Wait until something is on screen. Returns its centre.
#
#   wait_for <needle> [seconds] [exact]
wait_for() {
  local needle=$1 limit=${2:-25} mode=${3:-prefix} waited=0 found
  while ((waited < limit)); do
    if dump_ui && found=$(find_node "$needle" "$mode"); then
      printf '%s\n' "$found"
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

# Tap what a thing says. The tap lands on the node's centre, which for a
# Compose icon button is the icon itself.
tap_on() {
  local needle=$1 mode=${2:-prefix} at
  at=$(wait_for "$needle" 25 "$mode") ||
    die "never found \"$needle\" on screen (the layout may have changed)"
  # shellcheck disable=SC2086
  adb shell input tap $at
  sleep 2
}

# Same, but a missing target is not fatal: for things that are only there
# sometimes, like a permission dialog.
tap_if_there() {
  local needle=$1 mode=${2:-prefix} at
  if dump_ui && at=$(find_node "$needle" "$mode"); then
    # shellcheck disable=SC2086
    adb shell input tap $at
    sleep 2
    return 0
  fi
  return 1
}

on_screen() { dump_ui && find_node "$1" "${2:-prefix}" >/dev/null; }

back() { adb shell input keyevent KEYCODE_BACK; }

# ------------------------------------------------------------- the app

open_library() {
  adb shell am force-stop "$APP_ID"
  # A picker left open from an earlier run steals the launch.
  adb shell am force-stop com.google.android.documentsui >/dev/null 2>&1 || true
  adb shell am start -n "$MAIN_ACTIVITY" >/dev/null
  sleep 6
  # The app can open on the book you were reading; step back to the shelf.
  if ! on_screen "Liseur" exact; then
    back
    sleep 3
    wait_for "Liseur" 20 exact >/dev/null || die "never reached the library"
  fi
}


# Settings now lives in the shelf's overflow menu rather than on the bar,
# so it has to be opened before it can be found.
open_settings() {
    on_screen "Settings" || tap_on "More" exact
    tap_on "Settings"
    wait_for "Theme" >/dev/null || sleep 2
}

# Whether a settings switch is on. The row carries the checked state but
# no text of its own, so this finds the label and then the switch row
# wrapped around it: prints "on" or "off", or nothing if there is no such
# row on screen.
#
#   switch_state <label>
switch_state() {
    python3 - "$work/ui.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET

path, needle = sys.argv[1], sys.argv[2].lower()

def box(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    return tuple(int(g) for g in m.groups()) if m else None

try:
    nodes = list(ET.parse(path).getroot().iter("node"))
except ET.ParseError:
    sys.exit(1)

label = next(
    (n for n in nodes if (n.get("text") or "").strip().lower().startswith(needle)),
    None,
)
if label is None or (lb := box(label)) is None:
    sys.exit(1)

# The smallest checkable box that contains the label is its own row and
# not, say, a scroll container that happens to enclose several.
rows = [
    (b, n) for n in nodes if n.get("checkable") == "true"
    if (b := box(n)) and b[0] <= lb[0] and b[1] <= lb[1] and b[2] >= lb[2] and b[3] >= lb[3]
]
if not rows:
    sys.exit(1)
_, row = min(rows, key=lambda r: (r[0][2] - r[0][0]) * (r[0][3] - r[0][1]))
print("on" if row.get("checked") == "true" else "off")
PY
}

# Put a settings switch in a known position. Tapping blind would toggle
# whatever the last run left behind, which is how a screenshot set ends
# up half in one palette and half in another.
#
#   set_switch <label> <on|off>
set_switch() {
    local label=$1 want=$2 now
    wait_for "$label" 15 >/dev/null || die "no \"$label\" switch on screen"
    now=$(switch_state "$label") || die "could not read the \"$label\" switch"
    if [[ $now == "$want" ]]; then
        note "$label already $want"
        return 0
    fi
    tap_on "$label"
    sleep 2
    dump_ui
    [[ $(switch_state "$label") == "$want" ]] ||
        die "could not turn \"$label\" $want"
    note "$label turned $want"
}

# The dictionary switch sits on the reading and navigation screen, not
# on Settings itself.
open_dictionary_settings() {
    scroll_to "$READING_SETTINGS" exact || die "no \"$READING_SETTINGS\" row in Settings"
    tap_on "$READING_SETTINGS" exact
    scroll_to "$DICTIONARY_SWITCH" ||
        die "no \"$DICTIONARY_SWITCH\" switch under \"$READING_SETTINGS\""
}

# Which service read aloud speaks with, as its own settings screen names
# it. The screen shows only the chosen one, so whichever label is there
# is the answer.
voice_service() {
    local service
    dump_ui || return 1
    for service in "$DEVICE_VOICES" "Speech server (OpenAI-compatible)" "Gemini"; do
        if find_node "$service" exact >/dev/null; then
            printf '%s\n' "$service"
            return 0
        fi
    done
    return 1
}

# Pick a voice service, from read aloud's settings screen.
#
#   choose_voice_service <label>
choose_voice_service() {
    local want=$1
    [[ $(voice_service) == "$want" ]] && return 0
    tap_on "Change the voice service" exact
    wait_for "$want" 15 exact >/dev/null || die "no \"$want\" in the voice services"
    tap_on "$want" exact
    sleep 2
    [[ $(voice_service) == "$want" ]] || die "could not switch read aloud to \"$want\""
    note "read aloud now speaks with $want"
}

# Bring something below the fold into view, on any screen that scrolls
# with a swipe. `wait_for` only ever looks at what is already on screen,
# so a switch further down a long form is a switch it never finds.
#
#   scroll_to <needle> [exact|prefix|contains]
scroll_to() {
    local needle=$1 mode=${2:-prefix} i
    for i in 1 2 3 4 5 6 7 8; do
        dump_ui && find_node "$needle" "$mode" >/dev/null && return 0
        adb shell input swipe "$(x 0.5)" "$(y 0.75)" "$(x 0.5)" "$(y 0.35)" 400
        sleep 1
    done
    dump_ui && find_node "$needle" "$mode" >/dev/null
}

# Tap something on the shelf, scrolling down to it if it is below the
# fold. Only the shelf scrolls this way; a swipe in the reader turns a
# page instead.
tap_on_shelf() {
    local needle=$1 mode=${2:-prefix} i at
    for i in 1 2 3 4 5 6; do
        if dump_ui && at=$(find_node "$needle" "$mode"); then
            # shellcheck disable=SC2086
            adb shell input tap $at
            sleep 2
            return 0
        fi
        adb shell input swipe "$(x 0.5)" "$(y 0.75)" "$(x 0.5)" "$(y 0.3)" 400
        sleep 2
    done
    die "never found \"$needle\" on the shelf"
}

# Back to the top of the shelf, so a capture starts where the last one did.
shelf_to_top() {
    local i
    for i in 1 2 3 4 5 6; do
        adb shell input swipe "$(x 0.5)" "$(y 0.3)" "$(x 0.5)" "$(y 0.8)" 300
        sleep 1
    done
    sleep 1
}


x() { printf '%.0f' "$(awk "BEGIN { print $width * $1 }")"; }
y() { printf '%.0f' "$(awk "BEGIN { print $height * $1 }")"; }
# A fraction of the way down from another fraction, in pixels.
down() { y "$(awk "BEGIN { print $1 + $2 }")"; }
tap() { adb shell input tap "$(x "$1")" "$(y "$2")"; }


# ------------------------------------------------------------- the app

open_first_book() {
    local title=$1
    tap_on_shelf "$title"
    reader_chrome_up
    # The reader takes a while to lay the publication out.
    wait_for "Contents" 40 >/dev/null || sleep 8
    sleep 3
}

# Forward, a page at a time. The chrome has to be down first: with it up,
# a tap on the page edge only puts it away again, and the book stays
# exactly where it was.
turn_pages() {
    local count=$1 direction=${2:-forward} edge=0.85 i
    [[ $direction == back ]] && edge=0.15
    reader_chrome_down
    for ((i = 0; i < count; i++)); do
        tap "$edge" 0.5
        sleep 1
    done
    sleep 2
}

# The chrome comes and goes with a tap in the middle of the page.
show_chrome() {
    tap 0.5 0.5
    sleep 2
}

# A tap that lands on a highlight opens its popup instead of the chrome,
# and the middle of the page is where seeded marks tend to sit. A tap
# elsewhere in the centre box puts the popup away and raises the chrome
# in one go; outside that box a tap turns the page. While the shelf is
# still showing, the book is only opening, and tapping again there would
# open another one.
reader_chrome_up() {
    local spot
    on_screen "Contents" && return 0
    show_chrome
    for spot in 0.65 0.35; do
        on_screen "Contents" && return 0
        on_screen "Filter" exact && return 0
        find_popup >/dev/null || return 0
        tap 0.5 "$spot"
        sleep 2
    done
}

reader_chrome_down() {
    if on_screen "Contents"; then
        show_chrome
    fi
}

# Pick a reading theme by name in the typography sheet, rather than
# trusting whatever the device was left on: that is what stopped the
# light set from quietly coming out dark on a rerun.
choose_reading_theme() {
    local theme=$1
    # Opening the sheet is not always instant, and a dump taken while the
    # reader is still settling can come back without it. Rather than tap
    # where the swatch is meant to be, put the sheet away and open it
    # again.
    for _ in 1 2 3; do
        reader_chrome_up
        if tap_if_there "Aa" exact && wait_for "$theme" 15 exact >/dev/null; then
            tap_on "$theme" exact
            sleep 2
            back
            sleep 2
            return 0
        fi
        back
        sleep 3
    done
    die "could not pick the $theme reading theme"
}


readonly POPUP_NOTE_DP=55
readonly POPUP_DEFINE_DP=103


dp() { printf '%.0f' "$(awk "BEGIN { print $1 * $density / 160 }")"; }

# A screencap decoded to pixels without a library: an emulator host may
# have no Pillow on it, and asking for one would put a pip install
# between a checkout and a screenshot. Reads the file named by the first
# argument and leaves out, width, height, channels and stride behind for
# whatever is appended to it.
readonly PNG_DECODER=$(
    cat <<'PYEOF'
import struct, sys, zlib

path = sys.argv[1]
data = open(path, "rb").read()
if data[:8] != b"\x89PNG\r\n\x1a\n":
    sys.exit(1)

pos, idat, width, height, colour = 8, b"", 0, 0, 0
while pos < len(data):
    length, typ = struct.unpack(">I4s", data[pos:pos + 8])
    chunk = data[pos + 8:pos + 8 + length]
    if typ == b"IHDR":
        width, height, _, colour = struct.unpack(">IIBB", chunk[:10])
    elif typ == b"IDAT":
        idat += chunk
    pos += 12 + length

raw = zlib.decompress(idat)
channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[colour]
stride = width * channels
out, prev, p = bytearray(), bytearray(stride), 0
for _ in range(height):
    f = raw[p]
    p += 1
    line = bytearray(raw[p:p + stride])
    p += stride
    if f == 1:
        for i in range(channels, stride):
            line[i] = (line[i] + line[i - channels]) & 255
    elif f == 2:
        for i in range(stride):
            line[i] = (line[i] + prev[i]) & 255
    elif f == 3:
        for i in range(stride):
            a = line[i - channels] if i >= channels else 0
            line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
    elif f == 4:
        for i in range(stride):
            a = line[i - channels] if i >= channels else 0
            b = prev[i]
            c = prev[i - channels] if i >= channels else 0
            pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
            line[i] = (line[i] + (a if pa <= pb and pa <= pc else (b if pb <= pc else c))) & 255
    out += line
    prev = line
PYEOF
)

# Find the yellow 30dp disc, rejecting page highlights by their dimensions.
find_popup() {
    adb exec-out screencap -p > "$work/screen.png" 2>/dev/null || return 1
    python3 -c "$PNG_DECODER
$(
        cat <<'PYEOF'
size = float(sys.argv[2])
step = 3
cells = set()
for y in range(0, height, step):
    for x in range(0, width, step):
        i = y * stride + x * channels
        if all(abs(out[i + n] - c) < 12 for n, c in enumerate((255, 213, 79))):
            cells.add((x, y))
found = []
while cells:
    first = cells.pop()
    group, stack = [first], [first]
    while stack:
        x, y = stack.pop()
        for dx, dy in ((step, 0), (-step, 0), (0, step), (0, -step)):
            near = (x + dx, y + dy)
            if near in cells:
                cells.remove(near)
                group.append(near)
                stack.append(near)
    xs, ys = zip(*group)
    w, h = max(xs) - min(xs) + step, max(ys) - min(ys) + step
    if 0.8 * size < w < 1.15 * size and 0.8 * size < h < 1.15 * size and len(group) * step ** 2 > size ** 2 * 0.5:
        found.append((round(sum(xs) / len(xs)), round(sum(ys) / len(ys))))
if len(found) != 1:
    sys.exit(1)
print(*found[0])
PYEOF
    )" "$work/screen.png" "$(dp 30)"
}


# A long press: a swipe that barely moves, held long enough for the page
# to treat it as a word being picked rather than a tap.
hold() {
    local px py
    px=$(x "$1")
    py=$(y "$2")
    adb shell input swipe "$px" "$py" "$((px + 2))" "$((py + 2))" 1500
    sleep 3
}

# Press a word until the selection popup appears, trying a few places
# down the page: a press that lands in a margin selects nothing. An x
# across the column can be given to land on a different word.
select_a_word() {
    local across=${1:-0.45}
    local spot
    for spot in 0.45 0.55 0.35 0.65; do
        reader_chrome_down
        hold "$across" "$spot"
        if find_popup >/dev/null; then
            return 0
        fi
    done
    return 1
}


# Tap something in the selection popup, given where the first colour is.
tap_in_popup() {
    local what=$1 at chip_x chip_y
    at=$(find_popup) || return 1
    chip_x=${at% *}
    chip_y=${at#* }
    case $what in
        highlight) adb shell input tap "$chip_x" "$chip_y" ;;
        note) adb shell input tap "$((chip_x + $(dp $POPUP_NOTE_DP)))" "$chip_y" ;;
        define) adb shell input tap "$((chip_x + $(dp $POPUP_DEFINE_DP)))" "$chip_y" ;;
        read_aloud)
            adb shell input tap "$((chip_x + $(dp "${2:-247}")))" "$chip_y"
            ;;
    esac
    sleep 2
}


start_visible_read_aloud() {
    local across=${1:-0.45} speech_offset=247
    reader_chrome_up
    tap_on More exact
    if on_screen "Translate into"; then
        speech_offset=295
    fi
    back
    reader_chrome_down
    select_a_word "$across" || return 1
    tap_in_popup read_aloud "$speech_offset"
}
