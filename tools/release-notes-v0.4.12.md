# RedrainCast v0.4.12

The embedded app version is `0.4.12` (version code 25). This official release
clarifies receiver display statistics and aligns the lower statistics rows.
It does not change rendering, capture quality, codecs, the media protocol,
queue budgets, audio compensation or native dependencies.

## Highlights

- **Display px** replaces the misleading Canvas px in the main panel. It shows
  the attached display's reported active Android display-mode dimensions,
  separately from the incoming video's **Encoded** size.
- Display px is not a measurement of the final rendered video buffer, physical
  scanout or native optical projection resolution. No view or display-mode
  dimensions are substituted for an unavailable render-target measurement.
- App view size, the unavailable render-target value and measurement
  explanations remain in scrollable Full playback diagnostics, not the main
  panel.
- Footer values now start directly under State, Generation, Play epoch and
  Occluded. Scheduled frames, Audio epoch and Device played use three equal
  title-over-value columns instead of a crowded inline row.
- Compact mode retains 10sp text and all existing metrics, respecting system
  font scaling. The generation-total qualifier shares the header line to
  preserve the compact footprint.

## Validation and known limits

- Three independent reviewers found no significant actionable issues.
- Model and native-rendered UI tests cover measurement separation, diagnostic
  visibility, label/value alignment and compact portrait/landscape fit at
  115% font scaling. The private release workflow validates the exact committed
  revision with unit tests, lint, an optimized build and three-ABI LGPL relink
  checks before signing/publication.
- These checks do not establish physical display scanout, native projector
  resolution or new live Android/Windows interoperability acceptance.
- The intermittent tiny-strip picture after rotation remains unresolved.
- Dedicated native acceptance of the earlier audio-startup correction and
  sustained mixed-media recovery is not newly established by this release.
- Controlled surface/activity retirement, native crashes, OS kills and network
  loss can still prevent a terminal explanation from reaching the peer.

## Download and installation

`RedrainCast-0.4.12.apk` is one universal APK for Android 11/API 30 or newer,
with arm64-v8a, armeabi-v7a and x86_64 native libraries.

The production certificate is unchanged:
SHA-256 `79f906220de117d61c2d15bdfcef179a4e3e48cfa0c76b637c7af986c037fad3`.
Version code 25 supports an in-place update from production-signed
0.4.11/code 24. It cannot replace a debug-signed development installation in
place. Uninstalling an existing app loses local data; this release does not
automatically uninstall, launch, grant permissions or change device settings.

Verify the APK against the attached `SHA256SUMS` and signing certificate.

## Licensing and privacy

Application source remains private. The five public assets are the
production-signed APK, checksums, certificate report, notices/licenses and the
matching LGPL relink kit. GitHub's automatic source archives contain only the
public downloads repository, not this private application.

`android-lgpl-relink.tar.gz` contains corresponding FFmpeg source/build
materials, matching application-native objects for all three ABIs and relinking
instructions. It does not include private application source, build logs,
developer paths or signing credentials.
