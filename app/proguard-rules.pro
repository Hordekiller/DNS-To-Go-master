# --- General Rules ---
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes SourceFile,LineNumberTable
-printmapping mapping.txt

# Disabling optimizations as requested to prevent aggressive class merging
-dontoptimize

-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose
-dontpreverify

-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations

-keep public class com.google.vending.licensing.ILicensingService
-keep public class com.android.vending.licensing.ILicensingService

-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclassmembers public class * extends android.view.View {
   void set*(***);
   *** get*();
}
-keepclassmembers class * extends android.app.Activity {
   public void *(android.view.View);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class **.R$* {
    public static <fields>;
}

-keep class **$Properties

-dontwarn android.support.**
-keep public class * extends java.lang.Exception

# --- Butterknife ---
-keep class butterknife.*
-keepclasseswithmembernames class * { @butterknife.* <methods>; }
-keepclasseswithmembernames class * { @butterknife.* <fields>; }

# --- GreenDao ---
-keepclassmembers class * extends org.greenrobot.greendao.AbstractDao {
    public static java.lang.String TABLENAME;
}
-dontwarn org.greenrobot.greendao.database.**

# --- Google / GSON ---
-keep class com.google.** { *; }
-keep interface com.google.** { *; }
-dontwarn com.google.**

-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep public class * implements java.lang.reflect.Type

# --- Picasso ---
-dontwarn com.squareup.okhttp.**

# --- Retrofit ---
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-dontwarn okio.**
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# --- RxJava ---
-keep class rx.schedulers.Schedulers {
    public static <methods>;
}
-keep class rx.schedulers.ImmediateScheduler {
    public <methods>;
}
-keep class rx.schedulers.TestScheduler {
    public <methods>;
}
-keepclassmembers class rx.internal.util.unsafe.*ArrayQueue*Field* {
    long producerIndex;
    long consumerIndex;
}
-keepclassmembers class rx.internal.util.unsafe.BaseLinkedQueueProducerNodeRef {
    long producerNode;
    long consumerNode;
}
-dontwarn sun.misc.Unsafe
-dontwarn org.w3c.dom.bootstrap.DOMImplementationRegistry

# --- Apache ---
-keep class org.apache.** { *; }
-keep interface org.apache.** { *; }
-dontwarn org.apache.**

# --- MyID3 ---
-keep interface org.cmc.music.** { *; }
-keep class org.cmc.music** { *; }
-dontwarn org.cmc.music.**

# --- GSON Data Models (Task 3) ---
-keep class com.hololo.app.dnschanger.model.** { *; }
-keepclassmembers class com.hololo.app.dnschanger.model.** { *; }

# Keep SerializedName fields
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Also keep anything annotated with @Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
