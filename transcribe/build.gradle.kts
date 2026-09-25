plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "org.fossify.voicerecorder.transcribe"
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        ndk {
            // Drop x86 / x86_64 to roughly halve APK size; emulator users only.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    compileOptions {
        val currentJavaVersionFromLibs = JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    kotlin {
        jvmToolchain(project.libs.versions.app.build.kotlinJVMTarget.get().toInt())
    }

    sourceSets {
        getByName("main").java.directories.add("src/main/kotlin")
    }
}

dependencies {
    implementation(project(":store"))
    implementation(libs.sherpa.onnx)
    implementation(libs.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.coroutines.android)
}
