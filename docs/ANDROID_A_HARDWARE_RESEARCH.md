# A 角色硬件选型与成本调研

调研日期：2026-10-11。范围：替换当前 Nexus 5 的无屏蓝牙透传节点，不替换 B 的 DiPlay。

## 1. 结论

用户已明确：**A 必须支持 5 GHz Wi-Fi，最终长度和宽度均不超过 20 mm。** 厚度、外壳和天线是否计入尺寸尚待确认。以下开发板仅作为功能验证载体，不作为满足尺寸的成品方案。

有两条主要路线：

1. **尽快验证专用无屏设备：Raspberry Pi 3 Model A+。** 双频 Wi-Fi 和经典蓝牙在同一板上，
   用 Linux 实现代理、自动启动、管理与升级；单套准备约 ¥220–320 的硬件预算。
2. **压低产品硬件成本：经典 ESP32 + ESP32-C5。** 经典 ESP32 处理蓝牙，
   C5 处理双频网络与管理；开发样机约 ¥120–200，小批整机预算约 ¥80–140；此预算针对原先较宽松尺寸的模组方案，不适用于尚未设计的 20×20 mm 定制成品。

人民币数字为工程预算估算，美元按 **1 USD ≈ ¥7** 做预算折算，不是实时汇率或中国到手报价。
工程预算包含基础供电、必要存储、简单外壳等，具体口径见第 4 节。
目前没有任何以下新硬件完成本项目的 iPhone RFCOMM/CarPlay 实测。

## 2. A 必须具备什么

- Bluetooth Classic / BR/EDR，能与 iPhone 配对、查询目标服务并连接 RFCOMM。
- 本项目目标 UUID 是 `00000000-deca-fade-deca-deafdecacafe`；不能用普通串口 SPP 示例
  连接成功来代替对这个服务的验证。
- 有可靠的双向字节流、流控、超时和关闭语义，能接入当前 CPBT 透传协议。
- Wi-Fi TCP、mDNS/DNS-SD、配网、配置存储、上电自动运行与断线恢复。
- 通过 B 管理状态、日志、版本与升级；不依赖设备本地屏幕。

A 不传 CarPlay 视频/音频、不负责 MFi、不需要视频解码器或认证芯片。
因此首先看无线接口、蓝牙软件栈和无屏管理条件，而非 CPU、GPU 或 RAM 的最大规格。

**A 必须能直接加入 5 GHz 网络。** 不以路由器跨频段互访代替这项要求。单经典 ESP32
和原生 Zero 2 W 只作为对照或蓝牙独立实验设备，不是满足要求的完整 A 候选。

## 3. 候选硬件

| 候选 | 蓝牙与网络条件 | 适用性与主要约束 |
|---|---|---|
| 经典 ESP32-WROOM-32E / WROVER 系列 | Classic + BLE，2.4 GHz Wi-Fi | 不满足 5 GHz；仅用于独立蓝牙实验。需验证目标 UUID 的 SDP/RFCOMM、无屏配对、Wi-Fi/BT 共存和内存余量 |
| 经典 ESP32 + C5 | ESP32 提供 Classic，C5 提供 2.4/5 GHz Wi-Fi | 双频低成本候选；增加片间通信、流控、双固件管理与兼容升级成本 |
| Raspberry Pi Zero 2 W | Bluetooth 4.2/BLE，2.4 GHz Wi-Fi | 不满足 5 GHz；原生板排除出完整 A 选型 |
| Raspberry Pi 3 Model A+ | Bluetooth 4.2/BLE，2.4/5 GHz Wi-Fi | 推荐 Linux 验证板；512 MB RAM，需 microSD，板体较大 |
| Radxa ZERO 3W | 当前官方宣传 Wi-Fi 6 / BT 5.4 | 更紧凑，部分配置有 eMMC；需按实际无线模块/板次核对 BR/EDR、5 GHz 及系统驱动，不只看版本号 |

