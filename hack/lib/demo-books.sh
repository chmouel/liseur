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

# shellcheck source=hack/lib/device.sh
source "$(dirname "${BASH_SOURCE[0]}")/device.sh"

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
  "$REPO_ROOT/hack/reproducer/make-notes-book" "$CACHE_DIR/liseur_notes-fixture.epub" >/dev/null ||
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
