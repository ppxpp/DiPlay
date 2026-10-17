# 從原始碼建置 DiPlay

[繁體中文首頁](../README.zh-TW.md) · [英文建置指南](BUILD.md) · [Android CI](../.github/workflows/android.yml)

以下指令從專案根目錄執行，也就是包含 `gradlew` 與 `settings.gradle.kts` 的目錄。環境版本以本分支的建置設定及 CI 為準。

## 選擇應用程式

| Gradle 模組 | 用途 | Debug APK |
| --- | --- | --- |
| `:mobile` | 車機使用的主要 DiPlay 應用程式 | `mobile/build/outputs/apk/debug/mobile-debug.apk` |
| `:home` | 選用的 DiPlay Home 啟動器 | `samples/home/build/outputs/apk/debug/home-debug.apk` |
| `:maphost` | 接收 DiPlay 地圖的範例應用程式 | `samples/maphost/build/outputs/apk/debug/maphost-debug.apk` |
| `:automotive` | 從 xcertplay 繼承的獨立 Android Automotive 應用程式 | `automotive/build/outputs/apk/debug/automotive-debug.apk` |

`:common` 與 `:shared` 是共用函式庫。要安裝主程式，請選 **mobile**；如果裝到「DiPlay map host」，表示選錯模組或 APK。單獨執行 `assembleDebug` 可能建置多個應用程式。

## 準備環境

- JDK 25。
- Android SDK Platform 37（`platforms;android-37.0`）。
- Android SDK Build-Tools 36.0.0。
- Android SDK Platform-Tools。
- Android NDK 28.2.13676358。

使用專案附帶的 Gradle wrapper，首次建置需要網路下載相依套件。在 Android Studio 開啟根目錄，以 **Tools → SDK Manager** 安裝上述 SDK／NDK，將 **Gradle JDK** 設為 JDK 25，再等待同步。

### Windows PowerShell

將範例路徑換成實際安裝目錄；`JAVA_HOME` 指向 JDK 根目錄，不是 `bin`。

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
$env:ANDROID_HOME = 'C:\Users\YOUR_NAME\AppData\Local\Android\Sdk'
& "$env:JAVA_HOME\bin\java.exe" -version
.\gradlew.bat :mobile:assembleDebug
```

也可在根目錄建立不提交到 Git 的 `local.properties`：

```properties
sdk.dir=C:/Users/YOUR_NAME/AppData/Local/Android/Sdk
```

### Linux／macOS 終端機

設定 `JAVA_HOME` 為 JDK 25、`ANDROID_HOME` 為 SDK 目錄，確認 `java -version` 顯示 Java 25，再執行：

```sh
./gradlew :mobile:assembleDebug
```

一般原始碼 APK 不包含配件認證身分，除非自行提供執行時認證素材；因此它可用於介面驗證，但獨立 CarPlay 連線需要下節素材。Debug 套件名稱為 `com.shihab.diplay.hudtest`，正式版為 `com.shihab.diplay`。

## Android Studio 執行

在 **Run → Edit Configurations** 選擇或建立 Android App 設定，將 Module 設為 **mobile**，選取 Android 裝置後執行。只要 APK 而不安裝時，在終端機執行 `:mobile:assembleDebug`，輸出見上表。

## 完整檢查

依 `AGENTS.md` 與 Android workflow 執行所有單元測試、lint 與 debug 建置。Windows：

```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest :mobile:lintDebug :home:lintDebug :maphost:lintDebug :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug --stacktrace
```

Linux／macOS 將 `.\gradlew.bat` 換成 `./gradlew`。確認結果為 `BUILD SUCCESSFUL`。CI 另執行 `python3 scripts/check_public_tree.py`，避免認證容器或私密金鑰進入公開原始碼。

USB 權限測試需要 POSIX shell。Linux／macOS 使用 `sh`；Windows 會自動使用標準安裝位置的 Git for Windows `bin/sh.exe`。若安裝在其他位置，可在執行測試前指定：

```powershell
$env:DIPLAY_TEST_SHELL = 'C:\path\to\Git\bin\sh.exe'
```

測試透過標準輸入傳送腳本，避免 Windows 命令列改寫引號。檔案替換及 FileProvider 的測試相容層會保留 Android 的行為，包含拒絕設定範圍外的檔案。

## 獨立實車測試 APK

在 Git 之外準備只包含指定執行時素材的目錄：

```text
runtime-assets/
└── offline-mfi/
    ├── identity.pk8
    └── certificate.p7b
