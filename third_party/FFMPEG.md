# FFmpeg PCM resampler

The receiver's `screencopy_resampler` JNI library statically links only FFmpeg
**7.1.3** `libswresample` and `libavutil`. Ordinary local and app-CI builds
consume the pinned public `ffmpeg-pcm-android:7.1.3-r1` AAR/Prefab package from
<https://github.com/arisewanggithub/RedrainCast-Native/releases>.
Gradle verifies the complete AAR against
`ffmpeg-pcm-android-7.1.3-r1.sha256` before native configuration.
Only the app's small JNI wrapper is built against those FFmpeg archives.
The existing small bundled Opus library is still built from its pinned source.

GitHub Actions in the separate public native-build repository compiles the
package in parallel ABI jobs, not on application developers' machines. A fresh
clone or cleared cache downloads the same immutable package without credentials;
an offline build requires that package to have been cached already. Missing or
changed packages fail visibly: there is no automatic source-build fallback.

The corresponding unmodified source is retained as `ffmpeg-7.1.3.tar.xz`, from
<https://ffmpeg.org/releases/ffmpeg-7.1.3.tar.xz>, with its SHA-256 pinned in
`ffmpeg-7.1.3.sha256`. Source and build/relink materials accompany the public
native release as well as being retained here for distribution compliance.

The configuration in `build-ffmpeg.sh` disables GPL, nonfree and version-3
licensing, external-library autodetection, programs, codecs, formats, filters,
devices, networking and unrelated FFmpeg libraries. Its reported license is
**LGPL version 2.1 or later**. Assembly is deliberately disabled for a minimal,
reproducible Windows-NDK build; this is a build optimization choice, not a
runtime codec or resampler fallback. Hardware media-codec selection is unchanged.
`libavutil` still contains its normal utility implementation; unused static
sections are removed by the Android linker.

## Application builds

The application resolves the AAR through an artifact-only Gradle Ivy repository
at the public GitHub Release URL. `buildFeatures.prefab` exposes its static
modules through `find_package(redrain_ffmpeg REQUIRED CONFIG)`, with CMake
targets `redrain_ffmpeg::swresample` and `redrain_ffmpeg::avutil`.
The package contains all three ABIs and their installed public/generated headers,
and does not change universal APK packaging or end-user downloads.

