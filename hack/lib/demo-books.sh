# shellcheck shell=bash
#
# Shared demo-book seeding: download a real public-domain shelf, push it
# to a device, and grant it to Liseur through the actual SAF picker,
# because there is no other way to hand a SAF tree to an app.
#
# Sourced by hack/screenshots and hack/reset-books. The caller is expected
# to have already set: serial, an adb() wrapper scoped to that serial, a
# scratch "work" directory (with its own cleanup trap), and APP_ID,
# MAIN_ACTIVITY, DEVICE_BOOKS.
# shellcheck disable=SC2154 # work/adb come from the sourcing script

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly CACHE_DIR="$REPO_ROOT/tmp/books"

# In a non-main worktree, symlink the book cache from the main worktree
# so downloads are shared and not duplicated.
_main_git=$(git -C "$REPO_ROOT" rev-parse --path-format=absolute --git-common-dir 2>/dev/null)
_wt_git=$(git -C "$REPO_ROOT" rev-parse --path-format=absolute --git-dir 2>/dev/null)
if [[ -n "$_main_git" && "$_main_git" != "$_wt_git" && ! -L "$CACHE_DIR" ]]; then
  _main_books="${_main_git%/.git}/tmp/books"
  mkdir -p "$_main_books" "$(dirname "$CACHE_DIR")"
  rm -rf "$CACHE_DIR"
  ln -s "$_main_books" "$CACHE_DIR"
fi
unset _main_git _wt_git _main_books

# The shelf, in the order it is downloaded. Standard Ebooks serves the
# file itself only with ?source=download; without it you get the "your
# download has started" page, saved as a .epub that is not one.
readonly -a DEMO_BOOKS=(
  "herman-melville/moby-dick"
  "robert-louis-stevenson/treasure-island"
  "oscar-wilde/the-picture-of-dorian-gray"
  "mary-shelley/frankenstein"
  "mark-twain/the-adventures-of-huckleberry-finn"
  "jane-austen/pride-and-prejudice"
  "bram-stoker/dracula"
  "charlotte-bronte/jane-eyre"
  "h-g-wells/the-time-machine"
  # Two real series, for the series screens. Standard Ebooks records
  # them the way an EPUB is supposed to, so nothing here is faked: the
  # numbers on the shelf are the ones in the files.
  #
  # The gaps are deliberate. Sherlock Holmes is here as 1, 2, 3 and 5,
  # and the Martian books as 1 and 3, so the series screen has
  # something real to say about the volumes that are missing between
  # the ones you own -- which is the whole point of showing it.
  "arthur-conan-doyle/a-study-in-scarlet"
  "arthur-conan-doyle/the-sign-of-the-four"
  "arthur-conan-doyle/the-adventures-of-sherlock-holmes"
  "arthur-conan-doyle/the-hound-of-the-baskervilles"
  "edgar-rice-burroughs/a-princess-of-mars"
  "edgar-rice-burroughs/the-warlord-of-mars"
)

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

on_screen() { dump_ui && find_node "$1" >/dev/null; }

back() { adb shell input keyevent KEYCODE_BACK; }

# ------------------------------------------------------------- the app

open_library() {
  adb shell am force-stop "$APP_ID"
  # A picker left open from an earlier run steals the launch.
  adb shell am force-stop com.google.android.documentsui >/dev/null 2>&1 || true
  adb shell am start -n "$MAIN_ACTIVITY" >/dev/null
  sleep 6
  # The app can open on the book you were reading; step back to the shelf.
  if ! wait_for "Liseur" 20 exact >/dev/null; then
    back
    sleep 3
    wait_for "Liseur" 20 exact >/dev/null || die "never reached the library"
  fi
}

# --------------------------------------------------------------- setup

fetch_books() {
  mkdir -p "$CACHE_DIR"
  local slug file url
  for slug in "${DEMO_BOOKS[@]}"; do
    file="$CACHE_DIR/${slug//\//_}.epub"
    if [[ -s "$file" ]]; then
      note "have ${slug#*/}"
      continue
    fi
    url="https://standardebooks.org/ebooks/$slug/downloads/${slug%%/*}_${slug#*/}.epub?source=download"
    note "fetching ${slug#*/}"
    curl -fsSL --retry 2 -o "$file.part" "$url" ||
      die "could not download $slug from standardebooks.org"
    # A download that turned into the interstitial page is not a book.
    if ! head -c 2 "$file.part" | grep -q 'PK'; then
      rm -f "$file.part"
      die "standardebooks.org served a page rather than $slug"
    fi
    mv "$file.part" "$file"
  done

  # And one book nobody published: a reproducer for the notes in issue #152,
  # which the shelf above cannot provide because Standard Ebooks mark their
  # notes up correctly. Rebuilt every time, so a change to the fixture is on
  # the device the next time the shelf is seeded.
  note "building the notes fixture"
  "$REPO_ROOT/hack/make-notes-book" "$CACHE_DIR/liseur_notes-fixture.epub" >/dev/null ||
    die "could not build the notes fixture book"
}

# What the shelf should hold once it has been scanned: the downloaded books
# and the fixture built alongside them.
expected_books() { echo $((${#DEMO_BOOKS[@]} + 1)); }

push_books() {
  adb shell mkdir -p "$DEVICE_BOOKS"
  local file
  for file in "$CACHE_DIR"/*.epub; do
    adb push "$file" "$DEVICE_BOOKS/" >/dev/null
  done
  note "$(expected_books) books in $DEVICE_BOOKS"
}

# Grant the folder through the real picker, because there is no other way
# to hand a SAF tree to an app.
grant_folder() {
  if adb shell "run-as $APP_ID sqlite3 /data/data/$APP_ID/databases/liseur.db \
        'select count(*) from library_folders;'" 2>/dev/null | grep -qE '^[1-9]'; then
    note "a library folder is already granted"
    return 0
  fi
  open_library
  tap_on "Add books"
  tap_on "Add a folder"
  sleep 3
  # The picker reopens wherever it was left, so it may already be
  # inside the shelf. Tapping the folder again from in there hits the
  # breadcrumb and walks back out, and the grant then lands on the
  # wrong directory -- or on nothing at all.
  if ! on_screen "Files in Books"; then
    # The picker usually opens on the device's own storage already.
    # When it does not, go out to the roots and come back in by the
    # model name.
    if ! on_screen "Books"; then
      tap_if_there "Show roots" || true
      local model
      model=$(adb shell getprop ro.product.model | tr -d '\r')
      tap_on "$model"
    fi
    tap_on "Books"
  fi
  tap_on "USE THIS FOLDER"
  # The permission dialog can take a moment to arrive, and tapping
  # where it is about to be does nothing at all.
  #
  # Match the button exactly: the dialog above it opens with "Allow
  # Liseur to access...", so a prefix match taps the sentence instead,
  # which does nothing and loses the folder.
  tap_on "ALLOW" exact
  note "waiting for the shelf to fill"
  local waited=0 count=0 wanted
  wanted=$(expected_books)
  while ((waited < 90)); do
    count=$(adb shell "run-as $APP_ID sqlite3 /data/data/$APP_ID/databases/liseur.db \
            'select count(*) from books;'" 2>/dev/null | tr -d '\r')
    ((count >= wanted)) && break
    sleep 5
    waited=$((waited + 5))
  done
  note "$count books on the shelf"
  ((count > 0)) ||
    die "the folder was not granted; the picker layout may have changed"
}
