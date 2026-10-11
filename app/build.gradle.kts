plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.meowplay.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.meowplay.app"
        minSdk = 25
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.bouncycastle)
    implementation(libs.jmdns)
}
