# Global proxy validation

## Settings and behavior

With the VM stopped, open **Settings → Network proxy → Mode**:

- **Off** removes Podroid-managed proxy settings.
- **App proxy** uses HTTP proxy environment variables and managed Docker proxy
  settings. Applications that ignore these settings can still connect directly.
- **Global proxy** uses a guest TUN interface and routing rules for guest and
  container traffic. It does not depend on application proxy variables.

Global mode offers **HTTP** and **SOCKS5** upstream types. HTTP carries TCP and
proxied DNS; other UDP is rejected. SOCKS5 requires a no-auth upstream supporting
UDP ASSOCIATE. Its numeric UDP relay address must match the configured upstream
address (an unspecified bind address is interpreted as that address).

**Direct LAN** explicitly permits private/link-local destinations. DNS remains
proxied. Without this option, LAN destinations also use the proxy. Loopback,
guest-internal traffic and the Podroid host-service transport remain local.
The global guard remains installed when the proxy exits: traffic cannot silently
fall back to the physical interface. Select another mode and reboot to remove it.

Address queries use persistent synthetic DNS mappings (`198.19.0.0/16` and
`2001:2::/48`), so HTTP CONNECT / SOCKS5 receives the original domain and can
choose an address usable by the upstream. Other DNS queries use Cloudflare DoH
through the proxy, with TLS certificate verification. Literal IPv6 destinations
still require IPv6 support from the upstream.
Generic ICMP tunneling is not provided. UDP uses a framed TCP hop between the
guest and Android, then the upstream SOCKS5 UDP relay; it therefore inherits TCP
head-of-line blocking on that hop. TCP connections and UDP associations share
the displayed connection budget. UDP mode reserves more memory and descriptors
per slot, so its effective limit can be lower than TCP-only mode.

The Settings connection test checks a verified TLS connection and valid HTTP
response through the upstream. A destination WAF's HTTP 403 is a valid response;
CONNECT denial or certificate/hostname failures still fail the test. It does not
certify the guest TUN, UDP ASSOCIATE, or container routing.

## Reproducible isolated checks

```sh
./gradlew :app:testDebugUnitTest
node build-rootfs/tests/test_host_proxy.js
node build-rootfs/tests/test_global_proxy.js
sh build-rootfs/tests/test_podroid_network_dns.sh
JAVA_HOME=/path/to/jdk17 tools/tests/global-proxy/run.sh
```

The integration script currently requires ARM64 Docker. It creates its own
privileged test container and isolated network namespaces, without host
networking, published ports, production VM disks, or a writable project mount.
It removes the container on exit and prints the temporary evidence directory.

The fixture runs the real Java TCP/UDP relays, the guest SOCKS shim, sing-box and
nftables. Checks include IPv4/IPv6 TCP from the guest and a container-like routed
namespace, Docker-like forwarding policy, DNS interception, HTTP UDP rejection,
SOCKS5 UDP echo, relay loss, and persistent egress blocking after TUN shutdown.
IPv6 targets and UDP echo servers are controlled fixtures, not public load tests.

Also run `sh tools/tests/global-proxy/check-kernel.sh` **inside the disposable
guest**. Docker namespace tests use the Docker host kernel, so they cannot detect
missing options in the shipped VM kernel. The guest check validates nftables
expressions without installing rules; it specifically covers connection marks.

The domain-mapping check reuses cached IPv4 and IPv6 answers after restarting the
proxy, without a fresh DNS query. This detects lost mappings that could otherwise
break applications retaining DNS answers across proxy restarts.

## Local results (2026-11-25)

- All 510 JVM tests passed. UDP coverage includes partial handshakes, framing,
  large datagrams, shared limits, disconnect cleanup and 16 simultaneous
  associations exchanging 64 packets each, and immediate port reuse after
  closing an active association. One intermediate full run hit the existing
  `AvfTcpRelayTest.cancellation_closes_both_idle_sockets` race; final full runs
  passed without changing that test or the AVF relay implementation.
- Guest proxy configuration tests and isolated Linux integration checks passed.
- Android lint reports 18 existing errors in unchanged source (notification
  permissions, API-27 stat fields and Compose resource access). Lint is not
  passing; no reported error is in this change's source files.
- The standard rootfs Dockerfile and `Dockerfile --target ci-initramfs` builds
  passed, followed by debug APK assembly. An earlier attempt exhausted Docker
  storage; only this task's failed build cache was removed before the successful
  standard rebuild. Final hardware tests use the standard build artifacts.

Hardware validation must use an independent App package and fresh data disk.
Stop production only after its user has saved work; never run two VMs together.
Restore production and device settings afterwards. Hardware results are recorded
below only after observing them.

## Hardware results (2026-11-25)

Independent `com.excp.podroid.debug`, labelled **Podroid 全局代理测试**, uses a
fresh 2 GiB disk, 1024 MiB RAM and two vCPUs. Shared Downloads and autostart are
disabled. The device's upstream HTTP port is 8686; SOCKS5 with UDP is on 8687.
Production `com.ysh0566.podroid` is stopped only while a test VM runs.

| Check | AVF | QEMU |
|---|---|---|
| Ready with the rebuilt kernel and nftables expression check | Passed | Passed |
| HTTP mode: HTTPS without proxy variables | HTTP 200 | HTTP 200 |
| HTTP mode: container HTTP access / ordinary UDP blocked | Passed / passed | Passed / passed |
| SOCKS5 mode: HTTPS without proxy variables | HTTP 200 | HTTP 200 |
| SOCKS5 mode: 1200-byte UDP echo / container HTTP access | Passed / passed | Passed / passed |
| Proxy stop blocks literal-IP egress; restart restores HTTPS | Passed | Covered by shared Linux integration checks |

The final APK also passed two consecutive QEMU/SOCKS5 restart cycles, each with
a 1200-byte UDP echo and HTTPS 200. This validates immediate reuse of Android's
loopback relay ports after closing live associations.

The first hardware boot identified missing `NF_CONNTRACK_MARK`, now built in
and checked by the native build. Another hardware check found that this upstream
could dial a domain but rejected some literal IPv6 targets. Persistent DNS
mappings fix ordinary domain access while still carrying literal IPv6 traffic
through the proxy when supported by the upstream. The isolated Linux fixture
also checks domain-addressed UDP replies and cached mappings across restarts.

The Settings menu exposes Off / App proxy / Global proxy, HTTP / SOCKS5,
Direct LAN and connection limits. During VM execution, configuration is locked.
On this device the SOCKS5 session displayed an effective budget of 256 for a
configured 512, with active connections and rejection counts. The Android-side
SOCKS5 probe returned HTTP 200 through the configured upstream.

UML's parity configuration is updated but its kernel and runtime were not
validated in this task. The Direct LAN rule is covered by configuration checks;
hardware end-to-end tests above keep it disabled to prove the proxy path.

## Restoration and cleanup

Production was restored to `vm=running backend=avf selection=AVF stage=Ready`,
version code 100006. Its version, installation and update timestamps match the
pre-test package metadata. Production storage was never mounted or modified by
test commands; this is isolation/metadata verification, not a full disk hash.
The independent test App and disk were uninstalled, leaving only
`com.ysh0566.podroid` among Podroid packages. Temporary ADB forwarding, on-device
diagnostic files, local test credentials and the TCP/UDP echo process were
removed. The original stay-awake setting (`0`) was restored. No test Docker
containers remain; the owned test-environment image was removed.
