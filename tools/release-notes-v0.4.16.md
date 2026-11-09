# RedrainCast v0.4.16

Embedded version: `0.4.16` (version code 29).

## Highlights

- Fix uncapped HDR decoder selection: an absent FPS cap checks supported raster
  size without imposing a fallback cap. Positive caps retain size/rate checks,
  hardware/profile requirements and explicit rejection diagnostics.
- Improve negotiated Android/Windows orderly Stop behavior. Started media frames
  retain their original write deadlines; acknowledgement timers begin only after
  successful terminal delivery. Legacy teardown limits remain unchanged.
- Handle crossed Stops without duplicate terminal writes, halt healthy output
  before blocked recovery retirement, and reject late replacement installation.
- Coordinate media EOF with terminal-write completion without hiding actual write
  failures. Atomically arbitrate local/peer Stop and preserve fatal ERROR priority
  over queued orderly terminals.

## Validation and known limits

- Merged Stop changes passed full Android CI, including unit tests, lint, debug
  and optimized release builds. Deterministic regressions cover terminal-write
  ordering, crossed Stops and output suppression during recovery retirement.
- Uncapped HEVC HDR startup at 3456x2160 was verified on a Galaxy with decoded and
  presented output. This does not establish HDR scanout or projection acceptance.
- Physical Android/Windows Stop interoperability and long-running mixed-media
  acceptance remain untested.
- The intermittent tiny-strip picture after SDR rotation remains unresolved.
  Nonzero HDR rotation remains rejected.
- Native crashes, OS kills and network loss can prevent terminal error delivery.

## Installation

`RedrainCast-0.4.16.apk` supports Android 11/API 30 or newer and includes
arm64-v8a, armeabi-v7a and x86_64 native libraries.

Version code 29 permits in-place updates from production-signed 0.4.15/code 28.
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
