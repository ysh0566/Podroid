# Integrated host services validation

Date: 2026-11-25. Branch: `feature/integrated-host-services`.

## Isolation and current device status

The independent `com.excp.podroid.debug` App, labelled **Podroid 集成测试**,
was installed with a new 4 GiB disk, 1024 MiB RAM and two vCPUs. Shared Downloads
access and autostart were disabled. Neither production `com.ysh0566.podroid`
nor standalone helper `dev.podroid.bridge` was replaced or migrated.

Production was temporarily stopped with the user's authorization. Only one VM
ran at a time. After validation, the test VM was stopped normally, its microphone
service was confirmed absent, and the test App was force-stopped. It remains
installed with AVF selected. Production was restored to
`vm=running backend=avf selection=AVF stage=Ready`. The test SSH forward and
on-device UI dump were removed.

The device is an arm64 Xiaomi M367FC running Android 17. Production remains
version code 34 / 1.3.0-debug and the helper remains 1.1.1 (code 4). Their package
update times still match the baseline: 2026-11-25 01:24:36 and 01:28:58,
respectively. The production disk remains 137438953472 bytes. These are metadata
and lifecycle checks, not a byte-for-byte comparison of a running VM disk.

## Completed local checks

- Android debug compilation and APK assembly succeeded with JDK 17 and SDK 36.1.
  NDK 27.0.12077973 was selected through a temporary Gradle init script without
  changing the project's pinned toolchain.
- All 494 JVM tests passed after the NIO/proxy UI update. New tests cover relay byte integrity, half-close,
  subnet admission, connection limits, shutdown/rebind, browser concurrency,
  configuration rejection, boot flags, token rotation and diagnostic redaction.
  An earlier run hit the existing `AvfTcpRelayTest` idle-socket teardown race;
  subsequent complete runs passed without changing that implementation.
- `node build-rootfs/tests/test_host_proxy.js` passed: endpoint validation,
  gateway changes, opt-out restoration, registry credentials and manual edits.
- `sh build-rootfs/tests/test_podroid_network_dns.sh` passed.
- The arm64 Alpine rootfs built successfully. An ephemeral Docker container
  exercised its actual PulseAudio server and Node transport using synthetic PCM:
  authenticated microphone input reached `podroid_mic`, and nonzero playback
  samples reached the TCP listener. No real microphone or audio recording files
  were used by this check. Containers were removed automatically with `--rm`.
- Lint reports 18 errors in pre-existing source: notification permission,
  API-27 stat fields under minSdk 26, and Compose resource access. Each reported
  statement was compared with base commit `ef7d5c0` and was already present.
  This is source comparison, not a separate clean-baseline lint run; lint is
  therefore **not passing**. No reported error is in the new host-service code.

Reproduce the guest audio check after exporting a rootfs directory:

```sh
docker run --rm --platform linux/arm64 \
  -v "$ROOTFS_EXPORT_DIR:/image:ro" \
  -v "$PWD/build-rootfs/tests:/tests:ro" \
  alpine:3.24 sh /tests/test_audio_transport.sh
```

The harness creates its own disposable `/test-root`, substitutes a loopback
route for synthetic peers, and changes no files in either read-only mount.

## Isolated hardware validation

| Check | AVF | QEMU |
|---|---|---|
| Ready using the independent 4 GiB disk | Passed | Passed |
| Docker, proxy, audio, audio transport and X11 services started | Passed | Passed |
| Login proxy and bypass variables follow the backend gateway | Passed | Passed |
| HTTPS through the integrated proxy | HTTP 200 | HTTP 200; 128 KiB download verified |
| Real microphone PCM reaches the guest | 576960 samples in about 12 seconds; 510137 nonzero | 287040 samples in about 6 seconds; 266429 nonzero |
| Stopping the VM releases the microphone service | Passed | Passed |
| Audible guest playback | User confirmed normal sound after a 3-second tone | Not separately confirmed |

Microphone samples were counted in memory; no audio recording files were saved.
On AVF, the toolbar Stop action also removed the microphone service and its
listener refused new connections. The Android-side settings connection test
reported HTTPS success from the actual App UID.

The App relay accepted 128 simultaneous idle connections from the AVF guest,
rejected 16 excess connections and preserved the existing sessions. Home showed
`128 / 128` and 16 rejected connections. After all sessions closed, HTTPS still
returned 200. This capacity check used local proxy sockets, not public-site load.
It complements the standalone byte-integrity stress measurements below.

Disabling the proxy through Settings and restarting the isolated AVF VM removed
managed login/Docker proxy configuration. Proxy and microphone ports refused
connections. The proxy was re-enabled before QEMU testing; its gateway changed
from `10.199.82.169` on AVF to `10.0.2.2` on QEMU.

UML did not pass its compatibility probe. Its console reported
`Kernel panic - not syncing: Attempted to kill init! exitcode=0x00000000`.
The control API reported `uml=failed:Kernel_panic`, then the App fell back to
AVF Ready. This is fallback evidence, not successful UML integration validation.
The cause has not been isolated against a baseline build.

### Remaining hardware checks

- UML guest integration on a device/build where the compatibility probe passes.
- Microphone permission denial: the attempted UI interaction did not establish
  a reliable denied state, so only the granted recording path is confirmed.
- Notification Stop: the device grouped the notifications without exposing a
  unique Stop action; toolbar Stop and normal VM-stop cleanup passed instead.
- App/guest upstream loss and recovery, and absence of recording after unexpected
  process death. Standalone transport checks do not replace these lifecycle tests.
