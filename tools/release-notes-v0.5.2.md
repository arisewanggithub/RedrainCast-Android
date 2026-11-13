# RedrainCast v0.5.2

Embedded version: `0.5.2` (version code 36).

## Highlights

- Fix receiver catch-up discarding decoded pictures before checking their
  presentation deadlines. Future and exactly-due pictures are scheduled;
  genuinely late pictures can still be skipped to catch up.
- Preserve nonblocking timed presentation, existing buffering bounds and the
  eight-picture replacement budget. Stop catch-up promptly after retirement.
- Add deterministic coverage for future/due/late pictures, clock advancement,
  bounded replacement, retirement and interruption.

## Validation and known limits

- The scheduling regression was reproduced before the fix. The corrected path
  passed 84 targeted tests and the full 882-test Android JVM suite; lint reported
  no errors. Two swarm review rounds found no actionable issues.
- A debug build was installed and launched on OnePlus, and the same APK was
  copied to Galaxy. Physical playback smoothness was not measured; occasional
  judder from other causes may remain.
- Scheduled/rendered-frame observations do not prove physical display scanout.
  Results may vary by encoder, decoder, device load and network conditions.
- Automated validation does not establish physical-device rendering, shutdown,
  all native/runtime paths or Windows interoperability acceptance.
- Remote diagnostics remain opt-in, plaintext and unauthenticated. Use only on
  a trusted LAN.

## Installation

`RedrainCast-0.5.2.apk` supports Android 11/API 30 or newer and includes
`arm64-v8a`, `armeabi-v7a` and `x86_64`. Version code 36 permits updating
version code 35 installations only when the production signing certificate
matches. Debug-signed installations use a separate application ID and are not
replaced by this production release. Do not uninstall automatically to switch
signing identities.

## Downloads and source

Verify downloads using the attached `SHA256SUMS` and signing certificate
report. Matching license materials and the exact-build LGPL relink kit are
included. This is the stable v0.5.2 release.
