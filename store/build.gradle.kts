plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "org.fossify.voicerecorder.store"
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        val currentJavaVersionFromLibs = JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    kotlin {
        jvmToolchain(project.libs.versions.app.build.kotlinJVMTarget.get().toInt())
    }
}

dependencies {
    implementation(libs.fossify.commons)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.rules)
    androidTestImplementation(libs.androidx.test.runner)
}
