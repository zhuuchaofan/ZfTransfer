plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.xiaozhu.zftransfer"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.xiaozhu.zftransfer"
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "0.5.0-test"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("test-keystore.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2") }
