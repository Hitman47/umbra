#!/bin/bash
# Build, install and launch Nyxara debug from the current branch.
# Usage: ./scripts/build-nyxara-debug.sh [--clean]
#
# Run from Git Bash (or WSL). Package: io.github.mkdevtests.umbra.debug,
# installs side by side with the release build.
#
# Refuses to build from `main`: feature work belongs on a dedicated branch.
# Use scripts/build-nyxara-release.sh to ship `main`.

set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

. "$(dirname "$0")/_ensure_android_sdk.sh"
. "$(dirname "$0")/_adb.sh"
ensure_android_sdk "$REPO_ROOT"

CLEAN=0
for arg in "$@"; do
    case "$arg" in
        --clean) CLEAN=1 ;;
        *) echo "Unknown arg: $arg"; exit 2 ;;
    esac
done

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")"
case "$CURRENT_BRANCH" in
    main)
        echo "ERROR: refusing to build debug from 'main'."
        echo "  Check out a feature branch, or use scripts/build-nyxara-release.sh."
        exit 1
        ;;
    "") echo "WARN: could not detect current branch (detached HEAD?). Continuing." ;;
    *)  echo "==> Building from branch: $CURRENT_BRANCH" ;;
esac

fix_gradlew_crlf

if [[ $CLEAN == 1 ]]; then
    echo "==> Clean"
    ./gradlew :app:clean
fi

echo "==> Building Nyxara debug APK"
./gradlew :app:assembleDebug

APK="app/build/outputs/apk/debug/nyxara-debug.apk"
[[ -f "$APK" ]] || { echo "APK not found: $APK"; exit 1; }
echo "==> APK ready: $APK ($(du -h "$APK" | cut -f1))"

install_and_launch "$APK" io.github.mkdevtests.umbra.debug
