# pdfbox-android loads font/cmap/glyph resources and optional crypto/JP2 classes reflectively.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.**
-dontwarn javax.**
-dontwarn java.awt.**

# Ktor / supabase / kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-dontwarn kotlinx.serialization.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn reactor.blockhound.**
-keep,includedescriptorclasses class com.folio.notes.**$$serializer { *; }
-keepclassmembers class com.folio.notes.** { *** Companion; }
-keepclasseswithmembers class com.folio.notes.** { kotlinx.serialization.KSerializer serializer(...); }

# kotlin-reflect (used by Ktor/supabase type resolution) reads kotlin.Metadata at runtime;
# stripping it throws "Unresolved class: class java.lang.String" and the app dies at launch.
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations, EnclosingMethod
-keep class kotlin.reflect.** { *; }
-keep class kotlin.jvm.internal.** { *; }
-keep class kotlin.coroutines.Continuation
-dontwarn kotlin.reflect.jvm.internal.**
