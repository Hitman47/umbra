#!/bin/bash
# Build, sign, install and launch the Nyxara release APK from the current branch.
# Usage: ./scripts/build-nyxara-release.sh [--clean] [--no-install]
#
# Run from Git Bash (or WSL). Package: io.github.mkdevtests.umbra.
#
# Signing: the dedicated key described by ~/.umbra/release.properties
#   keystore=C:/Users/<you>/.umbra/umbra-release.jks
#   storePassword=...
#   keyAlias=umbra            (optional)
#   keyPassword=...           (optional, default: storePassword)
# or by the same values in env vars (they win):
#   UMBRA_RELEASE_KEYSTORE, UMBRA_RELEASE_KEYSTORE_PASSWORD,
#   UMBRA_RELEASE_KEY_ALIAS, UMBRA_RELEASE_KEY_PASSWORD
# otherwise the Android debug keystore. Keep the same key over time:
# GitHub updates need it, and switching keys forces an uninstall (app data lost).
# Back up ~/.umbra: it is not in the repo.

set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

. "$(dirname "$0")/_ensure_android_sdk.sh"
. "$(dirname "$0")/_adb.sh"
ensure_android_sdk "$REPO_ROOT"

CLEAN=0
INSTALL=1
for arg in "$@"; do
    case "$arg" in
        --clean) CLEAN=1 ;;
        --no-install) INSTALL=0 ;;
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
KEY_PROPERTIES="$HOME/.umbra/release.properties"
key_property() { grep -E "^$1=" "$KEY_PROPERTIES" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\r'; }
if [[ -z "${UMBRA_RELEASE_KEYSTORE:-}" && -f "$KEY_PROPERTIES" ]]; then
    UMBRA_RELEASE_KEYSTORE="$(key_property keystore)"
    UMBRA_RELEASE_KEYSTORE_PASSWORD="$(key_property storePassword)"
    UMBRA_RELEASE_KEY_ALIAS="$(key_property keyAlias)"
    UMBRA_RELEASE_KEY_PASSWORD="$(key_property keyPassword)"
fi
if [[ -n "${UMBRA_RELEASE_KEYSTORE:-}" ]]; then
    KEYSTORE="$UMBRA_RELEASE_KEYSTORE"
    [[ -f "$KEYSTORE" ]] || { echo "UMBRA_RELEASE_KEYSTORE points to a missing file: $KEYSTORE"; exit 1; }
    KS_PASS="${UMBRA_RELEASE_KEYSTORE_PASSWORD:?UMBRA_RELEASE_KEYSTORE is set but UMBRA_RELEASE_KEYSTORE_PASSWORD is not}"
    KEY_ALIAS="${UMBRA_RELEASE_KEY_ALIAS:-umbra}"
    KEY_PASS="${UMBRA_RELEASE_KEY_PASSWORD:-$KS_PASS}"
    KEYSTORE_KIND="release key"
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

echo "==> Building Nyxara release APK"
./gradlew :app:assembleRelease

OUT="app/build/outputs/apk/release"
UNSIGNED="$OUT/nyxara-release-unsigned.apk"
ALIGNED="$OUT/nyxara-release-aligned.apk"
SIGNED="$OUT/nyxara-release.apk"
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

[[ $INSTALL == 1 ]] && install_and_launch "$SIGNED" io.github.mkdevtests.umbra
exit 0
