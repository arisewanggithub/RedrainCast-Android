# RedrainCast downloads

Public Android APKs, checksums, signing certificates, notices and LGPL relink
materials belong in this repository. Application source remains private; the
automatic GitHub source archives contain only this public download documentation.

Testing prereleases, when available, are listed under
[Releases](https://github.com/arisewanggithub/RedrainCast-Releases/releases).
Every release requires explicit source and asset review, production signing
and exact-build LGPL relink materials. Drafts are not public downloads;
nothing automatically publishes a stable release.

## Download and verify testing releases

- Download the universal `RedrainCast-<version>.apk` from **Releases**. It supports
  Android 11/API 30 or newer on arm64-v8a, armeabi-v7a and x86_64.
- Check its SHA-256 against the attached `SHA256SUMS`, and verify its signing
  certificate with Android SDK `apksigner verify --verbose --print-certs`.
  The production certificate SHA-256 is
  `79f906220de117d61c2d15bdfcef179a4e3e48cfa0c76b637c7af986c037fad3`.
- A production-signed APK cannot update an older debug-signed development
  installation in place. Back up your app data before choosing to uninstall it;
  uninstalling loses local app data. No setup script changes your devices.
- Read the release notes: an intermittent first-rotation tiny-strip rendering
  issue remains unresolved, and live Windows interoperability is not verified.
  Testing releases are not claims of stable production readiness.

## FFmpeg and relinking

RedrainCast statically uses FFmpeg libswresample/libavutil under **LGPL 2.1 or
later**, with GPL/nonfree/version-3 components disabled. See <https://ffmpeg.org/>.
Every APK release includes licenses/notices and `android-lgpl-relink.tar.gz`:
the corresponding FFmpeg source, public build configuration, headers/static
archives, matching application-native objects for all three ABIs, toolchain
details and instructions to relink with modified LGPL libraries and sign your
own APK. Reverse engineering for debugging such modifications is permitted.
The relink kit does not require the production private signing key or private
application source.

The public native package and corresponding source/build materials are also at
<https://github.com/arisewanggithub/RedrainCast-Native/releases>.
