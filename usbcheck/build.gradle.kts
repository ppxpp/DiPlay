plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 独立 USB/MFi/NCM 诊断包，另提供最小 VPN TUN 验证，不启动完整 CarPlay 服务。
val authAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .getOrElse(rootProject.file(".private/runtime-assets").absolutePath)
val stagedAuth = layout.buildDirectory.dir("generated/mfi-assets")
val stageMfiAssets = tasks.register<Copy>("stageMfiAssets") {
    val directory = file(authAssets).resolve("offline-mfi")
    val requiredFiles = listOf("identity.pk8", "certificate.p7b").map(directory::resolve)
    from(directory) {
        include("identity.pk8", "certificate.p7b")
        into("offline-mfi")
    }
    into(stagedAuth)
    doFirst {
        check(requiredFiles.all { f -> f.isFile && f.length() > 0 }) { "Provision existing offline MFi identity assets first" }
    }
}
tasks.named("preBuild") { dependsOn(stageMfiAssets) }

android {
    sourceSets.getByName("main").assets.srcDir(stagedAuth.get().asFile)
    namespace = "com.shilapi.xcertplay.usbcheck"
    compileSdk {
        version = release(37)
    }

    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.10.2"
        }
    }

    defaultConfig {
        applicationId = "com.tencent.wecarflow"
        minSdk = 23
        targetSdk = 37
        versionCode = 13
        versionName = "0.3.4-usbcheck"
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
