plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.myapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.myapp.practice"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Dagger 2.60 still emits @CanIgnoreReturnValue but no longer supplies its annotation.
    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
}
