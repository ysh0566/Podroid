#!/usr/bin/env bash
set -euo pipefail
: "${RUNNER_TEMP:?}" "${ANDROID_HOME:?}" "${PODROID_APPLICATION_ID:?}" "${PODROID_VERSION_CODE:?}"
signing="$RUNNER_TEMP/podroid-ci-signing.jks"
trap 'rm -f "$signing"' EXIT
mode=unsigned
if [[ -n "${KEYSTORE_BASE64:-}" ]]; then
    : "${ORG_GRADLE_PROJECT_PODROID_RELEASE_STORE_PASSWORD:?Missing store password}"
    : "${ORG_GRADLE_PROJECT_PODROID_RELEASE_KEY_ALIAS:?Missing key alias}"
    : "${ORG_GRADLE_PROJECT_PODROID_RELEASE_KEY_PASSWORD:?Missing key password}"
    : "${EXPECTED_CERT_SHA256:?Missing expected signing certificate fingerprint}"
    umask 077
    printf '%s' "$KEYSTORE_BASE64" | base64 --decode > "$signing"
    export ORG_GRADLE_PROJECT_PODROID_RELEASE_STORE_FILE="$signing"
    mode=signed
fi
# Never put passwords in command-line flags or enable shell tracing. Disabling
# the configuration cache prevents signing configuration from entering caches.
./gradlew --no-daemon --no-configuration-cache --init-script scripts/ci/build.init.gradle :app:assembleRelease
apk=app/build/outputs/apk/release/app-release-unsigned.apk
if [[ "$mode" == signed ]]; then apk=app/build/outputs/apk/release/app-release.apk; fi
test -s "$apk"
tools="$ANDROID_HOME/build-tools/36.0.0"
mkdir -p build/ci/dist
"$tools/aapt" dump badging "$apk" > build/ci/package-badging.txt
python3 - <<'PY'
import os, re
from pathlib import Path
line = Path('build/ci/package-badging.txt').read_text().splitlines()[0]
assert re.search(r"name='([^']+)'", line)[1] == os.environ['PODROID_APPLICATION_ID'], 'Unexpected application ID'
assert re.search(r"versionCode='(\d+)'", line)[1] == os.environ['PODROID_VERSION_CODE'], 'Unexpected version code'
PY
if [[ "$mode" == signed ]]; then
    "$tools/apksigner" verify --verbose --print-certs "$apk" > build/ci/signing.txt
    actual=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' build/ci/signing.txt)
    expected=$(printf '%s' "$EXPECTED_CERT_SHA256" | tr -d ':' | tr '[:upper:]' '[:lower:]')
    [[ "$actual" == "$expected" ]] || { echo 'Signing certificate mismatch' >&2; exit 1; }
fi
filename="podroid-${PODROID_VERSION_CODE}-${mode}.apk"
cp "$apk" "build/ci/dist/$filename"
(cd build/ci/dist && sha256sum "$filename" > SHA256SUMS)
{
    printf 'Application ID: %s\nVersion code: %s\nSigning: %s\nCommit: %s\n' \
        "$PODROID_APPLICATION_ID" "$PODROID_VERSION_CODE" "$mode" "${GITHUB_SHA:-local}"
    if [[ "$mode" == signed ]]; then
        printf 'Certificate SHA-256: %s\n' "$actual"
    else
        printf 'This APK is unsigned and cannot be installed until signed with your existing key.\n'
    fi
} > build/ci/dist/BUILD.txt
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then cat build/ci/dist/BUILD.txt >> "$GITHUB_STEP_SUMMARY"; fi
