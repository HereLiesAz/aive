# R8 rules for release builds. Library consumer rules (Ktor, kotlinx.serialization,
# AndroidX) are applied automatically; entries here cover what those rules miss.

# Keep source file and line numbers so deobfuscated Play stack traces point at real lines.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# DJL HuggingFace tokenizers: the native library constructs Java objects by class name
# (e.g. ai/djl/huggingface/tokenizers/jni/CharSpan) and DJL discovers engines reflectively.
-keep class ai.djl.** { *; }

# ONNX Runtime: JNI code creates and reads Java objects by name.
-keep class ai.onnxruntime.** { *; }

# DJL's desktop-only helpers (image drawing, audio files, runtime compilation, JMX stats) reference
# JDK classes Android lacks. Aive only uses DJL's tokenizer, which never reaches them.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.sound.sampled.**
-dontwarn javax.tools.**
-dontwarn java.lang.management.**
