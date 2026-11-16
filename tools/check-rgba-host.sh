#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
ffmpeg_build=$(cd "${1:?supply an FFmpeg 8.1.3 source tree with static avutil and swscale}" && pwd)
temp_dir=$(mktemp -d)
trap 'rm -f "$temp_dir/rgba_checks" "$temp_dir/rgba_checks.exe"; rmdir "$temp_dir"' EXIT
platform_libs=()
case "$(uname -s)" in MSYS*|CYGWIN*|MINGW*) platform_libs+=(-lbcrypt);; esac
"${CC:-cc}" -std=c11 -O2 -Wall -Werror -I"$ffmpeg_build" -I"$repo/sdk/src/main/cpp" \
    "$repo/native-tests/rgba_checks.c" "$repo/sdk/src/main/cpp/rgba_renderer.c" \
    "$ffmpeg_build/libswscale/libswscale.a" "$ffmpeg_build/libavutil/libavutil.a" \
    "${platform_libs[@]}" -lpthread -lm -o "$temp_dir/rgba_checks"
"$temp_dir/rgba_checks"
