#!/usr/bin/env bash
# Exercises stage-browser-app.sh's guards against synthetic directories.
#
# The script itself is four commands; what is worth testing is the two things
# it refuses and the one thing it cleans up, because each of those failing
# open produces an image that builds, starts, and is wrong in a way nobody
# sees until a page is requested.
#
# Runs standalone — no Gradle, no network, no Angular build. Everything
# happens in a temporary directory.
set -uo pipefail

cd "$(dirname "$0")/.."
script="$PWD/scripts/stage-browser-app.sh"

failures=0
fail() { printf '  FAIL  %s\n' "$*" >&2; failures=$((failures + 1)); }
pass() { printf '  ok    %s\n' "$*"; }

workspace=$(mktemp -d)
# Staging writes to ./web relative to the repository root, so every case runs
# against a throwaway copy of that layout rather than the real one.
sandbox="$workspace/repo"
mkdir -p "$sandbox/scripts" "$sandbox/web"
touch "$sandbox/web/.gitkeep"
cp "$script" "$sandbox/scripts/"
trap 'rm -rf "$workspace"' EXIT

echo "stage-browser-app.sh"

# ── 1. A source that is not there ─────────────────────────────────────
# The common case on a fresh agent: someone ran the staging step without
# building the frontend first. It must stop, not stage an empty directory.
output=$(cd "$sandbox" && ./scripts/stage-browser-app.sh "$workspace/absent" 2>&1)
if [[ $? -eq 0 ]]; then
    fail "staged a source directory that does not exist"
elif [[ "$output" != *"ng build"* ]]; then
    fail "refused a missing source without saying how to produce one: $output"
else
    pass "refuses a source directory that is not there, and names the build command"
fi

# ── 2. The directory one level above the bundle ───────────────────────
# dist/cde-web exists, looks plausible, and contains browser/. Staging it
# produces an image that starts cleanly and answers every page with a 404 —
# the failure this check exists for, because it reads as a routing fault.
mkdir -p "$workspace/dist/cde-web/browser"
echo '<body><app-root></app-root></body>' > "$workspace/dist/cde-web/browser/index.html"
output=$(cd "$sandbox" && ./scripts/stage-browser-app.sh "$workspace/dist/cde-web" 2>&1)
if [[ $? -eq 0 ]]; then
    fail "staged a directory with no index.html in it"
elif [[ "$output" != *"index.html"* ]]; then
    fail "refused the wrong level without naming what was missing: $output"
else
    pass "refuses the directory above the bundle, and names index.html"
fi

# ── 3. The bundle itself ──────────────────────────────────────────────
bundle="$workspace/dist/cde-web/browser"
echo 'console.log(1)' > "$bundle/main-AAAA.js"
if (cd "$sandbox" && ./scripts/stage-browser-app.sh "$bundle" > /dev/null 2>&1) \
   && [[ -f "$sandbox/web/index.html" && -f "$sandbox/web/main-AAAA.js" ]]; then
    pass "stages the bundle's contents, not the bundle directory itself"
else
    fail "did not stage the bundle"
fi

# ── 4. A second stage replaces rather than merges ─────────────────────
# A chunk left from an earlier build is unreferenced by the new index.html
# and therefore invisible — until a cached page asks for one and gets a
# version of the application nobody shipped.
rm "$bundle/main-AAAA.js"
echo 'console.log(2)' > "$bundle/main-BBBB.js"
(cd "$sandbox" && ./scripts/stage-browser-app.sh "$bundle" > /dev/null 2>&1)
if [[ -e "$sandbox/web/main-AAAA.js" ]]; then
    fail "left a chunk from the previous build in place"
else
    pass "replaces the staged bundle rather than merging into it"
fi

# ── 5. .gitkeep survives staging ──────────────────────────────────────
# It is the only tracked file in web/, and `COPY web` in the Dockerfile needs
# the directory to exist in a checkout where nothing has been staged. An
# earlier version of this script removed the whole directory, so staging
# deleted it — and the resulting failure is a clean-checkout image build
# breaking days later, with a deletion in someone's diff that looked like
# build noise.
if [[ -f "$sandbox/web/.gitkeep" ]]; then
    pass "leaves .gitkeep in place, so a clean checkout still builds an image"
else
    fail "staging deleted web/.gitkeep"
fi

# And it comes back if a previous run already removed it.
rm -f "$sandbox/web/.gitkeep"
(cd "$sandbox" && ./scripts/stage-browser-app.sh "$bundle" > /dev/null 2>&1)
if [[ -f "$sandbox/web/.gitkeep" ]]; then
    pass "restores .gitkeep when an earlier run had removed it"
else
    fail "did not restore a missing .gitkeep"
fi

echo
if [[ $failures -gt 0 ]]; then
    echo "$failures check(s) failed." >&2
    exit 1
fi
echo "All checks passed."
