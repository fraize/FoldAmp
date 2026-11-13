plugins {
    id("com.android.application")
}

// Media3 is pinned to the 1.9 line so it matches the prebuilt Jellyfin FFmpeg decoder.
val media3 = "1.9.4"

android {
    namespace = "com.foldamp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.foldamp"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // .wsz skins are zips; keep them uncompressed so they open fast.
        noCompress += "wsz"
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}

// `gradlew runDebug`: build, install on the connected device or emulator, and launch. Uses the SDK's
// own adb, so it works the same on macOS, Linux and Windows without adb on the PATH.
val adb = androidComponents.sdkComponents.adb
tasks.register<Exec>("runDebug") {
    group = "install"
    description = "Installs the debug build and launches FoldAmp."
    dependsOn("installDebug")
    doFirst { executable = adb.get().asFile.absolutePath }
    args("shell", "am", "start", "-n", "com.foldamp/.ui.MainActivity")
}
