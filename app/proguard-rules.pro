# Compose + Kotlin reflect already kept by defaults.
# Keep our entry points (Activity, Application) in case of aggressive minification.
-keep public class com.openobsidian.android.MainActivity
-keepclassmembers class com.openobsidian.android.** { *; }

# Markwon image module references optional SVG/GIF decoders we don't ship.
-dontwarn com.caverock.androidsvg.**
-dontwarn pl.droidsonroids.gif.**
