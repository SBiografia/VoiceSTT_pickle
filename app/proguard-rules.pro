# sherpa-onnx JNI 바인딩은 리플렉션으로 네이티브 심볼을 찾으므로 이름이 유지되어야 한다.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
