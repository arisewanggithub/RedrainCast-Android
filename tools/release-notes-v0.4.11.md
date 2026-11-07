# RedrainCast v0.4.11

The embedded app version is `0.4.11` (version code 24). This release adds
optional receiver playback statistics and a denser phone layout. It does not
change the media protocol version, capture quality, codecs, queue budgets,
AudioTrack rate or FFmpeg native dependencies.

## Highlights

- **Show playback statistics on receiver (next cast)** is at the bottom of
  Settings > Advanced. It is off by default and saved only when settings are
  saved. Changing it does not alter an already selected cast.
- Updated Android and Windows peers negotiate the optional receiver feature.
  Older peers continue casting without an overlay; a sender requesting an
  unavailable feature retains a nonfatal notice instead of failing the cast.
- The translucent, noninteractive panel distinguishes received, decoded and
  actually rendered video; requested FPS and configured bitrate are not
  presented as measured throughput. Renderer skips are not network packet loss.
- Phone mode retains all statistics using dense 10sp text, shorter labels,
  measured column widths and shared units, while respecting system font
  scaling. Four FPS indicators and three equal generation-total blocks remain
  aligned, with each total's number below its label.
- Cached diagnostics include PCM buffer/target/padding, silence/discard totals
  and recent deltas, source A/V skew, waiting encoded queues, last-packet ages
  and matching-heartbeat control RTT. Unknown values show N/A. Android's audio
  epoch is not mislabeled as a full playback epoch, and unmeasured occlusion
  remains N/A.
- Full playback diagnostics can also be opened from Advanced settings or by
  holding the receiving video. Its separate view scrolls to the final metric.
  A new incoming session closes any idle diagnostics dialog before playback.
- Skew comparisons require original monotonic observation times within 10 ms,
  both no more than 250 ms old. Incomparable or unavailable observations show
  N/A and cannot inflate the observed peak. No extra native output query or
  source-clock extrapolation is added.

## Validation and limits

- The reviewed feature passed 521 JVM tests, Android lint with no errors, and
  debug/release/instrumentation-APK builds before release preparation. The
  private release workflow separately validates the exact committed version
  before signing.
- Native-rendered synthetic UI tests cover the reported phone-sized viewport
  at 115% font scaling, complete inner-text bounds, input passthrough, large
  counts and scrollable full diagnostics. These are not physical-display
  scanout or live mixed-device acceptance tests.
- Statistics use typed caches sampled once per second. Skew is source video
  minus played-source audio, positive for video ahead, not end-to-end latency.
  Comparable observations may still be separated by up to 10 ms. The displayed
  peak is a maximum of valid 1 Hz observations, not a per-frame maximum.
- Android Presented counts MediaCodec frame-rendered callbacks; Windows counts
  successful presentation API calls. Neither independently proves scanout.
- The intermittent tiny-strip picture after rotation remains unresolved.
- Dedicated native acceptance of the earlier audio-startup correction and
  sustained mixed-media recovery is not newly established by this release.
- Controlled surface/activity retirement, native crashes, OS kills and network
  loss can still prevent a terminal explanation from reaching the peer.
- Optional-feature protocol fixtures are not a claim of comprehensive live
  Android/Windows interoperability.

## Download and installation

`RedrainCast-0.4.11.apk` is one universal APK for Android 11/API 30 or newer,
with arm64-v8a, armeabi-v7a and x86_64 native libraries.

The production certificate is unchanged:
SHA-256 `79f906220de117d61c2d15bdfcef179a4e3e48cfa0c76b637c7af986c037fad3`.
Version code 24 supports an in-place update from production-signed
0.4.10/code 23. It cannot replace a debug-signed development installation in
place. Uninstalling an existing app loses local data; this release does not
automatically uninstall, launch, grant permissions or change phone settings.

Verify the APK against the attached `SHA256SUMS` and signing certificate.

## Licensing and privacy

Application source remains private. The five public assets are the
production-signed APK, checksums, certificate report, notices/licenses and the
matching LGPL relink kit. GitHub's automatic source archives contain the public
downloads repository, not this private application.

`android-lgpl-relink.tar.gz` contains corresponding FFmpeg source/build
materials, matching application-native objects for all three ABIs and relinking
instructions. It does not include private application source, build logs,
developer paths or signing credentials.
