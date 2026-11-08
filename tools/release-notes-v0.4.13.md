# RedrainCast v0.4.13

The embedded app version is `0.4.13` (version code 26). This owner-authorized
stable release adds strictly negotiated HDR10 reception, evidence-based video
path statistics and a separate debug app identity.

## Highlights

- HDR10 requires HEVC Main10, 10-bit 4:2:0, BT2020/PQ/BT2020nc limited range,
  a compatible hardware decoder and an HDR10-capable attached display.
  Capability changes and unsupported configurations fail explicitly rather
  than silently claiming HDR output or falling back to SDR.
- SDR keeps the existing TextureView fit/rotation path. HDR uses a secure
  framework SurfaceView at rotation zero. Nonzero HDR rotation is explicitly
  unsupported; no experimental child-SurfaceControl bridge is used.
- Encoded mastering chromaticity comparison tolerates only one fixed 1/50000
  quantum for hardware quantization. Negotiated wire metadata, luminance,
  light levels, profile and VUI remain strict. Both HDR metadata envelopes
  retain the original 2048-byte UTF-8 bound, including whitespace.
- Decoder rejection diagnostics report actual device constraints and preserve
  safe causes. Local statistics v2 separates incoming range, decoder-reported
  profile/depth and output supported by matched frame-rendered callbacks.
  Opaque output depth, physical HDR emission and OEM display state stay unknown.
- Debug builds use `dev.redrain.cast.debug`, label **RedrainCast Debug** and
  version `0.4.13-debug`, allowing side-by-side installation with release.
  Their updater compares the base version and never offers remote debug tags.
  The release application ID and source namespace remain unchanged.
- Shared protocol/statistics fixtures preserve the Windows contracts.
  This release does not change Windows source or pinned native dependencies.

## Validation and known limits

- Fresh local compilation and 589 tests across 81 suites passed, with zero
  failures, errors or skips. Lint reported zero errors and 11 warnings;
  debug, release and instrumentation APK builds passed with verified identities.
  The four native-graphics overlay tests unavailable on Windows ARM64 were
  excluded locally, not counted as passes and not weakened or excluded from CI.
  Local Python aliases were unavailable; release tooling is validated in CI.
- Full Linux CI and the exact merged-main release workflow must pass all unit
  tests, lint, optimized APK build, release-tooling regression tests, production
  signing and three-ABI exact-build LGPL relink checks before publication.
- No new real-device HDR projection, HDR rotation, physical scanout or live
  Windows interoperability acceptance is established. Owner authorization
  accepts these limitations; it is not evidence of successful device tests.
- The intermittent tiny-strip picture after SDR rotation remains unresolved.
  Nonzero HDR rotation is rejected, not claimed fixed or tested.
- Dedicated native acceptance of earlier audio-startup correction and
  sustained mixed-media recovery is not newly established.
- Controlled surface/activity retirement, native crashes, OS kills and network
  loss can still prevent a terminal explanation from reaching the peer.

## Download and installation

`RedrainCast-0.4.13.apk` is one universal production APK for Android 11/API 30
or newer, with arm64-v8a, armeabi-v7a and x86_64 native libraries.

The production certificate remains SHA-256
`79f906220de117d61c2d15bdfcef179a4e3e48cfa0c76b637c7af986c037fad3`.
Version code 26 permits in-place updates from production-signed 0.4.12/code 25.
It cannot replace older debug-signed installations using the release ID.
Uninstalling loses app data; publication does not deploy, launch, grant
permissions or change settings on any device.

Verify the APK against the attached `SHA256SUMS` and certificate report.

## Licensing and privacy

Application source remains private. Only the production APK, checksums,
certificate report, notices/licenses and matching LGPL relink kit are public.
GitHub's automatic source archives contain only the public downloads repository.

`android-lgpl-relink.tar.gz` binds the signed APK and merged source revision to
matching native objects for all three ABIs and corresponding public FFmpeg
source/build materials. It excludes private application source, developer
paths, build logs and signing credentials.
