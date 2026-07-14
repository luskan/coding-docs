plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.myapp.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }
}

dependencies {
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.hilt.compiler)

    // Dagger 2.60 generates references to this annotation in every @Inject-owning module.
    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
}
