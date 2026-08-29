#!/usr/bin/env bash
# Fails if anything that should be pinned has become floating again.
#
# Pinning decays. Someone adds a stage, copies a `FROM ubuntu:24.04` from the
# internet, and the build is non-reproducible again with nothing in the diff
# that looks wrong. Each check below exists because that specific thing was
# actually unpinned in this repository before 2026-08-29.
#
# Runs standalone (no Gradle, no network) so it is cheap enough to sit early
# in the pipeline and in a pre-commit hook.
set -uo pipefail

cd "$(dirname "$0")/.."

failures=0
fail() { printf '  FAIL  %s\n' "$*" >&2; failures=$((failures + 1)); }
pass() { printf '  ok    %s\n' "$*"; }

echo "Pinning checks"

# ── 1. Container images carry a digest ────────────────────────────────
# A tag is a mutable pointer. `ubuntu:24.04` is a different filesystem this
# month than last, so "it built yesterday" says nothing about today.
unpinned=$(grep -rnE '^\s*(FROM|[-# ]*image:)\s+[^ #]*[a-z0-9]:[A-Za-z0-9._-]+\s*($|AS|#)' \
             --include=Dockerfile --include='*.yml' --include='*.yaml' . 2>/dev/null \
           | grep -v node_modules \
           | grep -v '@sha256:' \
           | grep -vE 'cde-platform:|cde-converter:' \
           | grep -vE '^\S+:\s*#') || true
if [[ -n "$unpinned" ]]; then
    fail "container image without @sha256 digest:"
    printf '%s\n' "$unpinned" | sed 's/^/          /' >&2
else
    pass "every third-party container image is digest-pinned"
fi

# ── 2. The converter's three stages share one Ubuntu digest ───────────
# The runtime stage copies python-builder's venv wholesale. Different Ubuntu
# digests can mean different Python minor versions, and every import fails at
# request time rather than at build time.
mapfile -t ubuntu_digests < <(grep -oE '^FROM ubuntu:[0-9.]+@sha256:[a-f0-9]+' converter/Dockerfile \
                              | sed 's/.*@//' | sort -u)
if (( ${#ubuntu_digests[@]} == 1 )); then
    pass "converter stages all use one Ubuntu digest"
else
    fail "converter/Dockerfile mixes ${#ubuntu_digests[@]} Ubuntu digests: ${ubuntu_digests[*]}"
fi

# ── 3. No :latest anywhere ────────────────────────────────────────────
latest=$(grep -rn ':latest' --include=Dockerfile --include='*.yml' --include='*.yaml' . 2>/dev/null \
         | grep -v node_modules | grep -v '^\S*:[0-9]*:\s*#') || true
if [[ -n "$latest" ]]; then
    fail ":latest tag present:"
    printf '%s\n' "$latest" | sed 's/^/          /' >&2
else
    pass "no :latest tags"
fi

# ── 4. Deployment tag matches what kustomize overrides it to ──────────
# deployment.yaml's tag is what a bare `kubectl apply -f` deploys. If it
# drifts from kustomization.yaml's newTag, the two paths ship different
# versions and only one of them is the one anybody tested.
kustomize_tag=$(grep -A2 'name: cde-platform' k8s/kustomization.yaml | grep newTag | tr -d ' "' | cut -d: -f2)
for image in cde-platform cde-converter; do
    deploy_tag=$(grep -oE "image: ${image}:[^ ]+" k8s/deployment.yaml | cut -d: -f3)
    if [[ "$deploy_tag" == "$kustomize_tag" ]]; then
        pass "$image tag $deploy_tag matches kustomization newTag"
    else
        fail "$image: deployment.yaml has '$deploy_tag', kustomization.yaml has '$kustomize_tag'"
    fi
done

# ── 5. Python requirements are fully pinned ───────────────────────────
loose=$(grep -vE '^\s*(#|$)' converter/requirements.txt | grep -vE '==' ) || true
if [[ -n "$loose" ]]; then
    fail "converter/requirements.txt has non-pinned entries: $loose"
else
    pass "converter/requirements.txt is fully pinned ($(grep -cE '==' converter/requirements.txt) packages)"
fi

# ── 6. No forbidden licence sneaking back in transitively ─────────────
# ezdxf[draw] used to pull PyMuPDF (AGPL-3.0, forbidden by §2.1 outright) and
# PySide6 (LGPL/GPL, never approved) into the shipped image, for code app.py
# never imports. Cheap to name them explicitly; the SCA scan is the real net.
banned=$(grep -iE '^(pymupdf|fitz|PySide6|shiboken6)==' converter/requirements.txt) || true
if [[ -n "$banned" ]]; then
    fail "forbidden-licence package back in requirements.txt: $banned"
else
    pass "no AGPL/GPL-licensed Python package in the closure"
fi
if grep -qE '^\s*ezdxf\[' converter/requirements.in; then
    fail "requirements.in uses an ezdxf extra; [draw] reintroduces PyMuPDF (AGPL)"
else
    pass "ezdxf declared without extras"
fi

# ── 7. Gradle lockfile is present and complete ────────────────────────
# Gradle's own LockMode.STRICT is the real enforcement — it fails the build
# when a resolved version is not in the lockfile. This is the cheap smoke
# test that runs without a JVM or the network, so it checks the file is
# whole rather than merely present: a truncated or hand-mangled lockfile
# would otherwise sail through on a substring match.
#
# MINIMUM_MODULES is a floor, not a target. Current counts are 131-186; the
# floor only has to sit above "obviously mangled".
MINIMUM_MODULES=50
if [[ ! -f gradle.lockfile ]]; then
    fail "gradle.lockfile is missing — run ./gradlew resolveAndLockAll --write-locks"
elif ! grep -q '^empty=' gradle.lockfile; then
    # Gradle always writes this trailing marker, so its absence means the
    # file was truncated or edited rather than generated.
    fail "gradle.lockfile has no trailing 'empty=' marker — truncated or hand-edited"
else
    thin=""
    for conf in compileClasspath runtimeClasspath testCompileClasspath testRuntimeClasspath; do
        # Anchor on the '=<configs>' field so a module named after a
        # configuration cannot satisfy the check.
        count=$(grep -cE "=([a-zA-Z]+,)*${conf}(,|$)" gradle.lockfile)
        (( count >= MINIMUM_MODULES )) || thin="$thin $conf($count)"
    done
    if [[ -n "$thin" ]]; then
        fail "gradle.lockfile covers too few modules for:$thin (floor $MINIMUM_MODULES)"
    else
        pass "gradle.lockfile complete, all four classpaths ($(grep -cE '^[^#].*:.*=' gradle.lockfile) modules)"
    fi
fi

# ── 8. The frontend lockfile is present and used ──────────────────────
# package.json ranges are fine *because* npm ci installs the lockfile
# verbatim. `npm install` would silently update it, so the lockfile only
# means anything while the pipeline says ci.
if [[ -f ../cde-angular/package-lock.json ]]; then
    pass "frontend package-lock.json present"
    if grep -qE '\bnpm install\b' Jenkinsfile; then
        fail "Jenkinsfile uses 'npm install'; it must use 'npm ci' or the lockfile is advisory"
    else
        pass "Jenkinsfile installs the frontend with npm ci"
    fi
else
    echo "  skip  frontend repo not checked out beside this one"
fi

echo
if (( failures )); then
    echo "$failures pinning check(s) failed." >&2
    exit 1
fi
echo "All pinning checks passed."
