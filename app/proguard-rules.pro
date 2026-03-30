# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# SLF4J (Ktor dependency — no runtime impl needed)
-dontwarn org.slf4j.**

# Google Tink / ErrorProne annotations (EncryptedSharedPreferences dependency)
-dontwarn com.google.errorprone.annotations.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.signalbridge.app.**$$serializer { *; }
-keepclassmembers class com.signalbridge.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.signalbridge.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
