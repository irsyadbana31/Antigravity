-keep class id.vern.antigravity.data.** { *; }
-keep class id.vern.antigravity.service.** { *; }
-keep class id.vern.antigravity.oauth.** { *; }
-keep class id.vern.antigravity.install.** { *; }
-keep class id.vern.antigravity.nativebridge.** { *; }

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

-keep class coil.** { *; }
-dontwarn coil.**

-keep class id.vern.antigravity.BuildConfig { *; }

-keep class id.vern.antigravity.shizuku.** { *; }
-keep class id.vern.antigravity.gateway.ShizukuShellUserService { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**
