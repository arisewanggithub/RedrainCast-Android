#!/usr/bin/env bash
set -euo pipefail

case "$(uname -s)" in
    MINGW*|MSYS*)
        source_dir=$(cygpath -m "$1")
        build_dir=$(cygpath -m "$2")
        ndk=$(cygpath -m "$3")
        host=windows-x86_64
        exe=.exe
        make_tool="$ndk/prebuilt/windows-x86_64/bin/make.exe"
        ;;
    Linux*)
        source_dir=$1
        build_dir=$2
        ndk=$3
        host=linux-x86_64
        exe=
        make_tool=$(command -v make)
        ;;
    *) echo "Unsupported FFmpeg build host: $(uname -s)" >&2; exit 1 ;;
esac
abi=$4
api=$5
tools="$ndk/toolchains/llvm/prebuilt/$host/bin"
if [[ "$host" == windows-x86_64 ]]; then
    export PATH="$(cygpath -u "$tools"):/usr/bin:$PATH"
else
    export PATH="$tools:/usr/bin:$PATH"
fi
export TMPDIR="$build_dir"
mkdir -p "$build_dir"
cd "$build_dir"
case "$abi" in
    arm64-v8a) arch=aarch64; target=aarch64-linux-android ;;
    armeabi-v7a) arch=arm; target=armv7a-linux-androideabi ;;
    x86_64) arch=x86_64; target=x86_64-linux-android ;;
    *) echo "Unsupported Android ABI: $abi" >&2; exit 1 ;;
esac
if [[ "$host" == windows-x86_64 ]]; then
    # These minimal library targets have no executable host generators.
    host_cc="$tools/clang$exe --target=$target$api"
else
    host_cc=$(command -v cc)
fi

# Configure logs its shell environment. Do not copy ambient developer credentials.
env -i PATH="$PATH" SYSTEMROOT="${SYSTEMROOT:-C:\\Windows}" \
    TMPDIR="$TMPDIR" HOME="$build_dir" \
    bash "$source_dir/configure" \
    --target-os=android --arch="$arch" --enable-cross-compile \
    --cc="$tools/clang$exe --target=$target$api" \
    --cxx="$tools/clang++$exe --target=$target$api" \
    --host-cc="$host_cc" \
    --ar="$tools/llvm-ar$exe" --ranlib="$tools/llvm-ranlib$exe" \
    --nm="$tools/llvm-nm$exe" --strip="$tools/llvm-strip$exe" \
    --disable-everything --disable-autodetect --disable-iconv \
    --disable-programs --disable-doc --disable-network \
    --disable-avcodec --disable-avformat --disable-avdevice \
    --disable-avfilter --disable-swscale --disable-postproc \
    --enable-swresample --enable-avutil \
    --disable-gpl --disable-nonfree --disable-version3 \
    --disable-shared --enable-static --enable-pic \
    --disable-asm --disable-debug \
    --extra-cflags="-O2 -fPIC -ffunction-sections -fdata-sections"
"$make_tool" -j4 libswresample/libswresample.a libavutil/libavutil.a
