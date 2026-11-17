# Nullability is carried in CLASS-retained annotations for Java/Kotlin tooling.
# A Java-only consumer may have neither annotation on its runtime classpath.
-dontwarn org.jetbrains.annotations.NotNull
-dontwarn org.jetbrains.annotations.Nullable
-keep class com.cn.media.playback.NativeInput { native <methods>; }
-keepclassmembers class com.cn.media.playback.PcmOutput { int configure(int,int); int write(java.nio.ByteBuffer); }
-keep class com.cn.media.playback.TrackInfo { <init>(...); }
-keepclassmembers enum com.cn.media.playback.TrackInfo$Type { *; }
-keep class com.cn.media.playback.MediaInfo { <init>(...); }
-keepclassmembers enum com.cn.media.playback.MediaInfo$ProbeLimit { *; }
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
-keep class com.cn.media.playback.AudioTrackProbe { native <methods>; }
