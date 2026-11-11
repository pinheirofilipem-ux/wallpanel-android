# A ponte com a página (WebBridge) é chamada por JavaScript: os métodos marcados não podem ser renomeados nem removidos.
-keepclassmembers class pt.wallpanel.lite.WebBridge {
    @android.webkit.JavascriptInterface <methods>;
}
