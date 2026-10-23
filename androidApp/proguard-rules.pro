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

# JGit (Local Git on Android): reflection-free core, but it references JVM-only APIs
# (management, JSch, Apache HTTP) that Android lacks and that the app never calls.
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn com.googlecode.javaewah.**
-keep class org.eclipse.jgit.internal.JGitText { *; }
-keep class org.eclipse.jgit.lib.CoreConfig$* { *; }

# kotlinx.serialization: generated serializers are found by name (Foo.Companion.serializer() and
# Foo$$serializer). Without these keeps, release builds can fail at runtime decoding the Azphalt
# Store's JSON, settings and persisted workflow state, with errors that carry no message.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.hereliesaz.**$$serializer { *; }
-keepclassmembers class com.hereliesaz.** { *** Companion; }
-keepclasseswithmembers class com.hereliesaz.** { kotlinx.serialization.KSerializer serializer(...); }

# Azphalt Store models: decoded from azphalt.store JSON by the generated serializers above; keep the
# whole package so no model class or member the decoder reaches is shrunk or renamed.
-keep class com.hereliesaz.geministrator.azphalt.** { *; }

# Ktor discovers HTTP client engines and plugins through ServiceLoader and reflection.
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.**

# OkHttp (Ktor's Android engine for the Store and model downloads) and Okio: optional TLS providers
# and platform classes they probe for are absent on Android.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
