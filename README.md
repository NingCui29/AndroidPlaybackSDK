# AndroidPlaybackSDK

独立 Android 播放库。FFmpeg 8.1.3 读取容器、分包、解码音频和软件视频。可用的 Android MediaCodec 解码视频后直接输出到宿主 Surface，PCM 由 AudioTrack 播放。首版以 RK3588 NEXBoard（本轮 Android 12）进行真机验收，发行 ABI 为 arm64-v8a，公开包名为 `com.cn.media.playback`。

## 接入

开发候选坐标为 `com.cn.media:android-playback-sdk:1.0.0-rc.1`。在交付目录按 `SHA256SUMS` 核验文件。直接接入时将 AAR 放到应用模块的 `libs` 下，使用 Kotlin DSL 的 `implementation(files("libs/android-playback-sdk-1.0.0-rc.1.aar"))`。

11 月 16 日本地最新候选的 AAR 摘要以 `4986a12f` 开头，已全量补充中文 Javadoc 文档并完成真机软硬件解码验收。交付物包括 [AAR](artifacts/distribution/1.0.0-rc.1/4986a12f91c1206d-89bb000db577/public/android-playback-sdk-1.0.0-rc.1.aar)、[离线 Maven 仓库](artifacts/distribution/1.0.0-rc.1/4986a12f91c1206d-89bb000db577/public/offline-maven.zip)和[候选清单](artifacts/distribution/1.0.0-rc.1/4986a12f91c1206d-89bb000db577/public/candidate.json)。该坐标目前用于本地演练与真机回归。

使用离线仓库时解压 `offline-maven.zip`，将含有 `com/cn/media` 的目录配置为 Maven 根。远程私库使用相同坐标，仓库根改为获准读取的 HTTPS 地址。不要同时声明两种来源。独立 Java/Kotlin R8 宿主及按来源隔离的验证配置见 [acceptance/consumer](acceptance/consumer/)。

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository { maven { url = uri("vendor/offline-maven") } }
            filter { includeModule("com.cn.media", "android-playback-sdk") }
        }
    }
}
// app/build.gradle.kts
// dependencies { implementation("com.cn.media:android-playback-sdk:1.0.0-rc.1") }
```

## 创建播放器

```java
Player player = new Player(context, new PlayerConfig.Builder().build(), new PlayerListener() {
    @Override public void onError(PlayerError error, PlayerSnapshot snapshot) {
        android.util.Log.w("HostPlayer", error.code + " " + error.stage);
    }
});
player.setVideoSurface(holder.getSurface());  // 有画面时绑定有效 Surface
player.open(MediaSource.fromUri(contentUri)); // 方法声明 IOException
player.play();
```

Kotlin 使用同一接口。

```kotlin
val player = Player(context, PlayerConfig.Builder().build(), object : PlayerListener {})
player.open(MediaSource.fromFile(file))
player.play()
```

文件、获权限的 content URI 和带偏移／长度的 FD 均可输入。宿主传 FD 时保持打开至 `open` 返回，SDK 在接收请求时复制描述符。调用 `seekTo` 前检查 `snapshot.media.seekable`。默认回调在主线程串行交付，进度间隔为 250 ms，可按请求编号在 `onCommandResult` 核对终态。主动解绑使用 `setVideoSurface(null)` 并等待完成。当前源码另提供 `detachVideoSurface(holder.getSurface())`，适用于系统调用 `surfaceDestroyed` 时同步撤销旧输出。页面停止播放可用 `stop()` 后重用实例，宿主永久退出时调用 `release()`。

## 构建与验证

使用 JDK 17、Android SDK 35、NDK 30.0.16248370 和 CMake 3.22.1。在项目根目录运行以下 PowerShell 命令。

```powershell
.\gradlew.bat --offline :sdk:assembleRelease :sdk:assembleDebugAndroidTest
.\gradlew.bat --offline :sdk:publishPlaybackPublicationToOfflineRepository
python tools/assemble-delivery.py
.\tools\run-format-matrix.ps1 -Serial <设备标识>
```

本地 Maven 任务写入 `build/offline-maven`，装配后的候选在 `artifacts/distribution`。已有候选有自己的源码、符号和摘要，工作区的后续修复需重新组装并按新摘要回归。实现范围、真机证据与未验项目分别见[设计索引](docs/README.md)、[实施与验证记录](docs/21-implementation.md)、[本地交付记录](docs/d01-local-delivery.md)。
