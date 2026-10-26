# WebView JavaScript bridges used by AllBrowser's inspector.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# PdfBox-Android: optional JPEG 2000 decoder isn't bundled.
-dontwarn com.gemalto.jp2.**

# commons-compress: optional codecs that aren't bundled.
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn io.airlift.compress.**
-dontwarn org.osgi.**

# LuaJ: the javax.script engine and the bytecode compiler aren't used on Android. Its standard
# libraries look up their function classes by name (Class.forName), so R8 must keep them as is.
-keep class org.luaj.vm2.** { *; }
-dontwarn javax.script.**
-dontwarn org.apache.bcel.**
-dontwarn org.luaj.vm2.script.**

# Sora editor's TextMate support reads grammars and themes reflectively (Gson) and uses joni.
-keep class org.eclipse.tm4e.** { *; }
-keep class io.github.rosemoe.sora.langs.textmate.** { *; }
-dontwarn org.eclipse.jdt.annotation.**

# ML Kit creates its components by reflection from registrar class names listed in the manifest.
# R8's full mode drops constructors nothing calls directly, which left translation (and the other
# ML Kit features) without their components in release builds.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.**.*Registrar { <init>(); }
