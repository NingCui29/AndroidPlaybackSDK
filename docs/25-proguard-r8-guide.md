# Playback SDK 混淆与压缩配置指南 (ProGuard / R8)

## 1. 概述与设计理念

Android Playback SDK 采用 **轻量化自动下发** 的混淆治理策略：
- **SDK 端（自动化下发）**：通过 `consumerProguardFiles 'consumer-rules.pro'` 将底层必须保持的 JNI 符号、C/C++ 反射构造方法、Direct ByteBuffer 回调契约及枚举常量打包至 AAR 的 `proguard.txt` 中。
- **宿主端（零手动负担）**：宿主 App（或 Demo 应用）启用 R8 混淆（`minifyEnabled true`）时，Android 构建工具（AGP/R8）会**自动合并** SDK 附带的混淆规则，开发者**无需**在主工程手动复制粘贴底层 JNI 规则。
- **最大化混淆收益**：SDK 的公共接口（如 `Player`、`PlayerConfig` 等）在宿主代码中可随宿主正常被 R8 混淆压缩（重命名为短名称 `a.b.c` 并进行无用方法剔除），确保宿主 APK 达到极致的代码体积和保护效果。

---

## 2. SDK 消费者混淆规则 (`sdk/consumer-rules.pro`) 深度解析

以下规则已内置打包进 SDK AAR，在宿主开启 `minifyEnabled true` 时自动生效：

```proguard
# ----------------------------------------------------------------------------------
# 1. 注解缺失警告屏蔽
# ----------------------------------------------------------------------------------
# SDK 中的 @NotNull 与 @Nullable 仅在编译期生效。纯 Java 宿主运行时无需依赖此类注解库。
-dontwarn org.jetbrains.annotations.NotNull
-dontwarn org.jetbrains.annotations.Nullable

# ----------------------------------------------------------------------------------
# 2. JNI 原生符号绑定保护 (C/C++ -> Java 静态符号绑定)
# ----------------------------------------------------------------------------------
# 底层 C 语言通过 Java_com_cn_media_playback_NativeInput_<method> 静态链接。
# 类名与所有 native 方法名严禁重命名或裁剪。
-keep class com.cn.media.playback.NativeInput { native <methods>; }
-keep class com.cn.media.playback.AudioTrackProbe { native <methods>; }

# ----------------------------------------------------------------------------------
# 3. JNI 反射实例构造与枚举映射保护 (C/C++ -> Java 动态反射)
# ----------------------------------------------------------------------------------
# 底层 media_info.c 通过 FindClass 和 GetMethodID("<init>", ...) 实例化 TrackInfo 与 MediaInfo。
-keep class com.cn.media.playback.TrackInfo { <init>(...); }
# TrackInfo.Type 在 Java 层通过 Type.values()[type] 基于 C 层传入的整数直接寻址，禁止裁剪枚举常量
-keepclassmembers enum com.cn.media.playback.TrackInfo$Type { *; }

-keep class com.cn.media.playback.MediaInfo { <init>(...); }
# MediaInfo.ProbeLimit 同样基于 ProbeLimit.values()[limit] 整数寻址
-keepclassmembers enum com.cn.media.playback.MediaInfo$ProbeLimit { *; }

# ----------------------------------------------------------------------------------
# 4. 音频输出接口反射契约
# ----------------------------------------------------------------------------------
# 底层 audio_output.c / dual_output.c 通过 GetMethodID 反射调用 PcmOutput 的配置与写入
-keepclassmembers class com.cn.media.playback.PcmOutput {
    int configure(int,int);
    int write(java.nio.ByteBuffer);
}

# ----------------------------------------------------------------------------------
# 5. 会话渲染核心回调管道保护 (session_output.c)
# ----------------------------------------------------------------------------------
# C 工作线程在渲染生命周期内通过 GetMethodID 高频调用以下回调，方法签名与名称必须严格保留：
-keepclassmembers class com.cn.media.playback.PlaybackOutput {
    int beginAudio(int,long,boolean);
    int selectedAudioIndex();
    long selectedAudioFloorUs();
    long audioSelectionVersion();
    int switchingAudioFailed(int,int);
    int configure(int,int);
    int write(java.nio.ByteBuffer);
    int endAudio();
    android.view.Surface acquireVideo(long,long);
    int lateVideo(long,long,long);
    void softwareVideoInfo(int,long);
    void finishVideo(long,boolean);
    int endVideo();
    void stop();
    int initHardware(int,int,int,float,java.nio.ByteBuffer);
    int hardwarePacket(java.nio.ByteBuffer,long,long,boolean,java.nio.ByteBuffer);
    int flushHardwarePicture();
    int configurationWaitMs();
    void softwareFallback();
    long positionMs();
    int trackUnavailable(boolean,int);
}
```

