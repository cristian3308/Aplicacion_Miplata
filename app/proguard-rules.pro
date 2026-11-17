# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# --- Room Database & Entities ---
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class com.example.data.** { *; }
-keepclassmembers enum com.example.data.** { *; }

# --- Firebase AI Logic & App Check ---
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations

# --- Kotlin Coroutines & Serialization ---
-dontwarn kotlinx.coroutines.**
