plugins {
    id("com.android.application") version "9.5.1"
}

android {
    namespace = "com.parmesh.livetv"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    sourceSets["main"].jniLibs.srcDir(rootProject.file("build/mpv/jni"))
    sourceSets["main"].assets.srcDir(rootProject.file("build/mpv/assets"))
    defaultConfig {
        applicationId = "com.parmesh.livetv"
        minSdk = 23
        targetSdk = 37
        externalNativeBuild { cmake { abiFilters += listOf("armeabi-v7a", "arm64-v8a") } }
        versionCode = 16
        versionName = "0.0.15"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    check(rootProject.file("build/mpv/jni/armeabi-v7a/libmpv.so").isFile) { "Run python3 tools/prepare-mpv.py first." }
    implementation("androidx.media:media:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
}
