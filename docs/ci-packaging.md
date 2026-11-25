# CI APK packaging

`.github/workflows/build-apk.yml` runs for pushes to `main`, pushes of any tag,
and manual dispatch. Creating a local tag alone does not run GitHub Actions;
push it to the repository. Old tags whose commit predates the workflow do not
acquire a workflow retroactively. No release is published and no device is
modified or installed automatically.

## Pipeline and outputs

Four independent jobs build the kernel/initramfs, QEMU, Alpine rootfs and UML
runtime from this checkout. Docker layer caches accelerate subsequent builds.
The APK job installs JDK 17, Android SDK 36/36.1 and NDK r27c, runs the JVM unit
tests, and assembles the release variant. The full UML commit pin denotes the
same source revision as the previous abbreviated pin.

Open the completed Actions run and download:

- `podroid-<versionCode>-<commit>`: APK, `SHA256SUMS` and `BUILD.txt`.
- The matching `-uml-source` artifact: corresponding patched UML source.
- The matching `-tests` artifact: unit-test reports.

Artifacts expire after 30 days (test reports: 14 days; intermediate runtime:
3 days). Native builds can take substantially longer on a cold cache. Workflow
and Docker actions are pinned to complete commit IDs.

CI uses `scripts/ci/build.init.gradle`; normal local build identity is unchanged.
The default CI application ID is `com.ysh0566.podroid`, configurable through the
repository variable `PODROID_APPLICATION_ID`. CI version codes are
`100000 + github.run_number`, starting above the tablet's installed code 34;
reruns keep the same code. Do not replace/delete this workflow and reset its run
number without also adjusting the offset. Main builds use a version name such
as `1.3.0-ci.1`; tag builds use the sanitized tag with a leading `v` removed.

The guest image's `SYSTEM_VERSION` deliberately remains the source version
code, **not** the CI counter: migration script numbers belong to the guest
release history and must not be skipped by a large CI build number.

## Signing

No local private key is uploaded by these scripts. Without signing secrets,
the artifact contains `podroid-<versionCode>-unsigned.apk`. **It cannot be
installed until signed.** The build metadata and filename state this clearly;
CI never substitutes a newly generated debug key.

To produce signed APKs, configure these repository Actions secrets:

| Secret | Value |
|---|---|
| `PODROID_KEYSTORE_BASE64` | Base64 encoding of the existing signing keystore |
| `PODROID_STORE_PASSWORD` | Keystore password |
| `PODROID_KEY_ALIAS` | Existing key alias |
| `PODROID_KEY_PASSWORD` | Existing private-key password |

Also set repository variable `PODROID_SIGNING_CERT_SHA256` to the expected
certificate SHA-256 fingerprint (with or without colon separators). If a
keystore is supplied but another required value is missing, the build fails.
After signing, `apksigner` verifies the APK and the certificate must match that
fingerprint. The key file exists only in the runner's temporary directory and
is deleted on exit; the signing build does not use Gradle's configuration cache.
Never commit keystores/passwords or include them in artifacts/caches.

For the current tablet installation, the verified certificate fingerprint is:

```text
bab464577accee3fc7cc6af207f38c39faa24e7cdb08d17939bb05e50e3cc241
```

An update must keep application ID and signing identity and use a non-decreasing
version code. This pipeline checks the generated package/version and, when
configured, its certificate. Signing compatibility does not verify guest-data
migration or application behavior. In particular this packaging change does
not carry over the local independent-build port overrides (SSH 19932, VNC 15910,
audio 14723) or unfinished host-service features; this checkout still uses its
source defaults. Do not install it over a working VM before validating those
runtime differences and any customized guest overlay.
