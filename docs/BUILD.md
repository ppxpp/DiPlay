# Build DiPlay

Use this procedure to build the DiPlay app for an Android head unit.
Run the commands from the repository root, where `gradlew` and `settings.gradle.kts` are located.

## Select the application

This repository contains several Android applications.
Select `mobile` to build the main DiPlay app.

| Gradle module | Purpose | Debug APK |
| --- | --- | --- |
| `:mobile` | Main DiPlay app for Android head units | `mobile/build/outputs/apk/debug/mobile-debug.apk` |
| `:bt-proxy-android` | Android A Bluetooth proxy PoC ([module guide](../bt-proxy-android/README.md)) | `bt-proxy-android/build/outputs/apk/debug/bt-proxy-android-debug.apk` |
| `:maphost` | Sample application that displays a map from DiPlay | `samples/maphost/build/outputs/apk/debug/maphost-debug.apk` |
| `:home` | Optional DiPlay Home launcher | `samples/home/build/outputs/apk/debug/home-debug.apk` |
| `:automotive` | Separate Android Automotive application inherited from xcertplay | `automotive/build/outputs/apk/debug/automotive-debug.apk` |

The `:common` and `:shared` modules are libraries.
The applications use these libraries.

If the installed app is **DiPlay map host**, you selected `:maphost` or its APK.
Build `:mobile` and select `mobile-debug.apk` to install the main app.
A command such as `./gradlew assembleDebug` can build more than one application.
Use the module name in each build command to select the required application.

## Prepare the build environment

Install these tools:

- JDK 25.
- Android SDK Platform 37 (`platforms;android-37.0`).
- Android SDK Build-Tools 36.0.0.
- Android SDK Platform-Tools.
- Android NDK 28.2.13676358.

Use the Gradle wrapper included in this repository.
The first build needs an internet connection to download dependencies.

1. Open the repository root in Android Studio.
2. Install the Android SDK packages with **Tools → SDK Manager**.
3. Set **Gradle JDK** to JDK 25 in the Android Studio Gradle settings.
4. Wait for Gradle sync to finish.

For a terminal build, set `JAVA_HOME` to your JDK 25 directory.
Set `ANDROID_HOME` to your Android SDK directory.
You can also specify the SDK directory in the root `local.properties` file:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

Keep `local.properties` outside Git.
Check the Java version before the build:

```sh
java -version
```

The output must identify Java 25.
On Windows, use `gradlew.bat` in place of `./gradlew` in the commands below.

## Build the main app

The mobile app bundles the locally provisioned accessory identity by default.
Build the debug APK:

```sh
./gradlew :mobile:assembleDebug
```

Check that this file exists:

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
```

The debug application ID is `com.shihab.diplay.hudtest`.
The release application ID is `com.shihab.diplay`.

Both debug and release builds load `identity.pk8` and `certificate.p7b` from
`.private/runtime-assets/offline-mfi/` by default. This Git-ignored project-local
copy was extracted from `DiPlay-0.2.17.apk`. No environment variable is needed
for subsequent local builds. Missing or empty files stop the build; fresh
checkouts and CI must provision the files before building `:mobile`.
`DIPLAY_AUTH_ASSETS_DIR` can select a different runtime asset directory.
Tests generate synthetic identities at runtime.
Keep test private keys outside Git.

### Use Android Studio

1. Open **Run → Edit Configurations**.
2. Select an **Android App** configuration, or create one.
3. Set **Module** to `mobile`.
4. Select this configuration in the toolbar.
5. Select the Android device.
6. Click **Run** to install the main debug app.

To build an APK without installation, run `:mobile:assembleDebug` in the Android Studio terminal.
Select the APK from the `mobile` output directory.

## Check the source build

Run the unit tests, lint checks, and main debug build:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

Check that Gradle reports `BUILD SUCCESSFUL`.
For the complete CI procedure, see [the Android workflow](../.github/workflows/android.yml).
That workflow also checks and builds the `home` and `maphost` applications.

## Build a standalone car-test APK

The default project-local directory contains these runtime authentication files:

```text
.private/runtime-assets/
└── offline-mfi/
    ├── identity.pk8
    └── certificate.p7b
