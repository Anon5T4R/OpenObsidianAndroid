# Compose + Kotlin reflect already kept by defaults.
# Keep our entry points (Activity, Application) in case of aggressive minification.
-keep public class com.openobsidian.android.MainActivity
-keepclassmembers class com.openobsidian.android.** { *; }
