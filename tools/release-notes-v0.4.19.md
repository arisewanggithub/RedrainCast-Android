# RedrainCast v0.4.19

Embedded version: `0.4.19` (version code 32).

## Highlights

- New Android releases and stable update checks use the public
  [RedrainCast-Android repository](https://github.com/arisewanggithub/RedrainCast-Android/releases).
  Existing installations through v0.4.18 still check the old destination until
  manually updated.
- Publish with an isolated, same-repository GitHub token rather than a
  cross-repository publisher credential; signing remains read-only.
- Atomically reserve a new release tag at the reviewed source commit and verify
  its peeled commit before and after draft upload. Existing tags and lookup
  failures stop publication instead of silently reusing mismatched source.
- Retain the Android receiver video configuration and Surface lifetime fixes
  shipped in v0.4.18. This release adds no new media-path behavior.

## Validation and known limits

- Focused regressions cover strict updater URLs, release permissions, tag
  creation conflicts/races, annotated tags and exact reviewed source binding.
- Release validation covers JVM tests, lint, optimized builds, production
  signing and exact-build LGPL relinking for all three ABIs. These checks are
  not physical-device, performance or Windows interoperability acceptance.
- Native codec/AudioTrack stalls remain possible and were not rearchitected.
- Physical Android/Windows Stop interoperability and long-running mixed-media
  acceptance remain untested. The intermittent tiny-strip picture after SDR
  rotation remains unresolved; nonzero HDR rotation remains rejected.
- HDR metadata/output observations do not prove physical HDR scanout.
- Native crashes, OS kills and network loss can prevent terminal error delivery.

## Installation

`RedrainCast-0.4.19.apk` supports Android 11/API 30 or newer, with arm64-v8a,
armeabi-v7a and x86_64 libraries. Code 32 permits production-signed updates from
v0.4.18/code 31. The production certificate SHA-256 remains
`79f906220de117d61c2d15bdfcef179a4e3e48cfa0c76b637c7af986c037fad3`.
Debug installations use a separate application ID. Older debug-signed
installations using the production ID cannot update in place; uninstalling loses
local data. Publication does not install or change anything on devices.

## Licensing and source binding

Verify downloads using `SHA256SUMS` and the signing certificate report. The five
assets are the production APK, checksums, certificate, licenses and exact-build
LGPL relink materials. GitHub's automatic source archives now contain public
Android source at the verified release tag. The relink manifest binds the signed
APK and source revision to matching native objects and public FFmpeg materials.
The relink kit excludes developer paths, build logs, signing keys and credentials.
