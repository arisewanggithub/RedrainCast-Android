# RedrainCast v0.4.15

The embedded app version is `0.4.15` (version code 28). This release improves
independent media recovery, presentation timing and video capture defaults.

## Highlights

- Negotiated stream recovery v2 uses separate video and audio generations.
  Recovery on one track does not pause or recreate the healthy track. Initial
  startup still waits for all enabled tracks to be ready.
- Audio recovery retains bounded decoder preroll and validates source coverage
  against audio packets actually written. The shared playback clock remains
  continuous across track-local recovery.
- Each unresolved track keeps its original ten-second recovery deadline.
  Recovery no longer inherits an expired session-setup deadline, while control
  liveness and transport deadlines remain active.
- Successful in-flight audio writes finishing after Stop or retirement no
  longer produce a false recovery timeout or invalidate a clean size switch.
  Active recovery deadlines and real transport failures are still enforced.
- Video defaults align with scrcpy: optional capture cap, 60-FPS encoder hint,
  ten-second keyframe interval, limited color range, real-time priority and
  one-frame requested latency. The 1920-pixel maximum size is unchanged.
- Settings label the limit **Maximum capture FPS**, with **Uncapped (default)**
  followed by common caps. Existing saved caps, codecs and bitrates are preserved.
  Audio defaults and encoding behavior are unchanged.

## Compatibility and validation

- Uncapped capture requires a receiver explicitly advertising
  `uncappedFps:true`. Older receivers need an explicit positive FPS cap until
  updated; unsupported selections fail clearly rather than silently imposing
  a cap.
- Track-local recovery requires matching negotiated `streamRecoveryVersion:2`.
  This release does not establish interoperability with every older peer.
- Recovery regression coverage includes both track deadlines after long
  playback, control-read liveness, startup readiness, late writes during Stop
  and retirement, and genuine transport errors. The focused 91-test recovery
  suite and lint passed before merge, followed by full Android CI.
- The exact release source must pass full unit tests, lint, optimized APK build,
  release-tooling tests, production signing and three-ABI LGPL relink checks
  before publication.

## Known limits

- No new physical HDR scanout, HDR projection, long-running mixed-media or live
  Windows interoperability acceptance is claimed.
- The intermittent tiny-strip picture after SDR rotation remains unresolved.
  Nonzero HDR rotation is rejected, not claimed fixed or newly tested.
- Native crashes, OS kills, network loss and controlled retirement can still
  prevent a terminal explanation from reaching the peer.

## Download and installation

`RedrainCast-0.4.15.apk` is a universal production APK for Android 11/API 30 or
newer, with arm64-v8a, armeabi-v7a and x86_64 native libraries.

Version code 28 permits in-place updates from production-signed 0.4.14/code 27.
The production certificate remains SHA-256
`79f906220de117d61c2d15bdfcef179a4e3e48cfa0c76b637c7af986c037fad3`.
It cannot replace older debug-signed installations using the release ID.
Uninstalling loses app data; publication does not install or alter any device.

Verify downloads against the attached `SHA256SUMS` and certificate report.

## Licensing and privacy

Application source remains private. Only the production APK, checksums,
certificate report, notices/licenses and matching LGPL relink kit are public.
GitHub's source archives contain only the public downloads repository.

`android-lgpl-relink.tar.gz` binds the signed APK and merged source revision to
matching native objects for all three ABIs and public FFmpeg source/build
materials. Private application source, developer paths, logs and signing
credentials are excluded.
