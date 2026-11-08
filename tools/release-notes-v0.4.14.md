# RedrainCast v0.4.14

The embedded app version is `0.4.14` (version code 27). This release improves
editing in Sharing settings without changing media or protocol behavior.

## Highlights

- FPS uses a non-editable Android single-choice picker with the same common
  options as Windows: 15, 24, 25, 30, 48, 50, 60, 90 and 120. The default
  remains 60. Previously saved custom caps remain selectable alongside the
  sorted presets; new arbitrary FPS values cannot be typed.
- Numeric settings inputs select their full value when gaining focus, making
  replacement easier with a keyboard or remote. Subsequent taps in an already
  focused field retain normal cursor placement for individual edits.
- Choices and text edits remain drafts until Save; Cancel preserves saved
  settings. Regression tests cover presets, custom caps, focus and replacement.

## Validation and known limits

- The PR and exact merged-main release candidate must pass full Linux unit
  tests, lint and optimized APK builds before publication. The release workflow
  also validates release tooling, production signing and exact-build LGPL
  relinking for all three ABIs. Local tests were blocked by a missing JDK/SDK.
- No new device-runtime or live Windows interoperability acceptance is
  established. These settings changes do not establish HDR physical scanout,
  HDR projection or sustained mixed-media recovery acceptance.
- The intermittent tiny-strip picture after SDR rotation remains unresolved.
  Nonzero HDR rotation is rejected, not claimed fixed or newly tested.
- Controlled surface/activity retirement, native crashes, OS kills and network
  loss can still prevent a terminal explanation from reaching the peer.

## Download and installation

`RedrainCast-0.4.14.apk` is one universal production APK for Android 11/API 30
or newer, supporting arm64-v8a, armeabi-v7a and x86_64.

The production certificate remains SHA-256
`79f906220de117d61c2d15bdfcef179a4e3e48cfa0c76b637c7af986c037fad3`.
Version code 27 permits in-place updates from production-signed 0.4.13/code 26.
It cannot replace older debug-signed installations using the release ID.
Uninstalling loses app data; this release does not deploy or change any device.

Verify the APK against the attached `SHA256SUMS` and certificate report.

## Licensing and privacy

Only the production APK, checksums, certificate report, notices/licenses and
matching LGPL relink kit are public. Application source remains private;
GitHub's source archives contain only the public downloads repository.

`android-lgpl-relink.tar.gz` binds the signed APK and merged source revision
to matching native objects for all three ABIs and public FFmpeg source/build
materials. It excludes private application source, developer paths, build logs
and signing credentials.
