# RedrainCast v0.5.4

Embedded version: `0.5.4` (version code 38).

## Highlights

- Capture bounded, sender-owned incident evidence and session telemetry to help
  investigate media pressure and shutdown behavior.
- Add receiver control telemetry and audio-start observations, with compatibility
  fallback for peers advertising newer telemetry versions.
- Improve final evidence delivery, cancellation ordering, resource cleanup and
  receiver release accounting during shutdown and recovery.
- Expand tests for diagnostic evidence, interoperability and resource lifetimes.

## Validation and known limits

- Automated tests and release checks do not establish physical-device rendering,
  shutdown, all native/runtime paths or Windows interoperability acceptance.
- The cause of the observed live audio pressure has not been established, and
  this release does not claim to fix that playback problem. Presentation
  smoothness varies by encoder, decoder, device load and network conditions.
- Diagnostic evidence can contain sensitive operational information; share it
  only with trusted recipients.

## Installation

`RedrainCast-0.5.4.apk` supports Android 11/API 30 or newer and includes
`arm64-v8a`, `armeabi-v7a` and `x86_64`. Version code 38 permits updating
earlier installations only when the production signing certificate matches.
Debug-signed installations use a separate application ID and are not replaced
by this production release.

## Downloads and source

Verify downloads using the attached `SHA256SUMS` and signing certificate
report. Matching license materials and the exact-build LGPL relink kit are
included. This is the stable v0.5.4 release.
