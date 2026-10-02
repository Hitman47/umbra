#!/bin/bash
# Build, sign, install and launch the Umbra release APK from the current branch.
# Usage: ./scripts/build-umbra-release.sh [--clean]
#
# Run from Git Bash (or WSL). Package: io.github.mkdevtests.umbra.
#
# Signing: a dedicated key when UMBRA_RELEASE_KEYSTORE is set
#   UMBRA_RELEASE_KEYSTORE           path to the .jks
#   UMBRA_RELEASE_KEYSTORE_PASSWORD  store password (required with the above)
#   UMBRA_RELEASE_KEY_ALIAS          default: umbra
#   UMBRA_RELEASE_KEY_PASSWORD       default: the store password
# otherwise the Android debug keystore. Keep the same key over time:
# switching keys forces an uninstall (and loses app data).

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
[[ "$CURRENT_BRANCH" != "main" ]] && echo "WARN: release built from '$CURRENT_BRANCH', not 'main'."

# ----- build-tools (zipalign + apksigner) -----
BUILD_TOOLS=$(ls -d "$ANDROID_HOME/build-tools/"*/ | sort -V | tail -1)
ZIPALIGN="${BUILD_TOOLS}zipalign"
APKSIGNER="${BUILD_TOOLS}apksigner"
[[ -f "${ZIPALIGN}.exe" ]] && ZIPALIGN="${ZIPALIGN}.exe"
[[ -f "${APKSIGNER}.bat" ]] && APKSIGNER="${APKSIGNER}.bat"
[[ -f "$ZIPALIGN" ]] || { echo "zipalign not found at $ZIPALIGN"; exit 1; }
[[ -f "$APKSIGNER" ]] || { echo "apksigner not found at $APKSIGNER"; exit 1; }

# ----- signing keystore -----
if [[ -n "${UMBRA_RELEASE_KEYSTORE:-}" ]]; then
    KEYSTORE="$UMBRA_RELEASE_KEYSTORE"
    [[ -f "$KEYSTORE" ]] || { echo "UMBRA_RELEASE_KEYSTORE points to a missing file: $KEYSTORE"; exit 1; }
    KS_PASS="${UMBRA_RELEASE_KEYSTORE_PASSWORD:?UMBRA_RELEASE_KEYSTORE is set but UMBRA_RELEASE_KEYSTORE_PASSWORD is not}"
    KEY_ALIAS="${UMBRA_RELEASE_KEY_ALIAS:-umbra}"
    KEY_PASS="${UMBRA_RELEASE_KEY_PASSWORD:-$KS_PASS}"
    KEYSTORE_KIND="release key (from env)"
else
    KEYSTORE=""
    for candidate in \
        "$HOME/.android/debug.keystore" \
        "/mnt/c/Users/mathi/.android/debug.keystore"; do
        [[ -f "$candidate" ]] && KEYSTORE="$candidate" && break
    done
    [[ -z "$KEYSTORE" ]] && { echo "debug.keystore not found in ~/.android/"; exit 1; }
    KS_PASS="android"
    KEY_ALIAS="androiddebugkey"
    KEY_PASS="android"
    KEYSTORE_KIND="Android debug key"
fi

echo "==> build-tools: $BUILD_TOOLS"
echo "==> keystore: $KEYSTORE ($KEYSTORE_KIND)"

fix_gradlew_crlf

if [[ $CLEAN == 1 ]]; then
    echo "==> Clean"
    ./gradlew :app:clean
fi

echo "==> Building Umbra release APK"
./gradlew :app:assembleRelease

OUT="app/build/outputs/apk/release"
UNSIGNED="$OUT/umbra-release-unsigned.apk"
ALIGNED="$OUT/umbra-release-aligned.apk"
SIGNED="$OUT/umbra-release.apk"
[[ -f "$UNSIGNED" ]] || { echo "Unsigned APK not found: $UNSIGNED"; exit 1; }

echo "==> Aligning"
"$ZIPALIGN" -p -f 4 "$UNSIGNED" "$ALIGNED"

echo "==> Signing"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$KS_PASS" \
    --ks-key-alias "$KEY_ALIAS" \
    --key-pass "pass:$KEY_PASS" \
    --out "$SIGNED" \
    "$ALIGNED"
rm -f "$ALIGNED" "$SIGNED.idsig"

echo "==> APK ready: $SIGNED ($(du -h "$SIGNED" | cut -f1))"

install_and_launch "$SIGNED" io.github.mkdevtests.umbra
