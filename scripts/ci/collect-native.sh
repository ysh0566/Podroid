#!/usr/bin/env bash
set -euo pipefail
native=${1:?usage: collect-native.sh INPUT_DIRECTORY}
assets=app/src/main/assets
libs=app/src/main/jniLibs/arm64-v8a
mkdir -p "$assets/qemu" "$libs" out
for file in vmlinuz-virt initrd.img alpine-rootfs.squashfs; do
    test -s "$native/$file"
    cp "$native/$file" "$assets/$file"
done
for file in libqemu-system-aarch64.so libslirp.so libpodroid-bridge.so libpodroid-launcher.so \
            libuml-4k.so libuml-16k.so libumlstub-4k.so libumlstub-16k.so libpodroid-uml.so; do
    test -s "$native/$file"
    cp "$native/$file" "$libs/$file"
done
cp -R "$native/qemu/." "$assets/qemu/"
# Distribute the corresponding patched UML source with the binary artifacts.
cp "$native"/uml-source-*.tar.zst out/
