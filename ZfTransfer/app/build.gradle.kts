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
        versionCode = 21
        versionName = "0.5.11-test"
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
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
dependencies { testImplementation("junit:junit:4.13.2") }