```

Use only the intended runtime files in this directory.
Keep the directory and its private key outside Git.
The build rejects unexpected credential containers in APK assets.

An optional `.private/runtime-assets/network/hotspot.json` also bundles the local
test hotspot defaults (`ssid`, `passphrase`, `band`, `channel`, `security`). On
first launch it fills an unconfigured manual hotspot and selects wireless manual
hotspot mode. Existing settings and subsequent edits are preserved. This profile
is included in both debug and release mobile APKs and remains outside Git;
it does not turn on Android's system hotspot automatically.

Run the standalone debug task (ordinary `:mobile:assembleDebug` also bundles the identity):

```sh
./gradlew :mobile:assembleStandaloneDebug
```

To override the default, set `DIPLAY_AUTH_ASSETS_DIR` to a different asset directory
containing the same `offline-mfi/` structure.

The task stops if either required file is absent or empty.
Its APK output is `mobile/build/outputs/apk/debug/mobile-debug.apk`.
Check both files in the APK against your selected local inputs:

- `assets/offline-mfi/identity.pk8`.
- `assets/offline-mfi/certificate.p7b`.

Use this APK for a standalone connection test.
Update the existing test app with the same signing key to keep its settings.
Different signing keys cannot update the same installed application.

## Sign every APK with one identity

Every application module uses one keystore for debug and release, and the WeCarFlow repack in
`wecarflow/` reads the same file, so the head unit sees a single signature across DiPlay,
`usbcheck`, `bluetooth-probe` and WeCarFlow.

Provision it once per machine:

```sh
./scripts/provision-signing.sh
```

The script creates the keystore and `signing.properties` in a `.signing` directory above this
repository, links `DiPlay/.signing` and `DiPlay/wecarflow/.signing` to it, and prints the certificate SHA-256
along with the values to enter as GitHub Actions secrets. The keystore, the passwords and the
`.signing/` directory stay outside Git, and `scripts/check_public_tree.py` rejects them if they are
ever added to the tree. The script is idempotent and never overwrites an existing key.

Each value resolves in this order:

1. `.signing/signing.properties` keys `storeFile`, `storePassword`, `keyAlias`, `keyPassword`
2. `-Psigning.storeFile=…` and the other `-Psigning.*` Gradle properties
3. `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
4. `storeFile` additionally falls back to `.signing/repack.keystore`

While the identity is incomplete, `checkSharedSigning` fails before any APK is produced.
Unit tests and lint do not depend on it, so a checkout without signing material can still be tested.
Re-signing with a different key means the installed apps cannot be updated in place: uninstall them
first and accept that their data goes with them.

GitHub Actions reads the same four values from the `SIGNING_KEYSTORE_BASE64`,
`SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` repository secrets and
materialises `.signing/` before Gradle runs, so CI signs with the same certificate as local builds.

## Build a release APK

Prepare the runtime assets as described in the car-test procedure, and provision the signing identity
described above.

| Variable | Value |
| --- | --- |
| `DIPLAY_AUTH_ASSETS_DIR` | Optional override; defaults to `.private/runtime-assets` in the project root |

Keep the keystore and passwords outside Git.
Run the release checks and build:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

The output is `mobile/build/outputs/apk/release/mobile-release.apk`.
The APK contains the runtime identity you supplied.
Recipients can extract that identity from the APK.
The APK excludes the Android signing key.
See [the project notices](THIRD_PARTY_NOTICES.md) for the experimental identity used in public releases.

The public source archive matches the release tag.
It excludes runtime identities, signing keys, local configuration, and build output.
The retired `build-beta.py` helper is no longer part of the build procedure.

## Correct build problems

| Symptom | Action |
| --- | --- |
| Installed app is DiPlay map host | Select `mobile` in Android Studio. Build and install the APK from the `mobile` output directory. |
| Gradle cannot find the Android SDK | Set `ANDROID_HOME` or the `sdk.dir` value in `local.properties`. |
| A required SDK or NDK package is absent | Install the listed package with SDK Manager. |
| Java or Gradle JDK version is incorrect | Set the terminal JDK and Android Studio Gradle JDK to Java 25. |
| Authentication asset check fails | Restore the two files under `.private/runtime-assets/offline-mfi/`, or set `DIPLAY_AUTH_ASSETS_DIR`. |
| Standalone task reports missing authentication files | Check the external directory path and both required files. |
| `checkSharedSigning` fails | Run `./scripts/provision-signing.sh`, or set the `ANDROID_KEYSTORE_*` variables. |
| Android rejects an app update | Use the same application ID and signing key as the installed app. |

### Source validation without runtime authentication

Use `./gradlew -PdiplaySourceOnly=true :mobile:assembleDebug` for CI or a clean checkout
without private CarPlay authentication. This mode excludes the local runtime asset directory
and still rejects credential files added to other APK asset directories. Such APKs cannot
independently authenticate CarPlay. Ordinary local/release builds keep requiring both files;
`assembleStandaloneDebug` also explicitly rejects source-only mode.
