# RedrainCast v0.5.1-rc.1

Embedded version: `0.5.1-rc.1` (version code 34).

## Highlights

- Improve receiver video pacing by scheduling decoded frames against their
  presentation timestamps without blocking media workers.
- Improve audio output servicing and recovery behavior, and tighten media
  configuration and worker lifecycle coordination.
- Add opt-in sender video-frame traces and receiver audio-service diagnostics
  with tools for correlating and analyzing captured traces.

## Validation and known limits

- Android unit tests pass on the v0.5.0-based source branch, and debug lint
  passes. The signed optimized release workflow remains to be run for this
  exact candidate revision.
- Playback appeared smoother in an initial device comparison, but occasional
  judder remains. This candidate is for further testing, not a claim that the
  issue is fully resolved.
- Scheduled/rendered-frame observations do not prove physical display scanout.
  Results may vary by encoder, decoder, device load and network conditions.
- Remote diagnostics remain opt-in, plaintext and unauthenticated. Use only on
  a trusted LAN.

## Installation

`RedrainCast-0.5.1-rc.1.apk` supports Android 11/API 30 or newer and includes
`arm64-v8a`, `armeabi-v7a` and `x86_64`. Version code 34 permits updating
version code 33 installations only when the signing certificate matches.
Debug-signed installations use a separate application ID.

## Downloads and source

Verify candidate downloads using the attached `SHA256SUMS` and signing
certificate report. A release candidate is distributed as a draft for review;
it is not a final stable release.
