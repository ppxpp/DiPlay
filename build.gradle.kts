// Top-level build file where you can add configuration options common to all sub-projects/modules.
import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// One signing identity serves every application module, debug and release alike, and the WeCarFlow
// repack. Provision the material with scripts/provision-signing.sh; it stays outside Git.
fun readSigningProperties(text: String): Map<String, String> {
    val properties = Properties().apply { text.reader().use { load(it) } }
    return properties.stringPropertyNames().associateWith { properties.getProperty(it) }
}

val sharedSigning: Provider<Map<String, String>> =
    providers.fileContents(layout.projectDirectory.file(".signing/signing.properties")).asText
        .map { text -> readSigningProperties(text) }
        .orElse(emptyMap<String, String>())

fun signingValue(key: String, environmentVariable: String, default: String? = null): String? =
    sharedSigning.get()[key]?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty("signing.$key").orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(environmentVariable).orNull?.takeIf { it.isNotBlank() }
        ?: default

val signingKeystorePath = signingValue("storeFile", "ANDROID_KEYSTORE_PATH", ".signing/repack.keystore")!!
val signingKeystore = rootProject.file(signingKeystorePath)
val signingStorePassword = signingValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
val signingKeyAlias = signingValue("keyAlias", "ANDROID_KEY_ALIAS")
val signingKeyPassword = signingValue("keyPassword", "ANDROID_KEY_PASSWORD")

val signingValues = mapOf(
    "storePassword" to signingStorePassword,
    "keyAlias" to signingKeyAlias,
    "keyPassword" to signingKeyPassword,
)
val signingConfigured = signingValues.values.all { !it.isNullOrBlank() }

if (!signingConfigured) {
    logger.warn(
        "Shared signing identity is incomplete. APK assembly will fail; run scripts/provision-signing.sh or set " +
            "ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD.",
    )
}

subprojects {
    plugins.withId("com.android.application") {
        val application = extensions.getByType(ApplicationExtension::class.java)
        if (signingConfigured) {
            val shared = application.signingConfigs.create("shared")
            shared.storeFile = signingKeystore
            shared.storePassword = signingStorePassword
            shared.keyAlias = signingKeyAlias
            shared.keyPassword = signingKeyPassword
            application.buildTypes.named("debug") { signingConfig = shared }
            application.buildTypes.named("release") { signingConfig = shared }
        }
        // The task action is serialized by the configuration cache, so it may only capture locals
        // declared inside this block, not values from the enclosing script.
        val keystore = signingKeystore
        val missingValues = signingValues.filterValues { it.isNullOrBlank() }.keys
        val checkSharedSigning by tasks.registering {
            group = "verification"
            description = "Require the workspace signing identity before producing an APK."
            doLast {
                check(keystore.isFile) {
                    "Cannot sign this APK: signing keystore not found at ${keystore.absolutePath}. " +
                        "Run scripts/provision-signing.sh, or set ANDROID_KEYSTORE_PATH."
                }
                check(missingValues.isEmpty()) {
                    "Cannot sign this APK: " + missingValues.joinToString(", ") { key -> "signing $key is unset" } +
                        ". Provide them in .signing/signing.properties or as ANDROID_KEYSTORE_* environment variables."
                }
            }
        }
        tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(checkSharedSigning) }
    }
}
