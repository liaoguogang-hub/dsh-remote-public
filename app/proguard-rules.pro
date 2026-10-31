# Keep OkHttp internals used reflectively by its platform layer.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# We use org.json from the platform; nothing to keep.
-keep class com.dsh.remote.net.** { *; }
