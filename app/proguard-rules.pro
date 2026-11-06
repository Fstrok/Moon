-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface, *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class app.moon.data.** { *; }
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.re2j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# все @Serializable-классы приложения (JSON-хранилища, ответы JS)
-keep @kotlinx.serialization.Serializable class app.moon.** { *; }
