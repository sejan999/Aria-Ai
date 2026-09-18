# ---------------------------------------------------------------------------
# Aria Ai - ProGuard / R8 rules (pure Kotlin, no native sources)
# Minification is disabled for the debug build; these rules keep the release
# build safe for Hilt, Room, Moshi and the on-device ML task libraries.
# ---------------------------------------------------------------------------

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# Moshi
-keep class com.squareup.moshi.** { *; }
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonQualifier @interface *

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Compose
-dontwarn androidx.compose.**

# MediaPipe / TensorFlow Lite task libraries (pure Kotlin API surface)
-keep class com.google.mediapipe.tasks.** { *; }
-keep class org.tensorflow.lite.task.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn org.tensorflow.lite.**

# Aria Ai model & agent classes (accessed reflectively by Hilt/Room in places)
-keep class com.aria.ai.core.ai.model.** { *; }
-keep class com.aria.ai.data.local.** { *; }