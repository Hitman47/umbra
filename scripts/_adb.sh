#!/bin/bash
# Shared adb helpers for the build scripts. Sourced after ensure_android_sdk.

# Pick adb: PATH first, then the SDK's platform-tools.
resolve_adb() {
    if command -v adb >/dev/null 2>&1; then
        ADB=adb
    elif [[ -x "$ANDROID_HOME/platform-tools/adb.exe" ]]; then
        ADB="$ANDROID_HOME/platform-tools/adb.exe"
    elif [[ -x "$ANDROID_HOME/platform-tools/adb" ]]; then
        ADB="$ANDROID_HOME/platform-tools/adb"
    else
        ADB=""
    fi
}

# Install $1 (APK) and launch package $2. Tells apart "no device",
# "offline" and "unauthorized" (plugged in, Allow not tapped yet).
install_and_launch() {
    local apk="$1" pkg="$2"

    # In WSL, adb.exe does not reliably see the device the Windows adb
    # server sees: print the commands for PowerShell instead.
    if grep -qi microsoft /proc/version 2>/dev/null; then
        local win_apk
        win_apk="$(wslpath -w "$(realpath "$apk")" 2>/dev/null || echo "$apk")"
        echo ""
        echo "==> WSL detected. In PowerShell, run:"
        echo "    adb install -r \"$win_apk\""
        echo "    adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1"
        return 0
    fi

    resolve_adb
    if [[ -z "$ADB" ]]; then
        echo "adb not found. Install manually: adb install -r $apk"
        return 0
    fi

    "$ADB" start-server >/dev/null 2>&1 || true
    local state
    state="$("$ADB" devices 2>/dev/null | awk 'NR>1 && NF>=2 {print $2; exit}')"
    case "$state" in
        device)
            echo "==> Installing on $("$ADB" shell getprop ro.product.model | tr -d '\r')"
            if ! "$ADB" install -r "$apk"; then
                echo "Install failed (signature mismatch?). To force-replace (app data is lost):" >&2
                echo "    adb uninstall $pkg && adb install $apk" >&2
                return 1
            fi
            echo "==> Launching $pkg"
            "$ADB" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
            ;;
        unauthorized)
            echo "Device is plugged in but unauthorized." >&2
            echo "  Tap 'Allow USB debugging' on the device ('Always allow'), then re-run. APK: $apk" >&2
            return 1
            ;;
        offline)
            echo "Device is offline. Unplug/replug the cable, then re-run. APK: $apk" >&2
            return 1
            ;;
        *)
            echo "No device connected. Install manually: adb install -r $apk"
            ;;
    esac
}

# gradlew checked out with CRLF (WSL on /mnt/c) cannot be exec'd by bash.
fix_gradlew_crlf() {
    if head -1 ./gradlew 2>/dev/null | grep -q $'\r'; then
        sed -i 's/\r$//' ./gradlew
        chmod +x ./gradlew
    fi
}
