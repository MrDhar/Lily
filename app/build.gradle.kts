import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// Release signing is deliberately kept out of version control. Create a
// keystore.properties file next to this build script (never committed —
// see .gitignore) with:
//   storeFile=/absolute/or/relative/path/to/your.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
// Until that file exists, the release build type simply has no signing
// config attached, so `assembleDebug` and CI keep working unchanged, and
// `assembleRelease` still succeeds but produces an unsigned APK/AAB that
// you'd need to sign yourself before publishing.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

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
    if (keystorePropertiesFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Unchanged from the previous implicit default: unminified, debuggable.
        }
    }
}

