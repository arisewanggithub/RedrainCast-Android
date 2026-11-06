# RedrainCast v0.4.10-rc.2

This is a **testing prerelease**, not a stable-release claim. The Android app's
embedded version is `0.4.10-rc.2` (version code 22).

## Changes since v0.4.10-rc.1

- TV, Leanback and declared landscape-only receivers keep their application
  window landscape, including when the sender selects Auto or Portrait receiver
  orientation. Portrait pictures remain aspect-fitted; per-picture rotation,
  encoder raster, codecs and quality are unchanged.
- Phones and tablets retain automatic orientation following the displayed
  video, explicit Portrait/Landscape overrides and restoration of their prior
  orientation policy after the session.
- Regression coverage checks platform classification, every receiver mode,
  all four picture rotations, fitted bounds and session ownership/cleanup.

## Projector findings and acceptance limits

- On the JMGO N5 Pro running the previous RC, the user confirmed that Landscape
  connects while Auto and Portrait disconnect. Video-only H.264 also reproduced
  the disconnect; audio is not required to trigger it. These observations
  identify the orientation trigger, not the projector's underlying exception.
- The new fixed-landscape guard passed 37 focused JVM tests plus build/lint
  checks before release preparation. It still needs a physical projector retry;
  no live success of this new build is claimed.
- Detection uses Android's TV/Leanback or landscape-only declarations. Devices
  without those declarations retain phone/tablet behavior; selecting Landscape
  remains the workaround for affected vendor devices.
- The intermittent tiny-strip picture after rotation remains unresolved.
  Windows interoperability and sustained mixed-media recovery are not newly
  validated by this release.
- Native acceptance of the earlier audio-startup correction remains pending.
- Control-last teardown cannot guarantee remote diagnostics after an abrupt
  process exit or a lost connection.
- Stable-only startup update checks intentionally do **not** offer this RC.

## Download and installation

`RedrainCast-0.4.10-rc.2.apk` is one universal APK for Android 11/API 30 or newer,
with arm64-v8a, armeabi-v7a and x86_64 native libraries.

It uses the same production certificate as the public v0.4.6 and v0.4.10-rc.1
testing releases: SHA-256
`79f906220de117d61c2d15bdfcef179a4e3e48cfa0c76b637c7af986c037fad3`.
A production-signed APK cannot replace a debug-signed development installation
in place. Back up app settings before choosing to uninstall; uninstalling loses
local app data. No script automatically changes, uninstalls or grants
permissions on your phones/projector.

Verify the APK against `SHA256SUMS` and the attached signing report.

## Licensing and privacy

Application source remains private. The public release contains only the signed
APK, checksums, certificate report, notices/licenses and matching LGPL relink
materials. GitHub's automatic source archives refer to the public downloads
repository's documentation, not the private application source.

`android-lgpl-relink.tar.gz` supplies corresponding FFmpeg source/build
materials and the matching application-native objects and instructions needed
to relink the LGPL-linked library with modified FFmpeg. These are provided
under the LGPL 2.1 section 6(a) object/relink option; private JNI/Kotlin/Java
source and signing credentials are not included.
