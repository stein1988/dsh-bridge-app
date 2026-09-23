# 保留 WebView 中被 JS 反射调用的桥对象（本项目未使用 @JavascriptInterface，占位以防后续添加）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ZXing / OkHttp 常规保留
-dontwarn okhttp3.**
-dontwarn okio.**
