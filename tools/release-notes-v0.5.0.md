# RedrainCast v0.5.0

Embedded version: `0.5.0` (version code 33).

## Highlights

- Add opt-in, process-local remote playback diagnostics for Android. The
  collector can discover an enabled receiver, query a casting sender for its
  live endpoint, and collect sender plus delegated receiver evidence for the
  admitted cast.
- Add a full playback diagnostics view and improve compact statistics layout
  while keeping the diagnostic surface independently secured.
- Improve media timing, audio refill/recovery, video admission and track
  replacement/teardown behavior, with expanded regression coverage.

## Validation and known limits

- Android unit tests, lint, debug and optimized release builds, diagnostics
  collector tests, and release-tooling tests pass. Surface ownership
  instrumentation coverage is compiled separately; compositor placement and
  capture protection still require physical-device validation.
- A 180-second paired LAN capture with the latest debug build completed without
  disconnect, recovery or a sustained freeze. This single run is not broad
  LAN/lifetime acceptance or a physical display/overhead measurement.
- Remote diagnostics are opt-in, plaintext and unauthenticated. Use only on a
  trusted LAN; either peer can expose controlled diagnostic metadata and the
  service is not an authenticated or confidential channel. Both devices must
  run a compatible build.
- The intermittent first-rotation tiny-strip rendering issue remains
  unresolved. Android's rendered-frame callbacks do not prove physical display
  scanout, and live Windows interoperability is not certified by these tests.
- Native codec/AudioTrack stalls, OS termination and network loss can still
  prevent terminal error delivery. A production-signed APK cannot update an
  earlier debug-signed installation in place.

## Installation

`RedrainCast-0.5.0.apk` supports Android 11/API 30 or newer and includes
`arm64-v8a`, `armeabi-v7a` and `x86_64`. Version code 33 permits production
updates from v0.4.19/code 32. The production signing certificate remains
`79f906220de117d61c2d15bdfcef179a4e3e48cfa0c76b637c7af986c037fad3`.

## Downloads and source

Verify downloads using `SHA256SUMS` and the signing certificate report. The
release includes the production APK, checksums, certificate, licenses and
exact-build LGPL relink materials. GitHub's source archives correspond to the
public Android source at the verified release tag.
