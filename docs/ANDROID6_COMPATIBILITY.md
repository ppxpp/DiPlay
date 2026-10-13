# Android 6.0 兼容性

更新日期：2026-10-12。用户要求按 Android 6.0（API 23）实施；适用对象为 Android B 的 mobile DiPlay APK。Android A 代理原已支持 API 23。

## 实现

- mobile、common、shared 的最低版本降至 API 23；NDK 的 APP_PLATFORM 同步降至 android-23，保留四种 ABI。targetSdk 保持 37。
- WorkManager 使用支持 API 23 的 2.11.2，未强行覆盖 2.12 的最低版本限制。
- 语言读取与界面缩放支持单 Locale 的旧系统；按钮圆角不再读取 API 24 的 getter。
- 启用 Java 类库 desugaring；CompletableFuture 使用 android-retrofuture 1.7.4 回移实现，并同步生产端和相关测试端类型。共用库的应用入口同步启用 desugaring；automotive 和 home 的最低版本未降低。
- 音频欠载计数仅在 API 24+ 读取，API 23 保留播放头/队列跟踪；麦克风采样时间使用原有估算回退。NTP 的向下取整不依赖新 Java Math 接口。
- TLS 参数与下载大小读取适配 API 23；蓝牙、自动发现、TCP、iAP2/MFi、媒体和触控路径继续使用现有实现。
- Telecom 自管理通话服务在 API 26+ 启用，地图跨应用嵌入服务在 API 30+ 启用，不将较新系统的可选功能视为 Android 6.0 可用。
- 新系统热点接口保留版本门槛与反射路径，旧系统继续用现有热点检测；Android 6 没有系统 LocalOnlyHotspot，需要 Existing Wi-Fi、手动热点或待实测的旧 Wi-Fi Direct 路径。

## 已验证及边界

- mobile Debug APK 构建成功；aapt 确认 sdkVersion 为 23。
- 对 mobile、common、shared 执行仅检查 NewApi 的 lint（包含依赖模块），通过，无 API 版本错误。
- Robolectric API 23：启动 DiPlayActivity、打开全部设置分类、中文语言与缩放，以及阿拉伯语/RTL 缩放，共 2 项通过。
- 现有 AmbientLampSession 异步生命周期测试 9 项通过。
- 没有连接 Android 真机；尚未实测 Android 6.0 下蓝牙/自动发现、iAP2/MFi、AirPlay 视频、音频、触控和麦克风。不把页面测试与构建结果当作完整 CarPlay 已验证。
- 未运行全量回归；此前 API 23 的 Nexus 5 验证的是 A 代理，不代表 B 的 DiPlay 链路已经验证。

构建：`ANDROID_HOME=/Users/zhouhao/Library/Android/sdk ./gradlew --configure-on-demand :mobile:assembleDebug`。
APK：`mobile/build/outputs/apk/debug/mobile-debug.apk`（包名 `com.shihab.diplay.hudtest`）。
