#!/usr/bin/env bash
# Usage: ANDROID_NDK_ROOT=/absolute/path/to/r30 bash tools/build-ffmpeg.sh OUTPUT_DIRECTORY
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
ndk=${ANDROID_NDK_ROOT:?Provide the pinned r30 NDK directory}
destination=${1:?Provide a new build output directory}
mkdir -p "$destination"
destination=$(cd "$destination" && pwd)
if ! grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*30\.0\.16248370[[:space:]]*$' "$ndk/source.properties"; then
    echo 'Expected Android NDK 30.0.16248370' >&2
    exit 1
fi
case "$(uname -s)" in
    Linux*) host=linux-x86_64;;
    Darwin*) host=darwin-x86_64;;
    MSYS*|MINGW*|CYGWIN*) host=windows-x86_64; ndk=$(cygpath -u "$ndk");;
    *) echo 'Unsupported NDK host' >&2; exit 1;;
esac
toolchain="$ndk/toolchains/llvm/prebuilt/$host/bin"
archive="$root/third_party/ffmpeg/8.1.3/ffmpeg-8.1.3.tar.xz"
expected=7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3
actual=$(sha256sum "$archive" | cut -d' ' -f1)
test "$expected" = "$actual"
# Use a fresh build tree on every invocation. Keep configure diagnostics and original symbols.
work=$(mktemp -d "$destination/build.XXXXXX")
tar -xJf "$archive" -C "$work"
mkdir "$work/objects"
cd "$work/objects"
"$work/ffmpeg-8.1.3/configure" \
    --target-os=android --arch=aarch64 --enable-cross-compile \
    --cc="$toolchain/clang --target=aarch64-linux-android21" \
    --cxx="$toolchain/clang++ --target=aarch64-linux-android21" \
    --ar="$toolchain/llvm-ar" --nm="$toolchain/llvm-nm" \
    --ranlib="$toolchain/llvm-ranlib" --strip="$toolchain/llvm-strip" \
    --extra-ldflags=-Wl,--build-id=sha1 \
    --disable-everything --disable-autodetect --disable-programs --disable-doc --disable-network \
    --disable-avfilter --disable-avdevice --disable-static --enable-shared --enable-pic \
    --enable-demuxer=mov,matroska,avi,asf,mpegts,mp3,flac,wav,ogg \
    --enable-decoder=h264,hevc,vp8,vp9,mpeg4,wmv1,wmv2,wmv3,vc1,msmpeg4v1,msmpeg4v2,msmpeg4v3,mp3float,aac,flac,vorbis,opus,wmav1,wmav2,pcm_u8,pcm_s16le,pcm_s24le,pcm_s32le \
    --enable-parser=aac,flac,h264,hevc,mpeg4video,mpegaudio,opus,vc1,vorbis,vp8,vp9 \
    --enable-bsf=h264_mp4toannexb,hevc_mp4toannexb | tee "$work/configure-summary.txt"
make -j"${JOBS:-4}"
make install-headers INCDIR="$destination/include" PKGCONFIGDIR="$destination/pkgconfig"
mkdir -p "$destination/symbols/arm64-v8a" "$destination/stripped/arm64-v8a"
for name in avformat avcodec avutil swscale swresample; do
    library="lib$name.so"
    original="$destination/symbols/arm64-v8a/$library"
    stripped="$destination/stripped/arm64-v8a/$library"
    cp "lib$name/$library" "$original"
    "$toolchain/llvm-strip" --strip-unneeded "$original" -o "$stripped"
    "$toolchain/llvm-readelf" -h -l -d -n -W "$stripped" > "$work/$name.elf.txt"
done
(cd "$destination" && sha256sum symbols/arm64-v8a/*.so stripped/arm64-v8a/*.so > SHA256SUMS)
