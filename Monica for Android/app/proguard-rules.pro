# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# 忽略 R8 警告
-dontwarn org.xmlpull.v1.**
-dontwarn android.content.res.**

# Room 数据库
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Kotlin 协程
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Compose - 保持必要的类
-dontwarn androidx.compose.**

# CameraX
-dontwarn androidx.camera.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.odml.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ZXing
-dontwarn com.google.zxing.**

# WebDAV (Sardine)
-dontwarn com.thegrizzlylabs.sardineandroid.**
-dontwarn okhttp3.**
-dontwarn okio.**

# 保留数据类
-keepclassmembers class takagi.ru.monica.data.** {
    <fields>;
    <init>(...);
}

# 保留 Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Rust JNI uses statically exported Java_* symbols, so R8 must not rename this facade.
-keep class takagi.ru.monica.rustcore.RustPasswordListCore { *; }

# 移除日志 (Release构建)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Koin 由代码引用驱动收缩；不要整包 keep，否则 R8 无法移除已废弃的 DI 壳。
-dontwarn org.koin.**

# 确保实体类不被混淆 (特别是用于 JSON 序列化的)
-keep class takagi.ru.monica.data.model.** { *; }
