# ClipAI v2 ProGuard / R8 Rules

# Keep Kotlin metadata and coroutines
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class kotlin.Metadata { *; }

# Keep Room database entities and generated implementations
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep Media3 Transformer / ExoPlayer / Effect classes
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep LiteRT-LM JNI bindings
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Keep MediaPipe Tasks Vision JNI bindings
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Keep ONNX Runtime JNI bindings
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
