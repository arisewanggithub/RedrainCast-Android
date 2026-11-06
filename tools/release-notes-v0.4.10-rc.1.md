# RedrainCast v0.4.10-rc.1

This is a **testing prerelease**, not a stable-release claim. The Android app's
embedded version is `0.4.10-rc.1` (version code 21).

## Changes since v0.4.6

- Recovery no longer rejects a valid decoded-key proof solely because decode or
  control transit took more than 250 ms. Source admission freshness, original
  timestamps, queue limits and the first-unresolved ten-second deadline remain.
- Incomplete Opus coverage waits without sorting or rejecting valid timestamp
  reversals. Actual required-prefix eviction still triggers a bounded retry.
- Startup audio catch-up protects original source samples while native output
  begins, without enlarging buffers or changing AudioTrack's nominal rate.
- Control remains open through bounded media/playback retirement. Late valid
  receiver errors can supersede provisional media EOF; intentional startup
  cancellation is not misreported as a missing-acknowledgement protocol failure.
- Startup update checks consider **stable releases only** and open the browser
  only after an explicit choice. Prompts defer to casting, consent and errors.
- Future prerelease tags, embedded versions and APK filenames share their full
  `-alpha.N`, `-beta.N` or `-rc.N` suffix.

## Known issues and acceptance limits

- The intermittent tiny-strip picture after the first rotation remains
  unresolved. Diagnostic build success is not a native rendering baseline.
- Casting to the JMGO N5 Pro was observed to disconnect shortly after the first
  keyframe. Its exact receiver-side cause is not confirmed. Better teardown
  diagnostics do not guarantee delivery after a native crash, OS kill or loss
  of connectivity.
- Native acceptance of the latest audio-startup correction is pending; its JVM
  source-preservation regressions pass, but device success is not claimed.
- Live Android/Windows interoperability and sustained mixed-media recovery are
  not newly verified. Older recovery-v1 peers may retain stricter receipt-age
  rules; update both endpoints to benefit from the corrected behavior.
- Stable-only startup checks intentionally do **not** offer this RC.

## Download and installation

`RedrainCast-0.4.10-rc.1.apk` is one universal APK for Android 11/API 30 or newer,
with arm64-v8a, armeabi-v7a and x86_64 native libraries.

It uses the same production certificate as the public v0.4.6 testing release:
SHA-256 `79f906220de117d61c2d15bdfcef179a4e3e48cfa0c76b637c7af986c037fad3`.
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
