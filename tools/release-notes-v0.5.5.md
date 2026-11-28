# RedrainCast v0.5.5

Embedded version: `0.5.5` (version code 39).

## Highlights

- Redesign Cast settings with independent **Send video** and **Send audio**
  controls for audio-only and video-only sessions.
- Add fullscreen capture that ignores crop while preserving saved crop and
  dormant media-setting choices for later use.
- Expand bounded, opt-in diagnostics for paired sender/receiver troubleshooting.
- Bound the encoded audio queue to a one-second presentation-timestamp span.

## Validation and known limits

- High-frame-rate moving content with both audio and video can still experience
  audio queue pressure. This issue is unresolved; lowering the selected frame
  rate may reduce pressure. This release does not claim to fix it.
- A prior run of
  `StreamRecoveryPipelineTest.slowAudioConstructionKeepsVideoDecoderAndSourceClockContinuous`
  reached its 10-second timeout; a later rerun passed, but the original timeout
  remains unexplained and is not considered resolved.
- Automated checks do not establish physical-device rendering, all native/runtime
  paths or Windows interoperability. Virtual and hybrid audio modes are
  development experiments, disabled in this production release.
- Diagnostic evidence may contain sensitive operational information; share it
  only with trusted recipients.

## Installation

`RedrainCast-0.5.5.apk` supports Android 11/API 30 or newer and includes
`arm64-v8a`, `armeabi-v7a` and `x86_64`. Version code 39 permits updating
earlier installations only when the production signing certificate matches.
Debug-signed installations use a separate application ID and are not replaced
by this production release.

## Downloads and source

Verify downloads using the attached `SHA256SUMS` and signing certificate
report. Matching license materials and the exact-build LGPL relink kit are
included. This is the stable v0.5.5 release.
