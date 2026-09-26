# kotlinx.serialization keeps generated serializers through its bundled consumer rules.
# commonmark uses reflection-free visitors; nothing extra to keep.

# The voice interaction and recognition services are referenced from XML metadata only.
-keep class nl.bartvandermeeren.aight.assist.** { *; }

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# sherpa-onnx reads its config objects and calls back into Kotlin from JNI by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class kotlin.jvm.functions.Function1 { *; }
-keep class nl.bartvandermeeren.aight.voice.SherpaVoice$Sink { java.lang.Integer invoke(float[]); }
