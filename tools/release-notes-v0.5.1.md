# RedrainCast v0.5.1

Embedded version: `0.5.1` (version code 35).

## Highlights

- Improve receiver video pacing by scheduling decoded frames against their
  presentation timestamps without blocking media workers.
- Improve audio output servicing and recovery behavior, and tighten media
  configuration and worker lifecycle coordination.
- Add opt-in sender video-frame traces and receiver audio-service diagnostics
  with tools for correlating and analyzing captured traces.

## Known limits

- Playback appeared smoother in an initial device comparison, but occasional
  judder remains. This release improves the playback path but does not claim
  that judder is fully resolved.
- Scheduled/rendered-frame observations do not prove physical display scanout.
  Results may vary by encoder, decoder, device load and network conditions.
- Remote diagnostics remain opt-in, plaintext and unauthenticated. Use only on
  a trusted LAN.

## Installation

`RedrainCast-0.5.1.apk` supports Android 11/API 30 or newer and includes
`arm64-v8a`, `armeabi-v7a` and `x86_64`. Version code 35 permits updating
version code 34 installations only when the signing certificate matches.
Debug-signed installations use a separate application ID.

## Downloads and source

Verify downloads using the attached `SHA256SUMS` and signing certificate
report. This is the stable v0.5.1 release.
