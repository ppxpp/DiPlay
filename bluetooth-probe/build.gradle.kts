plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.shilapi.xcertplay.btprobe"
    compileSdk { version = release(37) }
    defaultConfig {
//        applicationId = "com.shilapi.xcertplay.btprobe"
        applicationId = "com.gtmc.app.fengyunyx.b819"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        multiDexEnabled = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
}
dependencies {
    implementation(project(":shared"))
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.core:core:1.13.1")
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
}
