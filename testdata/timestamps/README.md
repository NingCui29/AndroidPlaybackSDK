# V10 时间戳跳变

两个输入复用 V10 的全部 H.264 和 AAC 数据。在第五个视频关键包的 TS 起点之后，统一平移 PCR 和所有音视频 PES 的 PTS/DTS。正向变体加 3 s，反向变体减 1.5 s。音频 PES 的聚合边界与视频 GOP 不重合，各轨首次变化的包号与原始／新 PTS 见[清单](manifest.json)。

执行 `python tools/generate-ts-jumps.py --ffprobe <ffprobe路径>` 可以从 V10 重建这两份文件。脚本逐轨核对变换前后所有分包的大小、包数和时间戳偏移。两条视频均有 200 个显示帧，音频均有 376 帧 AAC-LC，解码得到 385,024 个 48 kHz 双声道采样帧。

在目标设备运行 `tools/run-device-tests.ps1 -Serial <设备> -Suite timestamps -Policy SOFTWARE_ONLY`，再按设备能力使用 HARDWARE_ONLY 复验。每组覆盖普通文件、不可回读的管道、向前及向后跳变。检查媒体位置随已消费 PCM 跳变、总消费采样帧和视频帧数、跳变后播放墙钟与提交间隔；文件在尾段另做一次精确定位。该测试记录 SDK 数字输出，共时基声画物理测量沿用 K04。
