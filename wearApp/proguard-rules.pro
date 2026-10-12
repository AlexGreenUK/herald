# kotlinx.serialization keeps generated serializers via its bundled consumer rules; the Wear libraries ship their own.

# WebRTC (live voice calls) is reached from native code by name, and its AAR ships no rules of its own.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# Its JNI glue too: JNI_OnLoad finds these by name, and renamed they abort the app as a call starts.
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**
