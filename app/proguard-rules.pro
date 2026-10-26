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
