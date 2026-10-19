# CP8: release keep rules (minify + shrinkResources enabled). Checked
# against the pinned artifacts in the local Gradle cache (offline):
# - media3-common/session/exoplayer 1.5.1 AARs ship only dontwarns plus one
#   keepclassmembernames (no documented extra keeps required); the session,
#   exoplayer and common surfaces used here are kept conservatively below
#   because the version-pinned online docs were unavailable offline.
# - room-runtime 2.6.1 ships "-keep class * extends RoomDatabase" itself.
# - coil-compose 2.6.0 ships no consumer rules, kept conservatively.
# Direct code references keep most of this anyway; these rules are the belt.

# kotlinx.serialization: keep generated serializers and companions for ours.
-keepattributes *Annotation*, InnerClasses
-keepattributes Signature
-keepattributes EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class app.auloud.player.bundle.**$$serializer { *; }
-keepclassmembers class app.auloud.player.bundle.** {
    *** Companion;
}
-keepclasseswithmembers class app.auloud.player.bundle.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class kotlinx.serialization.** { *; }

# Room: entities, DAOs and the database (the runtime keeps RoomDatabase).
-keep class app.auloud.player.data.** { *; }

# Media3 session + exoplayer + common (conservative; see note above).
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.common.** { *; }

# Guava futures used by PlaybackController (Media3 buildAsync/addListener).
-keep class com.google.common.util.concurrent.** { *; }

# Coil image loading (conservative; 2.6.0 ships no consumer rules).
-keep class coil.** { *; }

# PDF1: PdfBox-Android text extraction (2.0.27.0 ships no consumer
# rules; reflection in font handling means conservative keeps; proof is
# the release assemble plus the unit suites, same basis as Coil above).
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class org.apache.pdfbox.** { *; }
-keep class org.apache.fontbox.** { *; }
# Text extraction only: the JPX image module (JCenter-only JP2
# dependency, deliberately excluded) is never on the path; silence R8
# about its optional classes (AGP-suggested rules verbatim).
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder

# VC2 (D-129): sherpa-onnx JNI bridge (full flavor only). The native
# methods in libsherpa-onnx-jni.so are looked up by Java name at
# runtime, so the com.k2fsa classes and their members must survive
# minify untouched. Proof is the full-flavor release assemble plus
# the testFull suites; the JVM never loads the native lib.
-keep class com.k2fsa.sherpa.onnx.** { *; }
