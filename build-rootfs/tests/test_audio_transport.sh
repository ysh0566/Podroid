#!/bin/sh
# Run inside an ephemeral arm64 Alpine container with this directory mounted at
# /tests and the exported squashfs directory mounted read-only at /image.
# Only the disposable /test-root tree is changed; all PCM is synthetic.
set -eu
apk add --no-cache squashfs-tools >/dev/null
unsquashfs -quiet -d /test-root /image/alpine-rootfs.squashfs >/dev/null
r=/test-root
mkdir -p "$r/dev/shm" "$r/proc" "$r/run/podroid-pulse/private" "$r/root/.config/pulse" "$r/run/dbus"
chmod 711 "$r/run/podroid-pulse"
chmod 700 "$r/run/podroid-pulse/private" "$r/root/.config/pulse"
for spec in 'null 1 3' 'zero 1 5' 'random 1 8' 'urandom 1 9'; do set -- $spec; [ -e "$r/dev/$1" ] || mknod "$r/dev/$1" c "$2" "$3"; chmod 666 "$r/dev/$1"; done
printf 'podroid.mic_port=18889 podroid.mic_token=%064d\n' 1 > "$r/proc/cmdline"
# A fake route is confined to this disposable rootfs. Both peers run here.
rm "$r/sbin/ip"
printf '#!/bin/sh\necho default via 127.0.0.1 dev eth0\n' > "$r/sbin/ip"
chmod +x "$r/sbin/ip"
cp /tests/test_audio_transport.js "$r/tmp/audio-smoke.js"
chroot "$r" /usr/bin/dbus-daemon --system --fork
chroot "$r" /usr/bin/env PULSE_RUNTIME_PATH=/run/podroid-pulse/private XDG_RUNTIME_DIR=/run/podroid-pulse/private /usr/bin/pulseaudio -n --daemonize=no --disallow-exit --exit-idle-time=-1 --file=/etc/pulse/podroid-audio.pa > /tmp/pulse.log 2>&1 &
pulse=$!
trap 'kill "$pulse" 2>/dev/null || true' EXIT
sleep 2
if ! chroot "$r" /usr/bin/env PULSE_SERVER=unix:/run/podroid-pulse/native /usr/bin/pactl info; then cat /tmp/pulse.log; exit 1; fi
chroot "$r" /usr/bin/node /tmp/audio-smoke.js
