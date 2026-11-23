# AndroidPlaybackSDK 本地制品接入

当前坐标为 `com.cn.media:android-playback-sdk:1.2.1`。AAR 声明最低 Android API 为 21，进程 ABI 为 arm64-v8a。每份归档附 `SHA256SUMS`，`candidate.json` 记录该版本的 AAR、Maven 附件、每个 ELF 的摘要与 Build ID。真机适配和质量签核以各版本的审核记录为准。

## 宿主接入

直接接入时，把 `public` 目录里的 AAR 复制到宿主模块的 `libs`，在模块的 Gradle 配置中添加以下依赖。SDK 使用 Android 和 Java 平台类，没有需要另行提供的 Java 运行时依赖。

```kotlin
dependencies {
    implementation(files("libs/android-playback-sdk-1.2.1.aar"))
}
```

离线接入时，解压 `offline-maven.zip` 到宿主的 `vendor/maven`。在宿主的 `settings.gradle.kts` 中限定该坐标的来源，构建插件及宿主自身的依赖沿用其已预置的仓库和缓存。

```kotlin
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri(rootDir.resolve("vendor/maven")) } }
            filter { includeModule("com.cn.media", "android-playback-sdk") }
        }
        google()
        mavenCentral()
    }
}
```

```kotlin
dependencies {
    implementation("com.cn.media:android-playback-sdk:1.2.1")
}
```

私库消费时将专属仓库的 URL 替换为发行方提供的 HTTPS 读端点，继续固定坐标。Android SDK、Gradle Wrapper、Android Gradle Plugin 和 Kotlin 宿主所需的工具应由宿主预置。仓库 ZIP 包含固定版本目录、AAR、POM、Gradle 模块元数据、源码与 Javadoc 及各文件校验值。R8 规则会随 AAR 自动合入宿主。

## 播放与生命周期

以下 Kotlin 片段接收宿主已获得读取权限的 `contentUri`，使用宿主持有的可用 `surface`。文件用 `MediaSource.fromFile(file)`。仅有音轨时可跳过 Surface 绑定。

```kotlin
val player = Player(context, PlayerConfig.Builder().build(), object : PlayerListener {
    override fun onCommandResult(result: CommandResult) {
        // Use result.requestId and result.status to complete the matching UI operation.
    }
})
player.setVideoSurface(surface)
player.open(MediaSource.fromUri(contentUri))
player.play()
```

Java 的对应入口为 `new Player(context, new PlayerConfig.Builder().build(), listener)`。暂停、恢复、毫秒定位分别调用 `pause()`、`play()`、`seekTo(positionMs)`。命令返回请求号，在回调中处理完成态；默认每个实例在主线程串行回调。进度默认间隔 250 ms。宿主须处理打开 FD 时的同步 IOException 和异步命令错误。直接传 FD 时保持它打开到 `open` 返回，SDK 使用自己的副本。顺序流从当前位置开始，定位能力由媒体快照给出。Surface 移交或销毁前解绑 `setVideoSurface(null)`，对需要自行释放的 Surface，等待该命令完成再释放。切换媒体可复用实例；退出时调用 `release()` 并观察结果。SDK 清单不声明组件或权限。

## 格式与集成条件

| 输入样本范围 | 音视频组合 |
|---|---|
| MP4、MOV、MKV、TS | H.264 或 HEVC 与 AAC；包含纯视频、双音轨及分别映射到对应封装的测试样本 |
| WebM、AVI、ASF/WMV | VP8/Vorbis、VP9/Opus、MPEG-4 Part 2/MP3、WMV3 或 VC-1/WMA2 |
| 单独音频 | MP3、AAC-LC/M4A、FLAC、PCM/WAV、Ogg Vorbis、Ogg Opus、WMA2/ASF |

SDK 的输入入口为本地文件、content URI 和 FD。MediaCodec 路径取决于设备、固件、轨道参数和可用 Surface；软件路径由五个 FFmpeg 共享库提供。首个性能测试基线是单路 H.264 1080p30 SDR。HEVC 性能、其他分辨率及机型需对应设备验收。支持范围、格式样本和验收记录须核对同一候选构建。

若宿主同时携带同名 native 库，应比对六个 SONAME、依赖和 Build ID。构建提示重复路径时，核实两方 ABI 兼容性并由依赖所有者消除冲突。直接使用 Gradle 的 `pickFirst` 会使一方在运行时链接到另一份二进制。

## 对应源码与重建

`corresponding-source.zip` 含 SDK 使用的 Java/C 源码、Gradle/CMake 配置、FFmpeg 8.1.3 原始源码包及分离签名、公钥和构建脚本。`licenses/` 包含这次配置所用的 FFmpeg 许可。归档不含本地 FFmpeg 补丁。下列命令在解压的源码根目录运行，需要 JDK 17、Android SDK 35、NDK 30.0.16248370、CMake 3.22.1、Bash/GNU Make 等 FFmpeg 构建工具。Windows 可使用 MSYS2。以 MSYS 或 Linux 路径设置 `ANDROID_NDK_ROOT`，在运行 Gradle 的环境设置 Android SDK 和 JDK 位置。

```sh
bash tools/build-ffmpeg.sh build/ffmpeg
mkdir -p third_party/ffmpeg/8.1.3/include artifacts/n01/stripped/arm64-v8a
cp -R build/ffmpeg/include/. third_party/ffmpeg/8.1.3/include/
cp build/ffmpeg/stripped/arm64-v8a/*.so artifacts/n01/stripped/arm64-v8a/
./gradlew :sdk:assembleRelease
```

重建得到的 `sdk/build/outputs/aar/sdk-release.aar` 可替换宿主直接引入的 AAR。自行修改 FFmpeg 时，在工作副本解开原始源码并保留修改记录，按相同 ABI 和 SONAME 构建共享库，再按上述步骤链接 JNI、重打 AAR、重建及签署宿主安装包。发行方的签署指纹和固定原始源码摘要用于验证上游归档，改动后的源码单独记录摘要。符号可用性依赖工具链和源码路径；实际交付的符号以候选 Build ID 为索引。

## 发行工程取证

在完整 SDK 开发工程中先运行 `./gradlew --offline :sdk:publishPlaybackPublicationToOfflineRepository` 写入本地文件仓库，再运行 `python tools/assemble-delivery.py --version 1.2.1`。Windows 使用 `gradlew.bat`。脚本使用 Python 3.9 以上、GnuPG 与 NDK LLVM 工具，另一个 Android SDK 位置可通过 `--ndk` 显式指定。

输出路径为 `artifacts/distribution/<版本>/<AAR摘要前缀>-<材料摘要前缀>/`。`public` 可供接入，`private/native-symbols.zip` 单独保管，按版本停止支持后至少 24 个月的约定留存。候选变更会生成新目录。根清单用于分别校验公开材料和私有归档。ELF 静态检查涵盖真实 AAR 的库集合、SONAME、Build ID、16 KB LOAD 段对齐与 AAR 声明的最低 API 平台符号。最低系统、16 KB 页环境、独立宿主安装及质量审核需相应的实测记录。
