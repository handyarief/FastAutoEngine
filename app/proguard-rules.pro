# Pertahankan komponen manifest & entry point Locale/Automate plugin
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

-keep enum com.fastauto.engine.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class com.fastauto.engine.ScreenCaptureService$EvalOutcome {
    <init>(...);
    <fields>;
    <methods>;
}
