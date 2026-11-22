# RedrainCast v0.5.3

Embedded version: `0.5.3` (version code 37).

## Highlights

- Bound audio-clock and presentation-timing state for playback scheduling.
- Improve bounded PTS and audio-clock coordination, while preserving legacy
  timing negotiation compatibility.
- Add independently consented remote diagnostics and new negotiated video timing
  fields for diagnosis. Diagnostic capture remains opt-in and limited to a
  trusted LAN.
- Retire generation-scoped scheduling state promptly and preserve existing
  recovery and retirement behavior.
- Expand deterministic coverage for timing bounds, audio/video coordination,
  diagnostics admission, generated collector contracts and schedule retirement.

## Validation and known limits

- Release tooling and LAN diagnostics contract tests pass with the repository's
  Python tooling.
- Android unit tests, lint, and debug/release builds are validated through the
  repository build before publication.
- Three review rounds found no actionable findings.
- Frame scheduling observations do not prove physical display scanout.
  Presentation smoothness varies by encoder, decoder, device load and network
  conditions.
- Automated validation does not establish physical-device rendering, shutdown,
  all native/runtime paths or Windows interoperability acceptance.

## Installation

`RedrainCast-0.5.3.apk` supports Android 11/API 30 or newer and includes
`arm64-v8a`, `armeabi-v7a` and `x86_64`. Version code 37 permits updating
version code 36 installations only when the production signing certificate
matches. Debug-signed installations use a separate application ID and are not
replaced by this production release.

## Downloads and source

Verify downloads using the attached `SHA256SUMS` and signing certificate
report. Matching license materials and the exact-build LGPL relink kit are
included. This is the stable v0.5.3 release.
