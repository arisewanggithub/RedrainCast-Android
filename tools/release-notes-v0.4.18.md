# RedrainCast v0.4.18

Embedded version: `0.4.18` (version code 31).

## Highlights

- Keep Android receiver codec construction and presentation startup outside the
  shared playback lock, preserving healthy-track admission during reconfiguration.
- Serialize Surface retirement with worker installation and READY acknowledgement.
  Configuration losing its Surface lifetime retires normally without a false cast
  error; genuine codec/setup failures still use the normal error channel.
- Avoid recovery-proof locking when no recovery key is pending.

## Validation and known limits

- Two independent source reviews found no actionable introduced issues.
- Deterministic regressions cover healthy-track progress, initial readiness,
  configuration/Stop/Surface races, teardown ordering and genuine setup failures.
- Release validation covers JVM tests, lint, optimized builds, production signing
  and exact-build LGPL relinking for all three ABIs. This is not device or
  performance acceptance. No physical Android receiver test was performed.
- Native codec/AudioTrack stalls remain possible and were not rearchitected.
- Physical Android/Windows Stop interoperability and long-running mixed-media
  acceptance remain untested. The intermittent tiny-strip picture after SDR
  rotation remains unresolved; nonzero HDR rotation remains rejected.
- HDR metadata/output observations do not prove physical HDR scanout.
- Native crashes, OS kills and network loss can prevent terminal error delivery.

## Installation

`RedrainCast-0.4.18.apk` supports Android 11/API 30 or newer, with arm64-v8a,
armeabi-v7a and x86_64 libraries. Code 31 permits production-signed updates from
0.4.17/code 30. The production certificate SHA-256 remains
`79f906220de117d61c2d15bdfcef179a4e3e48cfa0c76b637c7af986c037fad3`.
Debug installations use a separate application ID. Older debug-signed
installations using the production ID cannot update in place; uninstalling loses
local data. Publication does not install or change anything on devices.

## Licensing and privacy

Verify downloads using `SHA256SUMS` and the signing certificate report. Public
assets contain only the production APK, checksums, certificate, licenses and
exact-build LGPL relink materials. Application source remains private; automatic
source archives contain only the public downloads repository. The relink kit
binds the signed APK and source revision to matching native objects and public
FFmpeg materials, excluding private source, developer paths, logs and credentials.
