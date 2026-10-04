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
