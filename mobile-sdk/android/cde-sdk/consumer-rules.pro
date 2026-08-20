# kotlinx.serialization generates serializers reflectively by name; without
# these the models survive R8 but their serializers do not, and every payload
# fails to parse only in a release build.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.cde.sdk.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.cde.sdk.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.cde.sdk.model.**$$serializer { *; }
-keep,includedescriptorclasses class com.cde.sdk.net.**$$serializer { *; }
-keep,includedescriptorclasses class com.cde.sdk.offline.**$$serializer { *; }
