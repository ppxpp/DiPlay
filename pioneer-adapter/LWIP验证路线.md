# lwIP 有线 CarPlay 验证路线

用户授权持续开发、直接 TCP 推送更新，车机安装后自动检测并继续验证；手机保持连接和解锁，不要求反复确认方案。

实现：NCM Ethernet 帧直接进入 APP 内 lwIP 2.2.1 IPv6/TCP/UDP；单线程 raw API 执行网络输入、定时器、输出，避免多线程调用 lwIP。lwIP TCP 连接转发到原 DiPlay 的 127.0.0.1 服务，代理源绑定127.0.0.2，保持各媒体通道一致的手机身份。Android Java Socket 只处理本机回环，不建立 TUN、不需 VPN 授权。原 USB、Lockdown、iAP2/MFi、AirPlay 加密及媒体代码保留。

端口发现仅限本 APP UID 的监听与 UDP Socket，排除 TCP 调试端口与代理自身的 peer alias。动态 TCP 端口在收到 SYN 时补查，避免 SETUP 后立即建连的竞态。NTP 和麦克风的手机 UDP 端口用轻量挂钩注册。所有原加密字节保持原样，既有媒体请求/响应结构不改。

启用只针对 Pioneer USB 适配启用的设备，network_backend默认LWIP；可通过TCP network-backend mode=VPN恢复旧路径（下一次连接生效）。其他设备继续原方案。

首轮：完整 USB 接管 → 配对 → MFi → lwIP SYN-ACK → AirPlay 请求/认证 → SETUP/RECORD → 首帧、音频、触控。真实设备日志决定下一步，不把构建成功或发包提交视为完整链路成功。

lwIP官方源码tag：STABLE-2_2_1_RELEASE。下载归档SHA256：ce0b7461c0ad9602c376f0bf07c5eb7253b48c7bf66f011c6bf3e2a96731c539。原许可保留在vendor目录COPYING，协议源码不改，适配代码独立。

来源：https://www.nongnu.org/lwip/2_1_x/group__api.html
