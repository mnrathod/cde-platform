#!/usr/bin/env bash
# Copies the built browser application into this repository's Docker build
# context, so the image can serve it.
#
# Why a script rather than a COPY: the Angular sources live in a sibling
# repository, outside this build context, and Docker cannot read above the
# context root. Something has to bring the build across, and doing it here —
# once, with the checks below — beats repeating a `cp -r` in the Jenkinsfile,
# the compose file and whatever a developer types locally.
#
# Nothing here is required to build a working API image. Skip it and `web/`
# stays empty; the image builds, starts, and serves no application, which is
# exactly what it did before ADR 15. The failure this guards against is the
# other one: staging something that is not the bundle, which produces an
# image that starts and 404s every page.
#
#   scripts/stage-browser-app.sh [source-directory]
#
# Default source is ../cde-angular/dist/cde-web/browser — the Angular
# application builder's output, which is one level below dist/cde-web.
set -euo pipefail

cd "$(dirname "$0")/.."

source_directory="${1:-../cde-angular/dist/cde-web/browser}"
destination="web"

if [[ ! -d "$source_directory" ]]; then
    echo "No build at $source_directory." >&2
    echo "Build it first (cd ../cde-angular && npx ng build --configuration production)," >&2
    echo "or pass the bundle directory as the first argument." >&2
    exit 1
fi

# The directory above the bundle also exists and also looks plausible, and
# staging it produces an image that starts cleanly and answers every page with
# a 404. Checking for the document we actually serve turns that into a failure
# here, with the path in it.
if [[ ! -f "$source_directory/index.html" ]]; then
    echo "$source_directory has no index.html in it." >&2
    echo "The bundle is the directory containing index.html — for the Angular" >&2
    echo "application builder that is dist/<project>/browser, one level below" >&2
    echo "the output path. That is the usual mistake here." >&2
    exit 1
fi

# Replace rather than merge. A stale chunk left from an earlier build is
# unreferenced by the new index.html and therefore invisible, until a cached
# page asks for one and gets a version of the application nobody shipped.
#
# .gitkeep survives, and is recreated if it has already been lost. It is the
# only tracked file here, and `COPY web` in the Dockerfile needs the directory
# to exist in a checkout where nothing has been staged — deleting it turns a
# clean-checkout image build into a failure, days later and somewhere else.
mkdir -p "$destination"
find "$destination" -mindepth 1 -maxdepth 1 ! -name .gitkeep -exec rm -rf {} +
[[ -f "$destination/.gitkeep" ]] || printf '%s\n' \
    "Staged browser application. Build output; not committed. See ADR 15." \
    > "$destination/.gitkeep"
cp -R "$source_directory/." "$destination/"

printf 'Staged %s into %s/ (%s files, %s)\n' \
    "$source_directory" "$destination" \
    "$(find "$destination" -type f | wc -l | tr -d ' ')" \
    "$(du -sh "$destination" | cut -f1)"
