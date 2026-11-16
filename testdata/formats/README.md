# 扩展必播样本

清单逐文件保存 SHA-256、生成工具和 ffprobe 轨道信息。[manifest.json](manifest.json)。首批 V01、A02、V11、A04 另有[有效采样及帧标记](../baseline/README.md)。

V02 用合成运动图、彩色角标、网格和旋转矩阵。V03/V04 复用同一组 HEVC Main 和 AAC 轨道。V05 的两条 AAC 分别由合成语音报 “Track one” 和 “Track two”，采样率为 44.1、48 kHz。WebM、AVI、TS、纯音频和 MP3 封面使用合成画面或 A04 声道标记。V09a/b 从程序生成的逐帧 RGB 图案编码为 WMV3/WVC1，再配 A04 生成 WMA Standard v2 轨。H02-hevc.ts 是补充的 HEVC Annex B 夹具。

重新生成时使用桌面 FFmpeg/ffprobe，另备两份系统合成语音 WAV。命令会更新媒体及摘要，比较测试时应保留原轮次清单。

```text
python tools/generate-fixtures.py --ffmpeg <ffmpeg.exe> --speech-one <one.wav> --speech-two <two.wav>
gcc -O2 -I/usr/include/w32api tools/generate-wmv.c -o generate-wmv.exe -L/usr/lib/w32api -lmfreadwrite -lmfplat -lmfuuid -lole32
generate-wmv.exe WMV3 <临时目录>/V09a.wmv
generate-wmv.exe WVC1 <临时目录>/V09b.wmv
python tools/generate-fixtures.py --ffmpeg <ffmpeg.exe> --wmv-dir <临时目录>
```

GCC 命令使用 Windows 的 MSYS 环境。坏音频、坏视频、双轨损坏用例由 `tools/generate-track-faults.py` 在副本内改写指定轨道的包内容，来源及各自摘要在 [faults/manifest.json](../faults/manifest.json)。
