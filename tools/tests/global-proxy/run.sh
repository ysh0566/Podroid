#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
lab="$(mktemp -d "${TMPDIR:-/tmp}/podroid-global-test.XXXXXX")"
container="podroid-global-test-$$"
# No host networking, published ports, production images or writable project mount.
cleanup() { docker rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
version="$(sed -n 's/^ARG SING_BOX_VERSION=//p' "$root/build-rootfs/Dockerfile.rootfs")"
checksum="$(sed -n 's/^ARG SING_BOX_SHA256=//p' "$root/build-rootfs/Dockerfile.rootfs")"
curl -fsSL --retry 3 "https://github.com/SagerNet/sing-box/releases/download/v${version}/sing-box-${version}-linux-arm64-musl.tar.gz" -o "$lab/sing-box.tar.gz"
echo "$checksum  $lab/sing-box.tar.gz" | shasum -a 256 -c -
tar -xzf "$lab/sing-box.tar.gz" -C "$lab"
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -d "$lab/classes" \
    "$root"/app/src/main/java/com/excp/podroid/hostservices/{RelayBudget,TcpRelay,UdpRelay}.java \
    "$root/tools/tests/global-proxy/RelayHarness.java"
docker build -t podroid-global-test-env "$root/tools/tests/global-proxy"
docker run -d --name "$container" --privileged -v "$root:/src:ro" -v "$lab:/lab" podroid-global-test-env sleep 1800 >/dev/null
docker exec "$container" sh /src/tools/tests/global-proxy/setup.sh
docker exec "$container" python3 /src/tools/tests/global-proxy/verify.py | tee "$lab/result.log"
printf 'Test evidence: %s\n' "$lab"
