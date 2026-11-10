# RedrainCast v0.4.17

Embedded version: `0.4.17` (version code 30).

## Highlights

- Improve the playback statistics panel layout with clearer grouped metrics,
  aligned values and consistent spacing.
- Keep statistics columns visible at large accessibility font sizes.
- Update the statistics panel documentation and layout regression coverage.
  No media transport, decoder or audio behavior changes are included.

## Validation and known limits

- Two independent reviews of the statistics panel changes found no actionable
  bugs. Automated UI regressions cover the panel layout and large font sizes.
- Release validation includes Android JVM tests, lint, compilation and the
  optimized release build; signing and exact-build relink checks cover all
  three packaged ABIs. These checks are not device or performance acceptance.
- Physical Android/Windows Stop interoperability and long-running mixed-media
  acceptance remain untested.
- The intermittent tiny-strip picture after SDR rotation remains unresolved.
  Nonzero HDR rotation remains rejected. Prior decoded/presented HDR output
  does not establish HDR scanout or projection acceptance.
- Native crashes, OS kills and network loss can prevent terminal error delivery.

## Installation

`RedrainCast-0.4.17.apk` supports Android 11/API 30 or newer and includes
arm64-v8a, armeabi-v7a and x86_64 native libraries.

Version code 30 permits in-place updates from production-signed 0.4.16/code 29.
The production certificate SHA-256 remains
`79f906220de117d61c2d15bdfcef179a4e3e48cfa0c76b637c7af986c037fad3`.
Debug installations use a separate application ID and are not replaced.
Older debug-signed installations using the production ID cannot be updated in
place; uninstalling loses their local data. Publishing does not change devices.

Verify downloads using the attached `SHA256SUMS` and signing certificate report.

## Licensing and privacy

Public assets comprise only the production APK, checksums, certificate report,
licenses and exact-build LGPL relink materials. Application source remains
private; GitHub source archives contain only the public downloads repository.
The relink kit binds the signed APK and source revision to matching native
objects and public FFmpeg materials for all three ABIs. Private application
source, developer paths, build logs and signing credentials are excluded.
