plugins {
    id("com.android.application")
}
android {
    namespace = "dev.gamingbrowser"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.gamingbrowser"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }
    signingConfigs {
        // Clave fija (personal) para que el fingerprint de assetlinks.json no cambie entre builds
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "gamingbrowser"
            keyPassword = "android"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.5.0")
}