---

## 3. 宿主 App / Demo 应用混淆规则 (`proguard-rules.pro`)

在宿主应用的 `demo/proguard-rules.pro` 或接入方工程中，推荐的基础混淆配置如下：

```proguard
# 1. 保留行号与源文件属性（线上崩溃 Crash 堆栈反混淆必备）
-keepattributes SourceFile,LineNumberTable

# 2. 保留泛型签名与注解属性
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 3. 宿主 Activity / UI 组件（AAPT2 会自动保留，此处可补充宿主特定组件）
-keep public class com.cn.media.playback.demo.DemoActivity { *; }

# ----------------------------------------------------------------------------------
# 4. 可选：若宿主采用“反射”或“插件化”方式动态调用 SDK 公共 API
# ----------------------------------------------------------------------------------
# 若宿主通过 Class.forName 动态加载播放器，可放开如下规则保护公共类及公有方法：
# -keep public class com.cn.media.playback.Player { public *; }
# -keep public class com.cn.media.playback.PlayerConfig** { public *; }
# -keep public class com.cn.media.playback.PlayerListener { public *; }
# -keep public class com.cn.media.playback.MediaSource { public *; }
# -keep public class com.cn.media.playback.PlayerSnapshot { public *; }
# -keep public class com.cn.media.playback.PlaybackStats { public *; }

# ----------------------------------------------------------------------------------
# 5. 可选：若宿主需要对 PlayerSnapshot / TrackInfo 执行 JSON 序列化 (Gson/FastJson)
# ----------------------------------------------------------------------------------
# -keepclassmembers class com.cn.media.playback.TrackInfo { public <fields>; }
# -keepclassmembers class com.cn.media.playback.MediaInfo { public <fields>; }
# -keepclassmembers class com.cn.media.playback.PlayerSnapshot { public <fields>; }
```

---

## 4. 目标真机 R8 混淆版本实测记录

### 4.1 构建配置 (`demo/build.gradle`)
```groovy
    buildTypes {
        release {
            minifyEnabled true
            signingConfig signingConfigs.debug
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
```

### 4.2 验证结果（目标真机实测）
- **构建输出**：产物 `demo-release.apk` 体积 7.16 MB（内含全套 64 位 FFmpeg 瘦身 Native 动态库）。
- **R8 映射文件**：生成于 `demo/build/outputs/mapping/release/`：
  - `seeds.txt`：精确保留 `NativeInput`、`AudioTrackProbe`、`PlaybackOutput`、`TrackInfo` 等所有关键 Native 锚点；
  - `mapping.txt`：宿主与非关键公共逻辑深度混淆，消除无效冗余元数据。
- **真机运行状态**：
  - 硬件解码（`MediaCodec Direct`）无缝出图，1080P 渲染丢帧率 **0.00%**；
  - 运行时进程物理内存（PSS）从 Debug 版本的 55~65 MB 进一步降低至 **44.71 MB ~ 54.48 MB**；
  - 动态文件选择（SAF / 内置目录浏览器）与比例切换均正常无阻，无任何 `UnsatisfiedLinkError`、`NoSuchMethodError` 或 `ClassNotFoundException`。
