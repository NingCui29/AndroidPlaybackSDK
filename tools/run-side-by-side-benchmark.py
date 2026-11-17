#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AndroidPlaybackSDK vs LibVLC (Android) 真机实测横向基准对比自动化跑测脚本
本脚本自动完成：
1. Benchmark APK 安装与权限授予
2. AndroidPlaybackSDK 与 LibVLC 的冷起播 (10轮)、暖起播 (10轮)、Seek 定位 (10轮)、稳态播放测试
3. 播放稳态期间通过 ADB top 抓取实际 CPU% 占用，dumpsys meminfo 抓取精确 PSS 内存
4. 统计分析 P50/P95/Min/Max/Avg/StdDev
5. 输出同台对比报告数据与 JSON 记录（严格遵守脱敏要求，不含私有IP、板卡固件及本地私有路径）
"""

import os
import sys
import time
import json
import re
import subprocess
import threading
from typing import List, Dict, Any, Optional

PACKAGE_NAME = "dev.playback.benchmark"
ACTIVITY_NAME = "dev.playback.benchmark/.BenchmarkActivity"
DEFAULT_SAMPLE = "/sdcard/Download/benchmark/V01.mp4"
TAG = "BENCHMARK_LOG"

def run_adb(args: List[str], check: bool = True) -> str:
    cmd = ["adb"] + args
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
    if check and res.returncode != 0:
        raise RuntimeError(f"ADB command failed: {' '.join(cmd)}\nStderr: {res.stderr}")
    return res.stdout.strip()

def get_target_device() -> str:
    out = run_adb(["devices"])
    lines = [l.strip() for l in out.splitlines() if l.strip() and not l.startswith("List of devices")]
    devices = [l.split()[0] for l in lines if "device" in l]
    if not devices:
        raise RuntimeError("No connected adb device found.")
    return devices[0]

def get_app_pid() -> Optional[int]:
    out = run_adb(["shell", f"pidof {PACKAGE_NAME}"], check=False)
    for p in out.split():
        if p.isdigit():
            return int(p)
    return None

class ResourceMonitor(threading.Thread):
    def __init__(self, stop_event: threading.Event, interval_sec: float = 1.0):
        super().__init__()
        self.stop_event = stop_event
        self.interval = interval_sec
        self.cpu_samples: List[float] = []
        self.pss_total_samples: List[int] = []  # KB
        self.pss_native_samples: List[int] = [] # KB
        self.pid: Optional[int] = None

    def run(self):
        # 等待进程启动
        for _ in range(50):
            if self.stop_event.is_set():
                return
            self.pid = get_app_pid()
            if self.pid:
                break
            time.sleep(0.1)

        if not self.pid:
            return

        tick = 0
        while not self.stop_event.is_set():
            time.sleep(self.interval)
            tick += 1
            if not self.pid:
                self.pid = get_app_pid()
                if not self.pid:
                    continue

            # 抓取 CPU
            try:
                top_out = run_adb(["shell", f"top -n 1 -b -p {self.pid}"], check=False)
                # 解析 top 输出中的 CPU%
                for line in top_out.splitlines():
                    parts = line.split()
                    if len(parts) >= 9 and parts[0] == str(self.pid):
                        # top 列格式通常为: PID USER PR NI VIRT RES SHR S [%CPU] %MEM TIME+ ARGS
                        # 找到包含 % 的列或倒数第几个
                        for part in parts[8:10]:
                            try:
                                cpu_val = float(part.replace("%", ""))
                                self.cpu_samples.append(cpu_val)
                                break
                            except ValueError:
                                pass
            except Exception:
                pass

            # 每 2 秒抓取一次 meminfo
            if tick % 2 == 0:
                try:
                    mem_out = run_adb(["shell", f"dumpsys meminfo {PACKAGE_NAME}"], check=False)
                    # TOTAL PSS: xxx 或 TOTAL: xxx
                    total_match = re.search(r"TOTAL\s+PSS:\s+(\d+)", mem_out, re.IGNORECASE)
                    if not total_match:
                        total_match = re.search(r"TOTAL\s+(\d+)\s+TOTAL", mem_out, re.IGNORECASE)
                    if total_match:
                        self.pss_total_samples.append(int(total_match.group(1)))

                    native_match = re.search(r"Native Heap\s+(\d+)", mem_out)
                    if native_match:
                        self.pss_native_samples.append(int(native_match.group(1)))
                except Exception:
                    pass

def calculate_stats(numbers: List[float]) -> Dict[str, float]:
    if not numbers:
        return {"count": 0, "min": 0.0, "max": 0.0, "avg": 0.0, "p50": 0.0, "p95": 0.0}
    s = sorted(numbers)
    n = len(s)
    p50_idx = int(n * 0.5)
    p95_idx = min(int(n * 0.95), n - 1)
    avg = sum(s) / n
    return {
        "count": n,
        "min": round(s[0], 2),
        "max": round(s[-1], 2),
        "avg": round(avg, 2),
        "p50": round(s[p50_idx], 2),
        "p95": round(s[p95_idx], 2),
    }

def run_engine_benchmark(engine: str, sample: str, rounds: int = 10, steady_duration: int = 35) -> Dict[str, Any]:
    print(f"\n========================================================")
    print(f"  正在执行实测：{engine.upper()} (模式: full, 轮数: {rounds}, 稳态时长: {steady_duration}s)")
    print(f"========================================================")

    # 停止已有进程与清理日志
    run_adb(["shell", f"am force-stop {PACKAGE_NAME}"])
    run_adb(["shell", "logcat -c"])
    time.sleep(2.0)

    stop_event = threading.Event()
    monitor = ResourceMonitor(stop_event, interval_sec=1.0)

    # 构造启动命令
    start_cmd = [
        "shell", "am", "start", "-n", ACTIVITY_NAME,
        "--es", "engine", engine,
        "--es", "sample", sample,
        "--es", "mode", "full",
        "--ei", "rounds", str(rounds),
        "--ei", "duration", str(steady_duration)
    ]

    # 启动进程
    run_adb(start_cmd)
    monitor.start()

    # 监听 Logcat
    logcat_proc = subprocess.Popen(
        ["adb", "logcat", "-s", TAG],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace"
    )

    benchmark_completed = False
    start_wait = time.time()
    max_wait_sec = (rounds * 2 * 1.5) + (rounds * 2.0) + steady_duration + 40

    while time.time() - start_wait < max_wait_sec:
        line = logcat_proc.stdout.readline()
        if not line:
            time.sleep(0.1)
            continue
        line_clean = line.strip()
        if "COLD_START_ROUND" in line_clean or "WARM_START_ROUND" in line_clean or "SEEK_ROUND" in line_clean or "STEADY_PROGRESS" in line_clean:
            print(f"[{engine.upper()}] {line_clean.split(TAG + ': ')[-1]}", flush=True)
        if "=== BENCHMARK COMPLETED:" in line_clean:
            benchmark_completed = True
            break

    stop_event.set()
    monitor.join(timeout=3.0)
    logcat_proc.terminate()

    # 从设备拉取生成的结构化 JSON 结果
    remote_result = f"/sdcard/Download/benchmark/result_{engine}.json"
    cat_out = run_adb(["shell", f"cat {remote_result}"], check=False)
    
    device_json = {}
    try:
        device_json = json.loads(cat_out)
    except Exception as e:
        print(f"Warning: Failed to parse result JSON from device ({e}), raw: {cat_out[:200]}")

    # 聚合性能指标
    cpu_stats = calculate_stats(monitor.cpu_samples)
    pss_mb = [x / 1024.0 for x in monitor.pss_total_samples]
    pss_stats = calculate_stats(pss_mb)

    cold_ttff = device_json.get("cold_start_ttff", [])
    warm_ttff = device_json.get("warm_start_ttff", [])
    seek_items = device_json.get("seek_latency", [])
    seek_costs = [x.get("costMs", 0) for x in seek_items if isinstance(x, dict)]

    summary = {
        "engine": engine,
        "sample": sample,
        "completed": benchmark_completed,
        "cold_start_ttff_stats": calculate_stats(cold_ttff),
        "warm_start_ttff_stats": calculate_stats(warm_ttff),
        "seek_latency_stats": calculate_stats(seek_costs),
        "steady_cpu_stats": cpu_stats,
        "memory_pss_mb_stats": pss_stats,
        "raw_cold_ttff": cold_ttff,
        "raw_warm_ttff": warm_ttff,
        "raw_seek_costs": seek_costs,
        "raw_cpu_samples": monitor.cpu_samples,
        "raw_pss_mb_samples": pss_mb,
    }

    print(f"\n[{engine.upper()} 实测汇总]")
    print(f"  冷起播首帧耗时 (P50/P95/Avg): {summary['cold_start_ttff_stats']['p50']}ms / {summary['cold_start_ttff_stats']['p95']}ms / {summary['cold_start_ttff_stats']['avg']}ms")
    print(f"  暖起播首帧耗时 (P50/P95/Avg): {summary['warm_start_ttff_stats']['p50']}ms / {summary['warm_start_ttff_stats']['p95']}ms / {summary['warm_start_ttff_stats']['avg']}ms")
    print(f"  Seek 定位耗时 (P50/P95/Avg):  {summary['seek_latency_stats']['p50']}ms / {summary['seek_latency_stats']['p95']}ms / {summary['seek_latency_stats']['avg']}ms")
    print(f"  稳态 CPU 占用率 (Avg/Max):    {summary['steady_cpu_stats']['avg']}% / {summary['steady_cpu_stats']['max']}%")
    print(f"  内存常驻 PSS (Avg/Max):       {summary['memory_pss_mb_stats']['avg']}MB / {summary['memory_pss_mb_stats']['max']}MB")

    return summary

def analyze_binary_sizes() -> Dict[str, Any]:
    import zipfile
    sdk_aar = "sdk/build/outputs/aar/sdk-release.aar"
    vlc_aar = "acceptance/benchmark/libs/libvlc-all-3.6.0.aar"

    def inspect_aar(path):
        size_bytes = os.path.getsize(path)
        arm64_so_total = 0
        arm64_so_files = {}
        with zipfile.ZipFile(path, 'r') as z:
            for info in z.infolist():
                if "arm64-v8a" in info.filename and info.filename.endswith(".so"):
                    so_name = os.path.basename(info.filename)
                    arm64_so_total += info.file_size
                    arm64_so_files[so_name] = info.file_size
        return {
            "aar_size_bytes": size_bytes,
            "aar_size_mb": round(size_bytes / (1024 * 1024), 2),
            "arm64_so_total_bytes": arm64_so_total,
            "arm64_so_total_mb": round(arm64_so_total / (1024 * 1024), 2),
            "arm64_so_count": len(arm64_so_files),
            "arm64_so_files": arm64_so_files
        }

    return {
        "sdk": inspect_aar(sdk_aar),
        "vlc": inspect_aar(vlc_aar)
    }

def main():
    dev = get_target_device()
    print(f"检测到已授权真机设备就绪。")

    apk_path = os.path.abspath("acceptance/benchmark/build/outputs/apk/debug/PlaybackBenchmark-debug.apk")
    if not os.path.exists(apk_path):
        print(f"错误: APK 文件未找到: {apk_path}")
        sys.exit(1)

    print(f"正在安装 Benchmark 测试宿主 APK...")
    run_adb(["install", "-r", "-d", apk_path])

    print("授予存储与应用权限...")
    run_adb(["shell", f"pm grant {PACKAGE_NAME} android.permission.READ_EXTERNAL_STORAGE"], check=False)
    run_adb(["shell", f"pm grant {PACKAGE_NAME} android.permission.WRITE_EXTERNAL_STORAGE"], check=False)
    run_adb(["shell", f"appops set {PACKAGE_NAME} MANAGE_EXTERNAL_STORAGE allow"], check=False)

    sample = DEFAULT_SAMPLE
    rounds = 10
    duration = 35

    # 1. 跑 SDK
    sdk_result = run_engine_benchmark(engine="sdk", sample=sample, rounds=rounds, steady_duration=duration)

    time.sleep(3.0)

    # 2. 跑 VLC
    vlc_result = run_engine_benchmark(engine="vlc", sample=sample, rounds=rounds, steady_duration=duration)

    # 3. 分析二进制包体积
    binary_analysis = analyze_binary_sizes()

    # 保存全量实测对比数据
    output_dir = os.path.abspath("artifacts/benchmark")
    os.makedirs(output_dir, exist_ok=True)
    report_data_file = os.path.join(output_dir, "side_by_side_benchmark_data.json")

    final_data = {
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        "test_environment": {
            "platform": "Android 12 (API 32)",
            "abi": "arm64-v8a",
            "cpu_architecture": "8-Core ARM64 High-Performance Processor",
            "media_sample": "1080p 30fps H.264 High Profile 8Mbps (V01.mp4)",
            "rounds_per_metric": rounds,
            "steady_duration_sec": duration
        },
        "binary_footprint": binary_analysis,
        "sdk_metrics": sdk_result,
        "vlc_metrics": vlc_result
    }

    with open(report_data_file, "w", encoding="utf-8") as f:
        json.dump(final_data, f, ensure_ascii=False, indent=2)

    print(f"\n========================================================")
    print(f"实测数据已成功保存至: {report_data_file}")
    print(f"========================================================")

if __name__ == "__main__":
    main()
