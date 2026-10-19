# ARSCLib loads framework resources and uses reflection-free code, but keep it whole to be safe:
# the patcher must behave exactly as it does in debug builds.
-keep class com.reandroid.** { *; }
-dontwarn com.reandroid.**
-keep class io.github.str8spir.carpatch.core.** { *; }
# apksig
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**
