# Playback SDK Demo 混淆与压缩规则 (ProGuard / R8)

# 1. 保留行号与源文件信息，便于线上 Crash 堆栈反混淆排查
-keepattributes SourceFile,LineNumberTable

# 2. 保留反射相关与泛型签名属性
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 3. 基础组件防误裁（Activity 已由 AAPT2 自动保留，此处强化自定义组件）
-keep public class com.cn.media.playback.demo.DemoActivity { *; }

# 注：SDK 底层的 Native 绑定与 JNI 回调规则已由 sdk/consumer-rules.pro 自动合并导入，
# 宿主无需在此重复声明 NativeInput、PlaybackOutput 等底层 keep 规则。
