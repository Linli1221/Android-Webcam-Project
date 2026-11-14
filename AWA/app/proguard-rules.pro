# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Ktor references java.lang.management classes which are not available on Android
-dontwarn java.lang.management.**

# JNI entry points of the Vulkan keyer (app/src/main/cpp/chroma_vulkan.cpp) are looked up by name.
-keep class com.soubhagyajit.awa.processing.vulkan.VulkanChromaKey { *; }

# ML Kit resolves its bundled model and the segmenter implementation by name at runtime. Its AARs
# ship consumer rules, but a release build that shrinks those away fails on the device with a
# NoClassDefFoundError that only shows up when background removal is switched on - so keep the
# entry points explicitly.
-keep class com.google.mlkit.vision.segmentation.** { *; }
-dontwarn com.google.mlkit.**
