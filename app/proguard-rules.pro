# kotlinx.serialization keeps generated serializers reachable through reflection-free lookups,
# but R8 needs the companion/serializer hints for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class dev.klaiber.cirrus.**$$serializer { *; }
-keepclassmembers class dev.klaiber.cirrus.** {
    *** Companion;
}
-keepclasseswithmembers class dev.klaiber.cirrus.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp platform-specific classes referenced only on some JDKs.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
