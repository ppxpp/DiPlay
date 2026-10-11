import org.gradle.api.file.FileSystemOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
}

// Reuse protocol sources without depending on DiPlay's native/audio/authentication stack.
abstract class PrepareIap2ProbeSources : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileOperations: FileSystemOperations

    @TaskAction
    fun prepare() {
        fileOperations.sync {
            from(sources)
            into(outputDirectory)
        }
    }
}

val prepareIap2ProbeSources by tasks.registering(PrepareIap2ProbeSources::class) {
    sources.from(rootProject.fileTree("shared/src/main/java") {
        include("com/shilapi/xcertplay/transport/Iap2LinkEngine.kt")
        include("com/shilapi/xcertplay/transport/RemoteRfcommProtocol.kt")
        include("com/shilapi/xcertplay/transport/BlockingDuplexByteStream.kt")
        include("com/shilapi/xcertplay/transport/BluetoothRfcommDuplexStream.kt")
        include("com/shilapi/xcertplay/iap2/wire/Iap2CsmFramer.kt")
        include("com/shilapi/xcertplay/iap2/wire/Iap2Wire.kt")
        include("com/shilapi/xcertplay/iap2/wire/Iap2WireCodec.kt")
    })
    outputDirectory.set(layout.buildDirectory.dir("generated/iap2Probe"))
}

android {
    namespace = "com.shihab.diplay.btproxy"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shihab.diplay.btproxy"
        minSdk = 23
        targetSdk = 37
        versionCode = 5
        versionName = "0.5.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

androidComponents.onVariants { variant ->
    variant.sources.kotlin!!.addGeneratedSourceDirectory(prepareIap2ProbeSources) { it.outputDirectory }
}
