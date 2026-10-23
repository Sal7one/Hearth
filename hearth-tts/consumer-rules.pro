# JNI names and PCM callbacks are resolved by the native adapters, including onPcm via GetMethodID.
# This single AAR owns all adapter rules; consumers do not depend on separate family modules.
-keep class com.sal7one.common_jni.voice.SupertonicNative { *; }
-keep class com.sal7one.common_jni.MingNative { *; }

-keep class com.sal7one.common_jni.voice.MagpieNative { *; }
-keep interface com.sal7one.common_jni.voice.MagpiePcmCallback { *; }
-keepclassmembers class * implements com.sal7one.common_jni.voice.MagpiePcmCallback { boolean onPcm(short[], int); }

-keep class com.sal7one.common_jni.voice.OmniVoiceNative { *; }
-keep interface com.sal7one.common_jni.voice.OmniVoicePcmCallback { *; }
-keepclassmembers class * implements com.sal7one.common_jni.voice.OmniVoicePcmCallback { boolean onPcm(float[], int); }

-keep class com.sal7one.common_jni.voice.MossNative { *; }
-keep interface com.sal7one.common_jni.voice.MossPcmCallback { *; }
-keepclassmembers class * implements com.sal7one.common_jni.voice.MossPcmCallback { boolean onPcm(float[], int); }

-keep class com.sal7one.common_jni.voice.OuteNative { *; }
-keep interface com.sal7one.common_jni.voice.OutePcmCallback { *; }
-keepclassmembers class * implements com.sal7one.common_jni.voice.OutePcmCallback { boolean onPcm(float[], int); }

-keep class com.sal7one.common_jni.voice.ChatterboxNative { *; }
-keep interface com.sal7one.common_jni.voice.ChatterboxPcmCallback { *; }
-keepclassmembers class * implements com.sal7one.common_jni.voice.ChatterboxPcmCallback { boolean onPcm(float[], int); }