- QEMU playback needs a separate audible confirmation.

Existing user overlay scripts can shadow updated audio/proxy files. Production
overlay reconciliation is outside this isolated test and has not run. The PR
still has validation gaps and the existing lint errors; it is not fully verified.

## NIO proxy update

The proxy UI is now **Settings → Network → Network proxy**. It includes the
proxy switch, HTTP protocol explanation, Android upstream host/port, bypass
list, Save, and an explicit Android-side HTTPS connection test. Current / limit,
transferred bytes and rejected connections are shown for the current VM session.
The shipped limit remains **128**; 512 is used only as a stress-harness parameter.

After device validation, the settings entry was reduced to one standard list row
with an on/off value. Tapping it opens a scrollable configuration dialog. Proxy
and microphone labels were shortened in both languages, with secondary help
using the existing small-text style. `:app:compileDebugKotlin` and
`:app:processDebugResources` passed; XML parsing, resource keys and formatting
arguments were checked in both translations. This final layout change has not
been installed or visually verified on the tablet. Before publishing the update,
`:app:testDebugUnitTest` was run again: all 494 tests passed without failures or skips.

The transport uses one selector thread, 16 KiB per direction, a three-second
upstream-connect timeout, bounded accept batches and read backpressure. Tests
cover binary integrity, delayed responses after half-close, overflow rejection,
abrupt resets, slow readers, failed upstream connects and teardown/rebind.

### Host stress results (JDK 17, loopback only)

| Workload | Result | Relay threads | Buffer bound |
|---|---|---|---|
| 128 concurrent, 20 rounds | PASS; 1,344,862,748 bytes, 5.521 s | 1 | 4 MiB |
| 512 concurrent, 8 rounds | PASS; 2,150,235,640 bytes, 9.640 s | 1 | 16 MiB |
| 128 persistent, 64 MiB per client | PASS; 17,182,621,228 bytes, 46.199 s | 1 | 4 MiB |

Each run rejected 32 intentional overflow connections, preserved payload
integrity and released every relay slot/thread. Byte counts include both
forwarding directions and the slow-reader check. These are synthetic loopback
measurements, not Internet throughput. Buffer bounds exclude kernel socket
buffers, Java objects and the test fixture's own memory. macOS does not expose
`/proc/self/fd`; Android runs additionally check idle descriptor counts.

An intentionally aggressive 240-round short-connection run was aborted by
macOS with `BindException: Can't assign requested address`. The host ephemeral
port range is 49152–65535. No system network settings were changed to bypass
that limit; steady-load validation uses persistent connections instead.

Reproduce without touching any installed App or VM:

```sh
mkdir -p /tmp/podroid-relay-classes
javac -d /tmp/podroid-relay-classes \
  app/src/main/java/com/excp/podroid/hostservices/TcpRelay.java \
  tools/tests/RelayStress.java
java -Xmx256m -cp /tmp/podroid-relay-classes \
  com.excp.podroid.hostservices.RelayStress 128 20
# Optional capacity test; does not alter the App's 128-connection limit:
java -Xmx256m -cp /tmp/podroid-relay-classes \
  com.excp.podroid.hostservices.RelayStress 512 8
```

### Android transport stress and environment restoration

On the connected arm64 Android 17 tablet, the same `TcpRelay` class and standalone
harness were compiled to DEX and run using `app_process` in a separate shell
process. Every socket used loopback and ephemeral ports. No production package,
user filesystem or VM disk was used by the harness.

- 128 simultaneous connections, 20 rounds, 1,345,323,008 bytes in 5.080 seconds.
- 32 intentional excess connections rejected; peak relay connections: 128.
- One relay selector thread throughout; all relay slots/threads released.
- Idle process descriptor count remained 78 across rounds (the harness allows
  a small margin for Android runtime descriptors); no growth with connection churn.
- Both-direction payload checks, half-close, slow-reader isolation, RST cleanup
  and repeated upstream connection refusal passed.

These measurements validate Android NIO/ART behavior in an isolated shell
process, **not** the App UID's networking permissions, AVF/SLIRP integration,
proxy UI or an actual Internet proxy. The later isolated hardware checks above
cover the App UID, AVF/QEMU integration and proxy UI separately.

The authorized VM stop and restoration were wrapped in `try/finally`. After the
run, production `com.ysh0566.podroid` reported `vm=running backend=avf stage=Ready`.
The standalone helper remained running. Production/helper versions and package
update times matched the baseline exactly, and no test package was installed.
The uploaded `/data/local/tmp/podroid-nio-stress-20261125.jar` was removed.

## PR preparation verification

After rebasing onto `main` at `6d10ae2`, the following checks were repeated:

- `:app:testDebugUnitTest --rerun`: 494 tests passed, with no failures or skips.
- `:app:assembleDebug`: passed with the documented local NDK override.
- Guest proxy and DNS tests, shell syntax and JavaScript syntax: passed.
- Host loopback stress: 128 connections for 20 rounds and 512 for eight rounds
  both passed, each rejecting 32 intentional overflow connections and using one
  selector thread. The production limit remains 128.
- An isolated, resource-limited arm64 container passed synthetic microphone and
  playback tests against the existing rootfs. Its audio/proxy scripts and Pulse
  configuration matched the PR source by SHA-256. The container was removed.
- All 29 new user-facing strings have both English and Chinese translations.

No APK was installed, no real microphone was captured, and no tablet app or VM
was stopped during the earlier PR preparation phase. Subsequent authorized
hardware validation is recorded above. The remaining hardware checks and
existing lint errors remain open.
