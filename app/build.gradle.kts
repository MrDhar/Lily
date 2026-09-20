plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace="com.maalik.projectlily"
    compileSdk=35
    defaultConfig { applicationId="com.maalik.projectlily"; minSdk=23; targetSdk=35; versionCode=44; versionName="4.4" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
