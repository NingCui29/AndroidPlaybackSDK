#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
多格式同台兼容性与起播性能对比跑测
测试文件：
1. V01.mp4 (H.264 / AAC 1080p MP4)
2. V11.mp4 (H.264 / AAC 720p MP4)
3. H02-hevc.ts (H.265 / HEVC TS)
4. V05.mkv (MKV 封装)
5. V09a.wmv (WMV 封装)
"""

import os
import sys
import time
import json
import subprocess

PACKAGE_NAME = "dev.playback.benchmark"
ACTIVITY_NAME = "dev.playback.benchmark/.BenchmarkActivity"
TAG = "BENCHMARK_LOG"

FORMAT_TESTS = [
    {"name": "MP4 (H.264 1080p)", "path": "/sdcard/Download/benchmark/V01.mp4", "codec": "H.264", "container": "MP4"},
    {"name": "MP4 (H.264 720p)",  "path": "/sdcard/Download/benchmark/V11.mp4", "codec": "H.264", "container": "MP4"},
    {"name": "TS (H.265 / HEVC)", "path": "/sdcard/Download/benchmark/H02-hevc.ts", "codec": "H.265/HEVC", "container": "TS"},
    {"name": "MKV (Multi-track)", "path": "/sdcard/Download/benchmark/V05.mkv", "codec": "H.264", "container": "MKV"},
    {"name": "WMV",               "path": "/sdcard/Download/benchmark/V09a.wmv", "codec": "WMV", "container": "WMV"}
]

def run_adb(args):
    cmd = ["adb"] + args
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace").stdout.strip()

def test_format(engine: str, sample_path: str):
    run_adb(["shell", f"am force-stop {PACKAGE_NAME}"])
    run_adb(["shell", "logcat -c"])
    time.sleep(1.0)

    start_cmd = [
        "shell", "am", "start", "-n", ACTIVITY_NAME,
        "--es", "engine", engine,
        "--es", "sample", sample_path,
        "--es", "mode", "cold_start",
        "--ei", "rounds", "3"
    ]
    run_adb(start_cmd)

    logcat_proc = subprocess.Popen(
        ["adb", "logcat", "-s", TAG],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace"
    )

    ttff_list = []
    success = False
    start_t = time.time()
    while time.time() - start_t < 18:
        line = logcat_proc.stdout.readline()
        if not line:
            time.sleep(0.05)
            continue
        line_clean = line.strip()
        if "COLD_START_ROUND" in line_clean and "ttff=" in line_clean:
            try:
                val = int(line_clean.split("ttff=")[1].replace("ms", "").strip())
                ttff_list.append(val)
            except Exception:
                pass
        if "=== BENCHMARK COMPLETED:" in line_clean:
            if "SUCCESS" in line_clean:
                success = True
            break

    logcat_proc.terminate()
    run_adb(["shell", f"am force-stop {PACKAGE_NAME}"])

    avg_ttff = round(sum(ttff_list) / len(ttff_list), 1) if ttff_list else 0.0
    return {
        "success": success and len(ttff_list) > 0,
        "rounds": len(ttff_list),
        "avg_ttff_ms": avg_ttff,
        "raw": ttff_list
    }

def main():
    results = {"sdk": {}, "vlc": {}}

    for item in FORMAT_TESTS:
        fname = item["name"]
        fpath = item["path"]
        print(f"--> 测试格式: {fname}...")

        # 测 SDK
        sdk_res = test_format("sdk", fpath)
        print(f"    [SDK] 成功: {sdk_res['success']}, 平均耗时: {sdk_res['avg_ttff_ms']}ms {sdk_res['raw']}")
        results["sdk"][fname] = sdk_res

        time.sleep(1.0)

        # 测 VLC
        vlc_res = test_format("vlc", fpath)
        print(f"    [VLC] 成功: {vlc_res['success']}, 平均耗时: {vlc_res['avg_ttff_ms']}ms {vlc_res['raw']}")
        results["vlc"][fname] = vlc_res

        time.sleep(1.0)

    out_file = os.path.abspath("artifacts/benchmark/format_matrix_benchmark.json")
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"\n格式兼容性跑测完成，结果已保存至: {out_file}")

if __name__ == "__main__":
    main()
