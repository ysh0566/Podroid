# Configurable proxy connection limits

Date: 2026-11-25. Base: `7ecac47`.

## Behavior

Settings > Network > Network proxy now includes **Connection limit** with
128, 256, 512, 1024 and Auto. The default is 512. Select a value and Save while
the VM is stopped; the next VM session uses it. Existing keys remain unchanged;
the new integer preference is `network_connection_limit`, with 0 meaning Auto.
Missing or unsupported stored values use 512.

Auto uses the resource budget up to 1024 connections. Fixed values are ceilings,
also reduced by the resource budget. The connection status shows the actual
listener limit. Capacity is sampled after old relay sockets are closed, at
listener start or rebind, and remains stable for that listener's lifetime.

- Heap budget: the smaller of one eighth of maximum heap and one quarter of
  current heap headroom, divided by 64 KiB per connection. This budgets twice
  the payload buffers to allow for Java bookkeeping.
- Descriptor budget: subtract existing descriptors, the larger of 256 or one
  quarter of the soft descriptor limit, and eight listener/selector/probe slots;
  divide the remainder by two sockets per connection.
- Missing descriptor information caps capacity at 128, still subject to memory.
- Zero budget starts no listener and follows the existing retry path. Status
  omits a connection count until a positive listener capacity is available.

This is an admission estimate, not a continuous resource guarantee. Other App
work can consume resources later. Native socket buffers are not included in the
Java heap estimate. Auto is intentionally bounded rather than unlimited.

## Validation

- `:app:testDebugUnitTest`: 500 tests passed, zero failures, errors or skips.
  Six new policy tests cover fixed/Auto limits, defaults, invalid preferences,
  heap pressure, descriptor reservations, procfs failures and soft-limit parsing.
- `:app:assembleDebug`: passed with JDK 17 and a temporary NDK 27.0.12077973
  override. The repository's pinned toolchain is unchanged.
- English and Chinese labels added; the existing short-label settings style is
  preserved. The new key is included in the persisted-key contract test.

Four-round host loopback stress using `tools/tests/RelayStress.java`:

| Limit | Bytes forwarded | Seconds | Payload buffer bound |
|---|---:|---:|---:|
| 128 | 271195640 | 1.333 | 4 MiB |
| 256 | 539622904 | 2.357 | 8 MiB |
| 512 | 1076483804 | 5.173 | 16 MiB |
| 1024 | 2150235692 | 13.415 | 32 MiB |

Each run reached its configured capacity, rejected 32 intentional overflow
connections, retained one selector thread, preserved binary data and exercised
half-close, slow-reader isolation, abrupt resets, upstream refusal and cleanup.
These measurements are synthetic throughput, not Internet performance. macOS
does not expose `/proc/self/fd`, so descriptor growth was not measured here.

Reproduce with JDK 17 and disposable local output:

```sh
mkdir -p /tmp/podroid-limit-classes
javac -d /tmp/podroid-limit-classes \
  app/src/main/java/com/excp/podroid/hostservices/TcpRelay.java \
  tools/tests/RelayStress.java
for limit in 128 256 512 1024; do
  java -Xmx512m -cp /tmp/podroid-limit-classes \
    com.excp.podroid.hostservices.RelayStress "$limit" 4
done
```

## Remaining device coverage

No APK was installed and no tablet App, VM or existing data was changed for this
update. The earlier device test at 128 connections does not establish App-UID
capacity at 512 or 1024. The picker, save/reopen behavior, runtime budget sampling
and larger limits still need isolated App/device validation before deployment.
