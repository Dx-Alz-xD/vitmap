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

# LuaJ: the javax.script engine and the bytecode compiler aren't used on Android.
-dontwarn javax.script.**
-dontwarn org.apache.bcel.**
-dontwarn org.luaj.vm2.script.**

# Sora editor's TextMate support reads grammars and themes reflectively (Gson) and uses joni.
-keep class org.eclipse.tm4e.** { *; }
-keep class io.github.rosemoe.sora.langs.textmate.** { *; }
-dontwarn org.eclipse.jdt.annotation.**
