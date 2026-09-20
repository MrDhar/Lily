# Project Lily release shrinking/obfuscation rules.
#
# The app has no reflection-based libraries (no Gson/Retrofit/Room/etc.) and
# uses the plain Android SQLite APIs, so the default R8 rules are sufficient.
# These keep rules exist only to protect the few areas that are sensitive to
# obfuscation: anything accessed by the Android framework via reflection.

# Keep the Activity entry point's class name stable — referenced by fully
# qualified name in AndroidManifest.xml.
-keep class com.maalik.projectlily.MainActivity { *; }

# Keep line number information for readable stack traces in crash reports,
# but strip the source file name to avoid leaking layout of the project.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Standard Android/Kotlin metadata needed for reflection-based platform
# features (e.g. Parcelable CREATOR fields) to keep working after shrinking.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepattributes *Annotation*
