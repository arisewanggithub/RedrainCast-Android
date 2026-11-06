# RedrainCast v0.4.6 - testing prerelease

This is a production-signed **testing prerelease** of the reviewed v0.4.6
baseline (version code 16), not the unfinished later v0.4.8 audio candidate.
It is not a stable-release claim. Application source remains private.

## Download and verify

- One universal APK supports **arm64-v8a, armeabi-v7a and x86_64** on
  **Android 11 / API 30 or newer**.
- Verify downloads against the attached `SHA256SUMS`.
- Verify the APK with Android SDK `apksigner verify --verbose --print-certs`.
  The production certificate SHA-256 is
  `79f906220de117d61c2d15bdfcef179a4e3e48cfa0c76b637c7af986c037fad3`.
- The attached certificate report, licenses/notices and exact-build LGPL
  relink kit belong to this same APK.

## Known limitations and signing migration

The intermittent **first-rotation tiny-strip rendering issue remains
unresolved**. Live Windows interoperability is **not verified**. Do not treat
this prerelease as evidence that those issues or later native-audio startup
investigations are fixed.

This production certificate differs from earlier development/debug signing.
Android cannot update those differently signed installations in place.
Back up app data before choosing to uninstall an older development app;
uninstalling loses its local data. Nothing in this release automatically
uninstalls an app or changes device permissions.

## FFmpeg / LGPL relinking

RedrainCast statically uses [FFmpeg](https://ffmpeg.org/) libswresample and
libavutil under **LGPL 2.1 or later**, with GPL, nonfree and version-3 components
disabled. Complete notices and license texts accompany the APK.

`android-lgpl-relink.tar.gz` includes the corresponding FFmpeg source, public
build configuration, headers/static archives, matching application-native
objects for all three ABIs, checksums and instructions to relink with modified
LGPL libraries and sign your own APK. The manifest binds the kit to this exact
APK and its reviewed source revision. Reverse engineering for debugging such
modifications is permitted. You do not need the production private signing key
or private application source to relink.

The kit uses LGPL 2.1 section 6(a)'s application object-code option. Older
in-app build notes list application JNI source files; this distribution instead
supplies the matching relinkable application objects and portable link
instructions, without disclosing proprietary application source.

GitHub's automatic source archives for this download repository contain only
public download documentation, not the private application source.
