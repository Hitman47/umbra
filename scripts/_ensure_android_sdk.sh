#!/bin/bash
# Resolve the Android SDK without depending on the caller's environment.
# Sourced by the build scripts (same approach as Kora).
#
# ANDROID_HOME is often unset in a non-interactive shell (Git Bash launched
# by an agent, WSL `bash -lc`, ...). Gradle then dies two seconds in with
# "SDK location not found", for a reason unrelated to the code.
#
# Order: $ANDROID_HOME, the usual install locations (Windows via Git Bash,
# WSL/Linux), then sdk.dir from local.properties. A path is accepted only
# if it looks like an SDK (platforms/ or platform-tools/ inside).

ensure_android_sdk() {
    local repo_root="${1:-$PWD}"

    if _android_sdk_looks_real "${ANDROID_HOME:-}"; then
        export ANDROID_SDK_ROOT="$ANDROID_HOME"
        return 0
    fi

    local candidate
    for candidate in \
        "${LOCALAPPDATA:+$(_to_posix "$LOCALAPPDATA")/Android/Sdk}" \
        "$HOME/AppData/Local/Android/Sdk" \
        "$HOME/android-sdk" \
        "$HOME/Android/Sdk" \
        "/usr/lib/android-sdk" \
        "/opt/android-sdk"
    do
        if _android_sdk_looks_real "$candidate"; then
            export ANDROID_HOME="$candidate"
            export ANDROID_SDK_ROOT="$candidate"
            echo "==> Android SDK: $ANDROID_HOME"
            return 0
        fi
    done

    local declared
    declared="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' \
        "$repo_root/local.properties" 2>/dev/null | tail -1 | sed 's/\\:/:/g')"
    if _android_sdk_looks_real "$(_to_posix "$declared")"; then
        export ANDROID_HOME="$(_to_posix "$declared")"
        export ANDROID_SDK_ROOT="$ANDROID_HOME"
        echo "==> Android SDK from local.properties: $ANDROID_HOME"
        return 0
    fi

    echo "ERROR: no usable Android SDK found." >&2
    echo "  ANDROID_HOME=${ANDROID_HOME:-<unset>}" >&2
    [[ -n "$declared" ]] && echo "  local.properties sdk.dir=$declared" >&2
    echo "  Install the SDK with Android Studio, or set ANDROID_HOME." >&2
    return 1
}

# C:\foo or C:/foo -> /c/foo under Git Bash; unchanged elsewhere.
_to_posix() {
    local p="$1"
    if command -v cygpath >/dev/null 2>&1 && [[ "$p" =~ ^[A-Za-z]: ]]; then
        cygpath -u "$p"
    else
        echo "$p"
    fi
}

_android_sdk_looks_real() {
    local dir="$1"
    [[ -n "$dir" ]] || return 1
    [[ -d "$dir/platforms" ]] || [[ -d "$dir/platform-tools" ]]
}
