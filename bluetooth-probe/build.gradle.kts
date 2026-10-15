plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.shilapi.xcertplay.btprobe"
    compileSdk { version = release(37) }
    defaultConfig {
        // 车机的安装白名单只放行少量包名（如 com.tencent.mm）；
        // 其他包名 adb install 会报 INSTALL_FAILED_VERIFICATION_FAILURE: app is not in the whitelist。
        // 探针沿用白名单包名以便在目标车机安装；报告中的 UID 与包名按实测记录。
        applicationId = providers.gradleProperty("probeApplicationId").orElse("com.tencent.wecarflow").get()
        minSdk = 23
        targetSdk = 37
        versionCode = 4
        versionName = "0.4.0-audio-probe"
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