```

不要將素材或私密金鑰提交到 Git。測試會在執行時產生合成身分，不需要把測試私密金鑰放入原始碼。

Windows PowerShell：

```powershell
$env:DIPLAY_AUTH_ASSETS_DIR = 'C:\path\to\runtime-assets'
.\gradlew.bat :mobile:assembleStandaloneDebug
```

Linux／macOS：

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

任一必要檔案缺少或為空時，任務會停止；建置也會拒絕非預期的憑證容器。輸出為 `mobile/build/outputs/apk/debug/mobile-debug.apk`。請確認 APK 中的 `assets/offline-mfi/identity.pk8` 與 `assets/offline-mfi/certificate.p7b` 符合選定素材。使用相同簽章更新既有測試程式，才能保留設定。

## 統一簽章身分

所有 application 模組的 debug 與 release 都用同一個 keystore 簽章，上層 `../wecarflow` 的改包也讀同一份檔案，因此車機看到的 DiPlay、`usbcheck`、`bluetooth-probe` 與 WeCarFlow 是同一個簽章。

每台機器先佈建一次：

```sh
./scripts/provision-signing.sh
```

腳本會在本倉庫上層的 `.signing` 目錄建立 keystore 與 `signing.properties`，再把 `DiPlay/.signing` 和 `wecarflow/.signing` 指過去，最後列出憑證 SHA-256 以及要填進 GitHub Actions secrets 的值。keystore、密碼與 `.signing/` 目錄一律留在 Git 之外，`scripts/check_public_tree.py` 會拒絕它們進入原始碼。腳本可重複執行，絕不會覆蓋既有金鑰。

每個設定值依下列順序解析：

1. `.signing/signing.properties` 的 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`
2. `-Psigning.storeFile=…` 及其他 `-Psigning.*` Gradle 属性
3. `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`
4. `storeFile` 另可回退到 `.signing/repack.keystore`

簽章身分不完整時，`checkSharedSigning` 會在產生 APK 之前失敗；單元測試與 lint 不受影響，沒有簽章素材的 checkout 仍可以正常執行測試。換用其他 keystore 後，已安裝的應用無法原地更新，必須先解除安裝，其資料一併消失。

GitHub Actions 從倉庫 secrets `SIGNING_KEYSTORE_BASE64`、`SIGNING_KEYSTORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD` 取得同樣四個值，在 Gradle 執行前寫出 `.signing/`，因此 CI 與本機使用相同憑證。

## 正式版建置

除認證素材外，先依上節佈建簽章身分。

| 變數 | 內容 |
| --- | --- |
| `DIPLAY_AUTH_ASSETS_DIR` | 認證素材目錄的絕對路徑 |

將 keystore 與密碼留在 Git 之外。Windows 執行：

```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

Linux／macOS 使用 `./gradlew`。輸出為 `mobile/build/outputs/apk/release/mobile-release.apk`，包含你提供的配件身分，接收者可從 APK 擷取。APK 不含 Android 簽章私密金鑰。公開版本採用的實驗性身分及授權見[第三方聲明](THIRD_PARTY_NOTICES.md)。

公開原始碼壓縮檔應對應 release tag，排除認證身分、簽章金鑰、本機設定及建置輸出。不要使用已退役的 `build-beta.py` 作為建置流程。

## 建置問題

| 問題 | 處理方式 |
| --- | --- |
| 找不到 SDK | 設定 `ANDROID_HOME` 或 `local.properties` 的 `sdk.dir`。 |
| 缺少 SDK／NDK | 在 SDK Manager 安裝上列指定版本。 |
| Java 版本不符 | 將終端機與 Android Studio 的 Gradle JDK 設為 JDK 25。 |
| APK 無法獨立啟動 CarPlay | 提供外部認證素材並使用 `assembleStandaloneDebug`。 |
| Standalone 任務找不到認證檔案 | 檢查外部目錄的絕對路徑及兩個必要檔案。 |
| `checkSharedSigning` 失敗 | 執行 `./scripts/provision-signing.sh`，或設定 `ANDROID_KEYSTORE_*` 環境變數。 |
| Android 拒絕覆蓋安裝 | 確認應用程式套件名稱與簽章相同。 |

### 不含執行期認證的原始碼驗證

CI 或沒有私有認證素材的乾淨環境，使用
`./gradlew -PdiplaySourceOnly=true :mobile:assembleDebug`。
此模式不打包本機私有認證目錄，仍會拒絕其他 assets 中的憑證；產物無法獨立進行 CarPlay 認證。
一般本機與發布建置仍要求兩個認證檔案，`assembleStandaloneDebug` 不接受此模式。
