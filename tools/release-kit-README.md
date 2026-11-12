# RedrainCast LGPL relink kit

FFmpeg libswresample and libavutil are used under LGPL 2.1 or later.
Corresponding upstream source, public build configuration, headers/static
archives (AAR/Prefab), notices and licenses are in `materials/`. Nothing enables
GPL, nonfree or version-3 components. The application remains proprietary.
You may modify these LGPL libraries and relink this application for your own
use; reverse engineering for debugging such modifications is permitted.

`objects/<ABI>/resampler_jni.cpp.o` is the matching application JNI object.
Only debugging metadata was removed to avoid distributing private build paths;
all code, data, symbols and relocations needed for relinking remain.
Application sources are available in the public Android repository rather than
duplicated in this kit. `manifest.json` binds these objects to
the exact distributed APK hash, version, application commit/source hash, NDK,
link flags and exported JNI symbols. It contains no local build paths.

## Reproduce or modify

1. Verify the archive's published SHA-256, then extract it. Within the kit run
   `sha256sum --check SHA256SUMS` (or an equivalent SHA-256 verifier).
2. Install Python 3.10+ and Android NDK **27.2.12479018**. The target is Android
   API **30**, static C++ runtime, arm64-v8a/armeabi-v7a/x86_64, 16 KiB pages.
3. Baseline smoke/relink, without compiling FFmpeg:

   ```sh
   python3 relink.py --ndk "$ANDROID_NDK_HOME" --ffmpeg-prefix ./ffmpeg --output ./relinked
   ```

4. To change FFmpeg, extract the corresponding source/build-materials archives
   in `materials/` and follow their public build instructions with the same NDK,
   API and ABI configuration. Do not enable GPL/nonfree/version-3 components.
   Provide your modified libraries in
   `<prefix>/<ABI>/lib/libswresample.a` and `libavutil.a`.
   Repeat the command with `--ffmpeg-prefix <prefix>`. This path is required
   explicitly; the relinker never silently substitutes original libraries.
   Baseline input checksums live in `SHA256SUMS`; modified archives are
   deliberately not required to match the originals.
5. Replace `lib/<ABI>/libscreencopy_resampler.so` in a copy of the APK with the
   three outputs. Using Android SDK build-tools 37.0.0, remove the original
   signature, run `zipalign -P 16 -f 4 input.apk aligned.apk`, then
   `apksigner sign --ks YOUR_KEYSTORE --out modified.apk aligned.apk`.
   Verify using `apksigner verify` and `zipalign -c -P 16 4 modified.apk`.
   Sign with **your own** key; the production private key is not needed or
   distributed. Android cannot install your differently signed copy as an
   in-place update of the official app. Back up your app data before choosing
   to uninstall it. This kit does not change devices or uninstall anything.

The relinker rejects unresolved link symbols, unexpected shared FFmpeg/C++
dependencies, wrong ELF machines, changed JNI exports and insufficient LOAD
alignment. Modified builds retain the original JNI interface. Passing these
checks does not certify Android runtime behavior; test your modified app.
The maintainer baseline-smokes all three ABIs before packaging. The original
APK need not be byte-identical after relinking (debug metadata/build IDs may
differ).
