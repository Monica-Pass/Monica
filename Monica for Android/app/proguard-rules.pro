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

# Google Play services (Auth / Credentials)
-dontwarn com.google.android.gms.**

# ProviderFactory reads this class name from Manifest metadata, then reflects
# its public Context constructor. Provider Events beta01 has no consumer rule
# for it; without this rule R8 removes the adapter and both transfer APIs fail.
-keep class androidx.credentials.providerevents.playservices.ProviderEventsApiProviderPlayServices {
    public <init>(android.content.Context);
}

# ZXing
-dontwarn com.google.zxing.**

# Bouncy Castle discovers algorithm Mappings and JCA service implementations by
# class name. R8 otherwise removes/renames them, so release GPG generation fails
# with NoSuchAlgorithmException (RSA for provider BC), although debug works.
# Preserve the provider adapters; the remaining crypto/OpenPGP code can shrink.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

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
-keep class takagi.ru.monica.rustcore.RustKeePassKdfCore { *; }
-keep class takagi.ru.monica.rustcore.RustListSortCore { *; }
-keep class takagi.ru.monica.rustcore.RustWalletStackCore { *; }
-keep class takagi.ru.monica.rustcore.RustVaultPickerCore { *; }
-keep class takagi.ru.monica.rustcore.RustAutofillCore { *; }

# 移除日志 (Release构建)
# Structured diagnostics use Log.println, so retain them without enabling general debug logs.
-keepnames class takagi.ru.monica.security.SecureStorageUnavailableException

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Koin 由代码引用驱动收缩；不要整包 keep，否则 R8 无法移除已废弃的 DI 壳。
-dontwarn org.koin.**

# 确保实体类不被混淆 (特别是用于 JSON 序列化的)
-keep class takagi.ru.monica.data.model.** { *; }
-keep class takagi.ru.monica.lansync.RustLanSync { *; }
