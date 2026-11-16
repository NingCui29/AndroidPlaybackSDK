# Android 6.0 arm64 模拟器验收

2026-11-13 从封存 AAR d6e8ee93 解出六个 arm64 共享库，在 API 23 客体上以 `RTLD_NOW` 加载 `libplayback.so`。加载器解析了依赖和重定位，可读取 FFmpeg 版本 8.1.3，并调用 JNI 库中的输入 FD 计数入口，初始值为 0。这次运行覆盖原生加载；Java 宿主的安装、Surface 输出和设备性能仍须单独验证。[候选摘要](../artifacts/validation/20261113-132400-rc-board/n01-api23/aar.json) · [运行输出](../artifacts/validation/20261113-132400-rc-board/n01-api23/native.txt)

2026-11-13，Windows x86_64 主机使用 Android SDK 提供的 Android 6.0 arm64-v8a 默认镜像。Google 镜像修订号为 7，官方清单 SHA-1 为 `ac18f3bd717e02804eee585e029f5dbc1a2616bf`，下载归档校验一致。D02 的最低版本真机当前无法提供，本页登记模拟器覆盖。镜像和组件均与 SDK 的 AAR 分开保存。[镜像索引](https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml)

ADB 已读取该系统的 API 23、ABI `arm64-v8a` 和 4 KB 内核页，固件为 `Android/sdk_phone_arm64/generic_arm64:6.0/MASTER/8690019:userdebug/test-keys`。镜像缺少 `getconf`，页大小来自进程的 `/proc/self/smaps`。素材字节由宿主从 `adb exec-out` 回读并计算 SHA-256。[设备记录](../artifacts/validation/20261113-api23-emulator/device.txt)

Android Emulator 36.6.11 的 Windows 启动器在自动选择后端时报告不支持该架构。使用其随包提供的 `qemu/windows-x86_64/qemu-system-aarch64-headless.exe`、`-accel off` 和软件 GPU 可启动 arm64 客体，设备端口为 `emulator-5582`。运行所需的 AVD 配置、启动器诊断和客户机启动日志均已留存。软件 CPU 和 GPU 的运行时间只用于分析本环境的用例。[AVD 配置及运行记录](../artifacts/validation/20261113-api23-emulator/)
