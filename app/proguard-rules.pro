# Compose / Kotlin defaults
-keep class kotlin.Metadata { *; }
-dontwarn org.jetbrains.annotations.**
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.vdl.videodownload.**$$serializer { *; }
-keepclassmembers class com.vdl.videodownload.** {
    *** Companion;
}
