plugins { id("com.android.library") }
android {
    namespace = "com.shilapi.xcertplay.pioneer"
    compileSdk = 37
    defaultConfig { minSdk = 23; consumerProguardFiles("consumer-rules.pro") }
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.10.2" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    compileOnly(project(":pioneer-ceam-stubs"))
}
