plugins { id("com.android.library") }
android {
    namespace = "com.shilapi.xcertplay.pioneer"
    compileSdk = 37
    defaultConfig { minSdk = 23; consumerProguardFiles("consumer-rules.pro") }
    testOptions { unitTests.isIncludeAndroidResources = true }
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.10.2" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    compileOnly(project(":pioneer-ceam-stubs"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