The initial immutable package is
[`ffmpeg-pcm-android-7.1.3-r1`](https://github.com/arisewanggithub/RedrainCast-Native/releases/tag/ffmpeg-pcm-android-7.1.3-r1),
published by this
[successful GitHub Actions run](https://github.com/arisewanggithub/RedrainCast-Native/actions/runs/42103770241).
Its release includes checksums, upstream source, build/relink materials,
provenance and per-ABI static/Prefab consumer validation.
The Android consumer also accepts future platform-specific release paths such
as `redraincast-ffmpeg-android-pcm-7.1.3-r2`; the AAR module and asset naming
remain `ffmpeg-pcm-android`. Windows decoder releases are separate and do not
change this Android dependency.

Use the normal Gradle build commands in the repository README. No Bash/make
FFmpeg toolchain is required for these application builds. NDK/CMake remain
required for the JNI wrapper and Opus. AAR verification is required by both
`preBuild` and CMake configuration tasks. Do not remove it or replace the pinned
version with a floating `latest` dependency.

## Explicit native-package maintenance

Update the source checksum/configuration and manually trigger the workflow in
<https://github.com/arisewanggithub/RedrainCast-Native>. A change to FFmpeg,
NDK, API floor, ABI set or compiler/configuration flags requires a new immutable
package revision, followed by updating the consumer version and AAR checksum.
Source builds are maintenance operations only; application builds never invoke
`build-ffmpeg.sh`.

The retained standalone helper can also reproduce an individual ABI build for
licensing/debugging. On Windows it requires existing Android SDK/NDK and Git Bash
at `C:\Program Files\Git\bin\bash.exe`.

The helper uses the NDK's existing clang and GNU make; it installs nothing.
Each ABI uses a separate extracted, in-tree source build, avoiding POSIX
absolute make prerequisites which native Windows GNU make cannot resolve.
The host compiler option uses the same NDK cross compiler: these two library
targets require no executable host generators. Configure receives a restricted
environment to avoid copying developer credentials into its diagnostic log.
Configure scratch files remain in the ABI's build directory.

The same helper supports Ubuntu, selecting the
NDK's `linux-x86_64` clang plus the runner's existing `bash`, `make`, and host
`cc` (standard Ubuntu build tools). The public packaging pipeline is the authoritative Linux build and verification
path. It validates metadata, static linking and a 16 KiB-aligned shared smoke
library before publishing.

The app's `.so` is linked with `-z max-page-size=16384`; inspect its LOAD segments
with NDK `llvm-readelf.exe -lW`. Native builds alone do not validate Android
runtime/JNI behavior: run the receiver's Android tests separately.

## Integration contract

`PcmResampler` is public for injectable playback factories;
`FfmpegPcmResampler(rate, channels)` is internal and has no identity fallback.
Input/output are interleaved signed 16-bit PCM, at the same sample rate and
channel count. Supported rates are 8000..96000 Hz, mono or stereo. Wire format
remains 48000 Hz mono/stereo. Offset and count are **frames**, not shorts.

Each conversion admits at most `floor(rate * 120 / 1000)` input frames.
Immediately before conversion, `swr_get_out_samples` must fit within that
maximum plus **256 frames** (filter and compensation headroom). An estimate
that does not fit is a visible error *before* consuming input, never truncation.
In particular, at high non-wire rates a full 120 ms input with maximum positive
compensation can exceed the fixed headroom; use smaller input blocks or fail.
Positive compensation adds output frames; negative compensation removes them.
Distance must be positive, and `abs(delta) * 50 <= distance` (maximum 2%),
except that exactly `(0, 0)` cancels compensation without resetting filter history.
The normal distance is `rate * 4`, four seconds.

`SWR_FLAG_RESAMPLE` keeps the filter active even at 1:1, so compensation does
not reinitialize filter history. `delayFrames` uses `swr_get_delay(rate)` in
the **source-rate** domain, including filter lookahead. The caller maps output
against accepted source spans/PTS and accounts for that delay; the JNI layer
does not rewrite source timestamps or capability negotiation. Zero input
returns an empty array without flushing. There is no idle flush/reset.
Create one instance per native audio epoch, and close it on retirement.
Close is idempotent and discards buffered filter history, not a drain.
Kotlin synchronizes calls; a native guarded ID registry also prevents stale
pointers and use-after-free. Invalid arguments, capacity errors, FFmpeg errors,
closed sessions and allocation failures surface as Java exceptions.

## Licensing and release obligations

The full upstream notices and LGPL text are preserved in
`licenses\ffmpeg\LICENSE.md` and `licenses\ffmpeg\COPYING.LGPLv2.1`.
Copies plus source/build/relink attribution are included in
`app\src\main\assets\licenses\FFmpeg-*`, surfaced by the application's notices UI.
Individual source-file copyright and permissive notices are retained in the
complete source archive. Do not remove them. Although the archive also includes
optional GPL components, the configured/built libraries do not enable them.

Every binary distribution using this library must:

* prominently credit FFmpeg (<https://ffmpeg.org/>), state use under LGPL 2.1+,
  and accompany the application with the LGPL text and applicable notices;
* accompany it with the complete corresponding FFmpeg source archive,
  checksum, build helper/configuration, JNI source and build instructions
  (including modifications, if any), or use another LGPL-compliant source
  distribution mechanism;
* supply the matching, unstripped JNI application object
  `CMakeFiles\screencopy_resampler.dir\resampler_jni.cpp.o` for **each ABI**
  plus the link instructions/toolchain version, allowing replacement of the
  LGPL libraries and relinking the `.so`. The original static libraries,
  headers/configuration and package provenance are in the native release.
  The app's Ninja link command (`ninja -t commands screencopy_resampler`)
  is available in its `.cxx` ABI build directory. Retain the AAR/static archives,
  configuration and matching app objects/link command with the release's
  relinking materials;
* permit modification for the recipient's own use and reverse engineering
  for debugging such modifications. Do not impose contrary EULA terms or
  restrictions on replacing/re-signing/reinstalling the relinked application.

The build packages the in-app license/attribution assets, but does **not**
automatically publish a source offer or distribute a release's relink objects.
Those are explicit distribution requirements, not satisfied merely by building
the library. See LGPL sections 4–6 for the exact terms; review the final release
packaging accordingly.
