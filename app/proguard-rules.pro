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

# ML Kit pulls in WorkManager 2.7 / Room 2.2, whose consumer rules predate R8 full mode and
# keep classes without their constructors. R8 then strips WorkDatabase_Impl.<init>() and the
# app dies at launch ("Failed to create an instance of androidx.work.impl.WorkDatabase").
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
-keep class * extends androidx.work.InputMerger { <init>(); }
# ML Kit's component registrars are listed in the manifest and built reflectively; without
# their constructors OCR and ink recognition fail with "Invalid component registrar".
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
