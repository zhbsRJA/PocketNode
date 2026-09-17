# ═══════════════════════════════════════════════════════════════
# R8 / ProGuard 规则
#
# 核心原则：凡是「Java 层和 native/C++ 层通过名字互相找对方」的代码，
# 都不能混淆。混淆之后 Java 方法名变成 a.b.c，而 C++ 那边硬编码的
# 还是原来的名字，运行时就会抛 UnsatisfiedLinkError 或静默失败。
#
# MediaPipe 正是这种重灾区 —— 它的推理引擎全是 C++，Java 这边只是个壳。
# ═══════════════════════════════════════════════════════════════

# ── MediaPipe 全家保留 ──────────────────────────────────────────
# 不写这条，release 包会在 createFromOptions 处直接崩。
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }
-keep interface com.google.mediapipe.** { *; }

# ── 所有 native 方法必须保名 ─────────────────────────────────────
# JNI 的函数名是 Java_包名_类名_方法名 拼出来的，方法名一改就断。
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── MediaPipe 依赖的两个代码生成库 ──────────────────────────────
# AutoValue 生成 AutoValue_XXX 子类，protobuf 生成 XXXProto 类，
# 都是运行时按名字反射找的。
-keep class com.google.auto.value.** { *; }
-dontwarn com.google.auto.value.**

-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# ── Guava（MediaPipe 用到 ListenableFuture）─────────────────────
-dontwarn com.google.common.**
-dontwarn com.google.j2objc.**
-keep class com.google.common.util.concurrent.** { *; }

# ── Kotlin 协程 ────────────────────────────────────────────────
# 协程内部有类名字符串拼接，不能混淆
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ── 本项目的模型数据结构 ────────────────────────────────────────
# 现在是普通 data class，没有反射访问，理论上可以混淆。
# 但保留它们能让崩溃堆栈可读 —— 出问题时省很多时间。
-keep class io.github.zhbsrja.pocketnode.data.** { *; }
-keep class io.github.zhbsrja.pocketnode.inference.** { *; }

# ── 保留行号，方便看崩溃堆栈 ────────────────────────────────────
# 不保留的话，堆栈里全是 "Unknown Source"，排查等于瞎猜。
# 代价是 APK 大一点点，值得。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── 保留注解和泛型签名 ──────────────────────────────────────────
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ── 不要删任何日志 ──────────────────────────────────────────────
# 这里本来有一条常见的优化规则：
#   -assumenosideeffects class android.util.Log {
#       public static *** d(...);
#       public static *** v(...);
#   }
# 它会把这些日志调用整个抹掉，省几百字节。
#
# 删掉它的原因：我们的 AppLog 内部就调 Log.d / Log.i（见 AppLog.kt 的
# when(level) 分支）。这条规则一开，debug 级别的日志在 release 包里
# 就彻底消失了 —— 而 release 包恰恰是最需要日志的时候。
# 排查时对着空白的 logcat 发呆，比 APK 大几百字节难受得多。

# ═══════════════════════════════════════════════════════════════
# 缺失类的警告抑制
#
# 这段是 R8 在 release 构建时报出来的：
#   Missing class com.google.mediapipe.framework.image.MPImage
#   (referenced from: LlmTaskRunner.createImage(MPImage))
#
# 原因：这些是 MediaPipe 的「视觉」类，属于 tasks-vision 库。
# 我们只依赖 tasks-genai（文本 LLM），没有引 vision。
# 但 tasks-genai 里的多模态接口（addImage / createImage）签名上
# 引用了 MPImage，R8 静态分析时就发现目标类不存在。
#
# 为什么可以安全忽略：
#   代码里从不调用这些多模态方法 —— 我们只走 generateResponse()
#   纯文本路径。R8 只是「看到了引用」，不代表运行时会走到那里。
#   真要启用图片输入，得另外加上 com.google.mediapipe:tasks-vision
#   依赖，那时候应该把这些 -dontwarn 删掉。
#
# 这几行是 AGP 自动生成在
#   app/build/outputs/mapping/release/missing_rules.txt
# 里的，直接抄过来，别自己凭感觉写。
# ═══════════════════════════════════════════════════════════════
-dontwarn com.google.mediapipe.framework.image.BitmapExtractor
-dontwarn com.google.mediapipe.framework.image.ByteBufferExtractor
-dontwarn com.google.mediapipe.framework.image.MPImage
-dontwarn com.google.mediapipe.framework.image.MPImageProperties
-dontwarn com.google.mediapipe.framework.image.MediaImageExtractor