经典 ESP32 支持 BR/EDR；ESP32-S3、C3、C5、C6 等不支持 Classic，不能单独替换现有
RFCOMM 节点。[Espressif 官方蓝牙能力表](https://docs.espressif.com/projects/esp-idf/en/latest/esp32c5/api-guides/bt-architecture/overview.html)

C5 的双频 Wi-Fi 与 BLE 规格见
[官方数据表](https://documentation.espressif.com/esp32-c5_datasheet_en.html)。
经典 ESP32 的 SPP API 提供按 RFCOMM server channel 建连能力，但默认 SPP 发现与
本项目 iAP2 UUID 的 SDP/频道解析仍需适配、实测，不能直接认定可用。
[ESP-IDF SPP API](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/api-reference/bluetooth/esp_spp.html)

树莓派无线规格分别见
[Zero 2 W 官方介绍](https://www.raspberrypi.com/news/new-raspberry-pi-zero-2-w-2/) 与
[3 Model A+ 官方规格](https://www.raspberrypi.com/products/raspberry-pi-3-model-a-plus/)。

Radxa 产品页列出 Wi-Fi 6 / BT 5.4，但其天线文档还同时提及 D8 / AP6212 模块。
采购必须要求卖方确认具体板次和无线模块；不能笼统保证所有标为 ZERO 3W 的板子具有
相同无线能力或驱动体验。
[官方产品页](https://radxa.com/products/zeros/zero3w/)、
[官方无线模块/天线文档](https://docs.radxa.com/en/zero/zero3/accessories/zero3w-antenna)

## 4. 价格依据与预算

### 4.1 可公开查到的单价

| 项目 | 本次查询价格 | 口径与来源 |
|---|---|---|
| ESP32-WROOM-32E-N8 | 1 个 $4.5848；100 个档 $3.414/个 | 是模块，不是完整开发板或整机；[LCSC](https://www.lcsc.com/product-detail/WIFI-Modules_Espressif-Systems-ESP32-WROOM-32E-8MB_C701342.html) |
| Waveshare ESP32-C5-Zero | $6.99–7.49 | 开发板目录报价，产品详情页本轮访问被拦截，须下单前复核；[厂商目录](https://www.waveshare.com/product/arduino/boards-kits/esp32-c5.htm?mode=list) |
| Raspberry Pi Zero 2 W | $17.25 | 当前代理商页面价；不是用历史 $15 发布价替代当前采购价；[PiShop](https://www.pishop.us/product/raspberry-pi-zero-2-w/) |
| Raspberry Pi 3 Model A+ | $25 | 板卡价格，不含存储/供电；[PiShop](https://www.pishop.us/product/raspberry-pi-3-model-a-plus-512mb-ram/) |
| Radxa ZERO 3W | 页面当前显示 $39.99 | 多内存/eMMC 配置选择页，不能认定所有配置同价；[Arace 产品页](https://arace.tech/products/radxa-zero-3w) |

Arace 分类/首页仍显示 ZERO 3W 的 $20 宣传价，但产品页显示 $39.99，存在不一致；
本稿采用产品页的较高值作为预算依据，不以 $20 宣传价承诺可购买。

### 4.2 单台原型和小批硬件预算（估算）

| 方案 | 可运行原型预算 | 约 100 台的小批整机硬件预算 | 成本判断 |
|---|---:|---:|---|
| 经典 ESP32 单芯片 | ¥60–120 | ¥45–80/台 | 不满足要求，仅用于蓝牙实验 |
| 经典 ESP32 + C5 | ¥120–200 | ¥80–140/台 | 两块开发板验证后，可整合一块 PCB |
| Pi Zero 2 W | ¥170–260 | 暂不报定制量产 BOM | 不满足要求；价格仅保留作对照 |
| Pi 3 Model A+ | ¥220–320 | 暂不报定制量产 BOM | 相比 Zero 多付约 ¥70，得到板上双频网络 |
| Radxa ZERO 3W | ¥330–450 | 暂不报定制量产 BOM | 基于 $39.99 页面价，存储配置需单独确认 |

这些范围不是供应商正式报价。MCU 原型按买开发板与基础配件估算；小批按模块、PCB、
贴装、5V 供电转换、连接器、简单外壳及基本生产测试估算。
不含开发人力、射频/整机认证、模具、税费、运费、售后与利润，也不含 MFi 认证相关费用。
Linux 板按购买成品板估算，不能将整板零售价当作自行设计 SoC 板的量产 BOM。

MCU 小批预算的粗略构成：

| 成本项 | 单 ESP32 | ESP32 + C5 |
|---|---:|---:|
| 无线模块 | 约 ¥24–30 | 约 ¥49–75；其中 C5 模块 ¥25–45 为待询价预算 |
| PCB、供电、连接器、基础保护 | 约 ¥10–20 | 约 ¥15–25 |
| 贴装、简单外壳、基本测试 | 约 ¥10–25 | 约 ¥15–35 |

C5 模块尚未取得足够明确的批量报价，双芯片预算置信度低于单芯片方案。
若需直接接汽车 12V，而非已有稳定 5V USB 供电，应另计宽压电源与实际使用环境保护设计，
当前数字不作为车规设备报价。

## 5. 开发工作与风险

### Linux 单板

需要重写 Android A 代理为 Linux 程序：蓝牙配对管理、SDP/RFCOMM、CPBT 透传、mDNS、
管理接口、上电服务与升级。可复用 B 的网络协议，不是把现有 APK 直接复制过去。
本稿判断其开发不确定性通常低于 MCU，原因是通用蓝牙/网络工具与系统服务较完整；
这不代表目标 iPhone 已在 Linux 上接受连接。
需考虑启动时间、存储掉电一致性、日志限额和升级恢复；512 MB 对仅运行代理而言是合理起点，
但最终资源占用应由实现测量。

### 经典 ESP32

需要用 ESP-IDF 实现目标 SDP/RFCOMM 与 CPBT，保存配对信息、无屏配网、mDNS、管理和 OTA。
优先选内存留有余量的经典型号做实验，避免把 TLS、OTA 和日志加入后才发现资源不足。
蓝牙与 2.4 GHz Wi-Fi 同时运行的稳定性、无屏配对和目标服务访问是主要实测门槛。

### ESP32 + C5

建议职责划分：经典 ESP32 只处理蓝牙；C5 处理网络、服务发现、管理与升级协调。
UART 加流控或 SPI 均可作为片间通道候选，需要定义分帧、错误恢复和缓冲，而不是直接连线
就视为透传完成。双芯片还需管理两个软件版本和兼容升级。
先证明单 ESP32 能建立目标蓝牙链路，再加 C5，避免同时排查蓝牙与片间协议。

## 6. 推荐采购与验证顺序

- 若以最快推进无屏管理为目标：先买一块 **Pi 3 Model A+**，准备约 ¥300 的验证预算。
- 若以最终售价与小体积为目标：采用 **经典 ESP32 + C5**，准备约 ¥120–200；
  可先独立验证经典 ESP32 的蓝牙链路，但完整 A 必须加入 C5 或其他 5 GHz 网络组件。
- A 的 5 GHz 要求已确认：单 ESP32 和原生 Zero 2 W 不满足网络条件，
  比较 Pi 3A+ 与双芯片方案，不先假定必须自研双芯片板。
- 现阶段不优先采购高 RAM Linux 板；A 的工作不包含视频解码。

每种新硬件的必要成功证据：目标蓝牙链路 → CPBT Wi-Fi 透传 → B 上真实 iAP2/MFi →
AirPlay 和画面 → 触控/音频 → 配网/自动启动/恢复/管理与升级。
先用现有 B 和 iPhone 做最小实验，不在首轮设计量产 PCB。

## 7. 本轮未解决项

- 尚未在任何候选硬件上实测本项目 iPhone 的自定义 RFCOMM 服务。
- 跨 2.4/5 GHz 路由器组播以及 MCU 无屏蓝牙配对仍待实测。
- C5 模块批量单价、国内到手价格、具体采购库存需实际询价。
- Radxa 模块/板次/驱动差异需卖方和实物确认。
- 本稿不将远程 MFi 盒子或认证芯片计入 A；那属于 B 的认证后端选型。

## 8. 20×20 mm 尺寸约束补充

官方模组本体长宽：

| 模组 | 长宽 | 与 20×20 mm 的关系 |
|---|---|---|
| ESP32-WROOM-32E | 25.5×18.0 mm | 单个模组已超限 |
| ESP32-WROOM-32UE（外置天线） | 19.2×18.0 mm | 本体可放入，但未包含载板、天线、供电和外壳 |
| ESP32-C5-WROOM-1 | 27.5×18.0 mm | 单个模组已超限 |
| ESP32-C5-WROOM-1U（外置天线） | 21.2×18.0 mm | 单个模组仍超限 |

尺寸来源：[ESP32 模组数据手册](https://www.espressif.com/sites/default/files/documentation/esp32-wroom-32e_esp32-wroom-32ue_datasheet_en.pdf)、[C5 模组数据手册](https://www.espressif.com/sites/default/files/documentation/esp32-c5-wroom-1_wroom-1u_datasheet_en.pdf)。

上述现成双 WROOM 模组方案不能满足要求；简单去掉排针或 USB 接口不能解决模组本体超限。
功能验证仍可使用开发板，但最终产品需重新评估更小封装/模组或芯片级定制 PCB。
双面贴装或叠板只能作为待评估手段，不能绕过现有 C5 模组本体超限。
芯片级方案还需安排存储、晶振、射频匹配、天线、电源和烧录触点；目前未做布局与射频实测，
不能承诺完整 20×20 mm 成品可行，也不能沿用前文模组原型成本作为成品报价。
