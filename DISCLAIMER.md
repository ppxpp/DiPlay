# Disclaimer / 免责声明

> This project is provided **as-is** for learning, research, and personal experimentation on hardware you own. By using it you accept this entire disclaimer.
>
> 本项目按"**现状**"提供,仅供在**你自己拥有的设备**上进行学习、研究和个人实验。使用本项目即表示你接受本免责声明的全部内容。

DiPlay is distributed under the **GNU GPL-3.0** (`LICENSE`), which already disclaims warranty and limits liability. This document covers project-specific risks that the license text does **not** address. The two are complementary; neither grants any right to third-party intellectual property.

DiPlay 在 **GNU GPL-3.0**(`LICENSE`)下分发,该许可证已包含"无担保"与"责任限制"条款。本文件补充许可证文本**未涵盖**的项目特定风险,二者互为补充;任何一份都**不**授予任何第三方知识产权。

---

## 1. Apple, CarPlay & MFi / Apple、CarPlay 与 MFi

**EN.** DiPlay is **not affiliated with, endorsed by, sponsored by, or authorized by Apple Inc.** "CarPlay", "iPhone", "iPad" and the CarPlay icon are trademarks or registered trademarks of **Apple Inc.**, used here for descriptive purposes only.

Legally provisioning a CarPlay head unit generally requires participation in Apple's **MFi (Made for iPhone) program** under a signed license. DiPlay **does not replace MFi certification and grants no Apple license**. Its implementation may implicate Apple proprietary protocols, patents, or copyrighted interfaces, and the accessory identity it can bundle is experimental and not newly provisioned for DiPlay (see `README.md` and `SECURITY.md`).

**Users are solely responsible** for complying with Apple's licensing terms and all applicable laws. The authors make no representation or warranty about third-party intellectual property and accept no liability for any infringement, certification, or compliance consequence of your use.

**中文.** DiPlay 与 **Apple Inc. 无任何关联**,未获 Apple 授权、赞助或认可。"CarPlay"、"iPhone"、"iPad" 及 CarPlay 图标均为 **Apple Inc.** 的商标或注册商标,此处仅作描述性用途。

合法为车机配置 CarPlay 通常需加入 Apple **MFi(Made for iPhone)计划**并签署授权协议。DiPlay **不能替代 MFi 认证,也不授予任何 Apple 许可**。其实现可能涉及 Apple 私有协议、专利或受版权保护的接口;它可能捆绑的配件身份是实验性的、并非为 DiPlay 新配置(见 `README.md` 与 `SECURITY.md`)。

**使用者须自行遵守** Apple 相关许可条款及所有适用法律法规。作者不对任何第三方知识产权作出陈述或担保,亦不对你使用所导致的侵权、认证或合规后果承担责任。

## 2. Vehicle & driving safety / 车辆与驾驶安全

**EN.** Running software on a vehicle head unit can affect driving safety and vehicle behavior. **The driver assumes full responsibility for safe operation** and must obey all traffic laws, including limits on in-motion screens and distracted driving. No compatibility with any vehicle model, head unit, firmware, or iOS version is guaranteed; use may cause malfunction or damage. Modification **may void the manufacturer's warranty**, and such losses are borne by the user. Install and use under qualified guidance, and do not rely on DiPlay for any safety-critical function.

**中文.** 在车机上运行软件可能影响行车安全与车辆行为。**驾驶员对安全驾驶负全部责任**,须遵守所有交通法规(包括行驶中对屏幕与分心驾驶的限制)。不保证与任何车型、车机、固件或 iOS 版本兼容,使用可能造成异常或损坏。改装**可能导致原厂保修失效**,相关损失由使用者承担。请在具备资质的指导下安装使用,且**不要**将 DiPlay 用于任何安全关键功能。

## 3. Regulatory compliance / 法规合规

**EN.** Wireless and electronic features (e.g., Wi-Fi, Wi-Fi Direct, Bluetooth) typically require certification or type approval in the target jurisdiction (for example SRRC, FCC, CE, TELEC). You are responsible for ensuring your build, distribution, and deployment comply with local law. The authors make no warranty of compliance.

**中文.** 无线电与电子功能(如 Wi-Fi、Wi-Fi Direct、蓝牙)通常在目标地区需要认证或型号核准(例如 SRRC、FCC、CE、TELEC)。你须自行确保构建、分发与部署符合当地法律。作者不对合规性作出任何担保。

## 4. Data & privacy / 数据与隐私

**EN.** CarPlay processes personal data such as contacts, messages, location, and audio. The authors do not collect, control, operate, or store any user data, and there is no DiPlay authentication server. How data is handled depends on your device, network, and the apps in use — see `docs/PRIVACY.md`. If you distribute DiPlay or deploy it for others, you assume full responsibility for privacy and data-protection compliance (for example GDPR and China's PIPL).

**中文.** CarPlay 会处理通讯录、短信、位置、音频等个人数据。作者不收集、不控制、不运营、不存储任何用户数据,也不存在 DiPlay 认证服务器。数据如何处理取决于你的设备、网络与所用应用——见 `docs/PRIVACY.md`。若你分发 DiPlay 或为他人部署,需自行承担隐私与数据保护合规责任(例如 GDPR、《中华人民共和国个人信息保护法》)。

## 5. No support / 不提供支持

**EN.** DiPlay is experimental and provided without any warranty of accuracy, reliability, fitness for a purpose, uninterrupted or error-free operation, or security. No technical support is promised. Releases may stop being maintained, and features may break after iOS or firmware updates.

**中文.** DiPlay 属于实验性软件,不担保准确性、可靠性、特定用途适用性、不中断或无错误运行,也不担保安全性。不承诺任何技术支持。版本可能停止维护,iOS 或固件更新后功能可能失效。

## 6. Contributions & third parties / 贡献与第三方

**EN.** Contributions are, by default, provided under this project's GPL-3.0 license; accepting a contribution implies no support or warranty. DiPlay depends on third-party components (see `docs/THIRD_PARTY_NOTICES.md` and `docs/licenses`), each governed by its own license. Preserve all notices when distributing modifications.

**中文.** 对项目的贡献默认在 GPL-3.0 许可证下提供;接受贡献不隐含任何支持或担保。DiPlay 依赖第三方组件(见 `docs/THIRD_PARTY_NOTICES.md` 与 `docs/licenses`),各组件受其自身许可证约束。分发修改版时请保留所有声明。

## 7. Changes / 变更

**EN.** This disclaimer may be updated at any time; continued use constitutes acceptance of the current version. It does not replace the `LICENSE`; in any conflict over software rights and warranties, the GPL-3.0 `LICENSE` governs.

**中文.** 本免责声明可能随时更新,继续使用即视为接受最新版本。它不替代 `LICENSE`;在软件权利与担保发生冲突时,以 GPL-3.0 `LICENSE` 为准。

---

*Report compliance or security concerns through the channels in `SECURITY.md`. Do not include credentials or pairing records in public issues.*

*请通过 `SECURITY.md` 中的渠道反馈合规或安全问题。公开 issue 中请勿包含凭据或配对记录。*
