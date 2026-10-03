#!/bin/bash
# Build the signed release APK and publish it as a GitHub release: installed
# apps offer the update on their next launch.
# Usage: ./scripts/publish-umbra-release.sh [notes.md]
#
# Before running: bump `umbraVersion` in app/build.gradle.kts, commit, push.
# Notes default to the commit subjects since the previous release.
# Needs the dedicated key (~/.umbra/release.properties, see
# build-umbra-release.sh) and `gh` logged in.

set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

NOTES_FILE="${1:-}"
[[ -n "$NOTES_FILE" && ! -f "$NOTES_FILE" ]] && { echo "Notes file not found: $NOTES_FILE"; exit 2; }

VERSION="$(sed -n 's/^val umbraVersion = "\(.*\)"/\1/p' app/build.gradle.kts)"
[[ -n "$VERSION" ]] || { echo "umbraVersion not found in app/build.gradle.kts"; exit 1; }
TAG="v$VERSION"

# Installed apps only update to the same signature: never publish with the debug key.
[[ -n "${UMBRA_RELEASE_KEYSTORE:-}" || -f "$HOME/.umbra/release.properties" ]] \
    || { echo "No release key (~/.umbra/release.properties): refusing to publish."; exit 1; }

git diff --quiet && git diff --cached --quiet || { echo "Uncommitted changes: commit them first."; exit 1; }
git fetch -q origin
[[ "$(git rev-parse HEAD)" == "$(git rev-parse '@{u}' 2>/dev/null)" ]] \
    || { echo "HEAD is not pushed (or has no upstream): push first."; exit 1; }
CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[[ "$CURRENT_BRANCH" != "main" ]] && echo "WARN: publishing from '$CURRENT_BRANCH', not 'main'."

if gh release view "$TAG" >/dev/null 2>&1; then
    echo "Release $TAG already exists: bump umbraVersion in app/build.gradle.kts."
    exit 1
fi

./scripts/build-umbra-release.sh --no-install
APK="app/build/outputs/apk/release/umbra-release.apk"

if [[ -z "$NOTES_FILE" ]]; then
    NOTES_FILE="$(mktemp)"
    PREVIOUS="$(git describe --tags --abbrev=0 2>/dev/null || true)"
    git log --no-merges --pretty='- %s' ${PREVIOUS:+"$PREVIOUS"..}HEAD > "$NOTES_FILE"
fi

echo "==> Publishing $TAG from $(git rev-parse --short HEAD)"
gh release create "$TAG" "$APK" \
    --target "$(git rev-parse HEAD)" \
    --title "Umbra $VERSION" \
    --notes-file "$NOTES_FILE"
git fetch -q --tags origin
echo "==> Done: installed apps will offer $VERSION on their next launch."
