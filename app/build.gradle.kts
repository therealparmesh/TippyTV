plugins {
    id("com.android.application") version "9.5.1"
}

android {
    namespace = "com.parmesh.livetv"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.parmesh.livetv"
        minSdk = 23
        targetSdk = 37
        versionCode = 12
        versionName = "0.0.11"
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    val libVlc = rootProject.file("build/libvlc/libvlc-patched.aar")
    check(libVlc.isFile) { "Run ./tools/build-libvlc.sh to build the patched playback engine first." }
    implementation(files(libVlc))
    implementation("androidx.media:media:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
}
