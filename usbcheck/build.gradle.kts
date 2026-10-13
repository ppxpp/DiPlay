plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// USB 链路诊断 App：只验证"识别 iPhone → 切换 CarPlay 配置 → 强制占用接口"这段
// VPN 之前的路径，不启动 VpnService。包名与 DiPlay 一致，以复用系统已授予的 USB 权限。
android {
    namespace = "com.shilapi.xcertplay.usbcheck"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
//        applicationId = "com.shihab.diplay"
        applicationId = "com.gtmc.app.fengyunyx.b819"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-usbcheck"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
}
