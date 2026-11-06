# RedrainCast v0.4.10

This is an **owner-authorized stable release** following the user's successful
JMGO N5 Pro connection retry on v0.4.10-rc.2. The embedded app version is `0.4.10`
(version code 23). The media and orientation implementation is unchanged from
RC2; stable designation does not claim the separate known issues below are fixed.

## Highlights

- One native Android application sends or receives one active session at a time,
  using independent control, video and audio TCP connections.
- Declared landscape-only and portrait-only receivers keep their supported
  window axis in every receiver orientation mode. TV and Leanback receivers
  otherwise keep their startup window axis. Video remains aspect-fitted, with
  per-picture rotation and a fixed encoder raster.
- The user confirmed that the new guard fixes the projector connection issue
  previously triggered by Auto or Portrait receiver orientation.
- Portable displays retain automatic video-following orientation, explicit
  Portrait/Landscape choices and restoration of their prior orientation policy.
- Recovery preserves original timestamps and bounded queues, accepts delayed
  validated key proofs, and waits for actual source-audio coverage.
- Startup audio catch-up protects original source samples without enlarging
  buffers or changing AudioTrack's nominal rate.
- Startup update checks consider stable releases only. An installed 0.4.10 RC
  recognizes this same-base final version as newer; browser download remains an
  explicit choice, with no in-app installer or automatic permission grants.

## Known limitations

- The intermittent tiny-strip picture after rotation remains unresolved.
  The projector connection retry does not validate that separate rendering path.
- Controlled receiver activity/surface retirement can still close connections
  without a terminal explanation. That shutdown-reporting gap is separate from
  the fixed-axis guard. Crashes and network loss cannot guarantee error delivery.
- Fixed-axis detection depends on Android's TV/Leanback or single-axis
  declarations. Affected vendor devices without those declarations may still
  need an explicit supported receiver orientation.
- Dedicated native acceptance of the earlier audio-startup correction and
  sustained mixed-media recovery is not newly completed by this release.
- Live Windows interoperability is not comprehensively verified by this
  projector retry; no Windows code changes are included.

## Download and installation

`RedrainCast-0.4.10.apk` is one universal APK for Android 11/API 30 or newer,
supporting arm64-v8a, armeabi-v7a and x86_64.

The production certificate is unchanged from the public testing releases:
SHA-256 `79f906220de117d61c2d15bdfcef179a4e3e48cfa0c76b637c7af986c037fad3`.
Version code 23 permits an in-place update from production-signed RC2/code 22.
A production-signed APK cannot replace a debug-signed development installation
in place; choosing to uninstall loses local app data. No script automatically
uninstalls apps, changes device settings or grants capture/microphone consent.

Verify the APK against the attached `SHA256SUMS` and signing certificate report.

## Licensing and privacy

Application source remains private. The public assets are the signed APK,
checksums, certificate report, notices/licenses and matching LGPL relink kit.
GitHub's automatic source archives contain public download documentation, not
the private application source.

`android-lgpl-relink.tar.gz` contains corresponding FFmpeg source/build materials,
matching application-native objects for all three ABIs, and instructions to
relink with modified FFmpeg under the LGPL 2.1 section 6(a) object/relink option.
Private application source and signing credentials are not included.
