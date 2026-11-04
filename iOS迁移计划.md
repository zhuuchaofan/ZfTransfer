# iOS 迁移计划

2026-11-01 起草。这是评估和分阶段计划，不是承诺；没有实机验证过的内容一律标注“未验证”。

## 目标与前提

目标：做一个 iOS 版 Zf 传图，功能先对齐 Android 的核心流程：连接相机、浏览照片、预览、导入 JPEG 到相册。

已确认的前提（用户 2026-11-01 回答）：

- 有 Mac，可以安装 Xcode；有 iPhone（用户 2026-11-01 补充确认）。付费苹果开发者账号还没有确认，见“未决问题”。
- 发布方式：自己用和少数朋友，不上 App Store。因此可以继续使用 GPL-3.0，协议层可以直接参照现有 Kotlin 实现移植（该实现来自 Nikon Pull，GPL-3.0，见 ZfTransfer/THIRD_PARTY.md）。如果将来要上 App Store，GPL-3.0 代码不能放进去，协议层要依据 MIT 许可的 ZPill 另行重写。
- 开发机是 Windows，不能编译和运行 iOS 应用。凡是需要 Xcode 的步骤都要在用户的 Mac 上做。

## 现有代码哪些能搬

主代码约 4800 行 Kotlin（协议层约 1000 行，应用层约 3800 行），另有 18 个测试文件。

- 协议层（PTP/IP 客户端、数据解析、模型，约 1000 行）：照着逻辑用 Swift 重写，工作量不大，现有测试可以对照。
- 纯逻辑（约 24 个文件）：目录整理、双卡副本合并、存储卡筛选、自动导入规则、日期分组、格式筛选、传输队列、已导入证据、检查更新的版本比较等。不依赖 Android，现有单元测试可以逐条对照移植。
- 界面和系统集成（约 25 个文件）：主界面、预览与缩放、连接相机 Wi-Fi、前台服务、图片解码、保存到相册。必须重写，是主要工作量。

## 技术方案（建议）

- 语言与界面：Swift，SwiftUI。不用 Kotlin Multiplatform 或跨平台框架：现有代码用了 java.time、java.io 等平台接口，构建脚本是自定义的 kotlinc，引入多平台的改造成本和风险都大于重写。
- 结构：一个独立的 Swift Package（暂名 ZfCore），放协议、解析和纯逻辑；网络传输放在一个协议接口后面，测试时用假数据代替，这样 ZfCore 的单元测试不依赖相机，也不依赖具体平台。应用层放 SwiftUI 界面和系统集成。
- 传输：Network.framework（NWConnection）建立到相机的 TCP 连接，命令通道和事件通道各一条，与 Android 一致。
- 保存照片：PhotoKit，只申请“添加照片”权限。
- 连接相机热点：NEHotspotConfiguration，需要“Hotspot Configuration”能力，据我所知需要付费开发者账号，未验证。另外 iOS 14 起访问局域网设备需要“本地网络”权限，要在 Info.plist 里写用途说明。

## iOS 与 Android 的能力差别

1. 后台。iOS 没有前台服务和唤醒锁，应用到后台或锁屏后网络连接很快被挂起。Android 版的“锁屏保持连接”“锁屏期间收通知、解锁后补下”在 iOS 上基本做不到同等效果，自动导入只能在应用打开时工作。
2. 连接热点。iOS 用系统弹窗确认加入，连上后系统可能提示“无互联网连接”，加入热点的行为和 Android 的 WifiNetworkSpecifier 不同，需要实机摸索。
3. 蓝牙唤醒热点：Android 上的实验还没有解决热点上的握手问题，iOS 不在范围内。
4. 签名和安装。免费个人账号只能给自己的设备签名，有效期很短，需要反复重装；用 TestFlight 分发给朋友需要付费开发者账号。

## 没有 iPhone 时能做什么（用户有 iPhone，仅作备用）

Xcode 的模拟器使用 Mac 的网络。让 Mac 自己连上相机热点后，模拟器里的应用可以访问 192.168.1.1，所以协议、浏览、缩略图、预览、导入到模拟器相册都可以在没有 iPhone 的情况下测试。模拟器测不了的：NEHotspotConfiguration 加入热点、真机的后台和锁屏行为、真机的本地网络权限弹窗、保存到真机相册。这部分是否能做取决于有没有 iPhone。

## 分阶段

阶段 0：确认环境和决定。确认有没有 iPhone 和开发者账号；确认 Mac 的系统版本能装哪个 Xcode；在 Mac 上建一个能编译的空工程。

阶段 1：ZfCore。用 Swift 移植协议层和纯逻辑，把 Kotlin 测试里的数据和断言搬过来作为 Swift 测试。验收：测试在 Mac 上全部通过。如果在 Windows 上装了 Swift 工具链，可以在这里先跑纯逻辑部分的测试，网络相关的代码仍然要在 Mac 上验证。

阶段 2：最小应用。Mac 手动连相机热点，在模拟器里连接、读目录、显示缩略图、预览、导入 JPEG 到相册。验收：与 Android 同一台 Zf 上读到同样数量的照片，导入的文件与相机里一致。

阶段 3：真机和系统集成（需要 iPhone，付费账号取决于热点能力）。NEHotspotConfiguration 加入热点、本地网络权限、真机后台行为、保存到相册。

阶段 4：功能对齐。日期分组、格式和存储卡筛选、双卡副本合并、已导入标记、重试队列、自动导入（仅应用打开时）、检查更新。

阶段 5：分发。TestFlight 或自行签名，写 iOS 版的说明和已知限制。

## 未决问题

1. iPhone 已确认有，阶段 3 可以做；型号和 iOS 版本还没有问。
2. 有没有付费开发者账号？影响热点能力（NEHotspotConfiguration）和 TestFlight 分发。免费个人账号可以给自己的 iPhone 装，但有效期很短，需要反复重装，热点能力能不能用我没有验证。
3. Mac 的型号和系统版本？决定能装的 Xcode 版本。
4. 是否在 Windows 上安装 Swift 工具链，用来在 Windows 上编译和测试 ZfCore 的纯逻辑部分。好处是我能在写代码的同时验证编译和测试结果，坏处是要下载约 1 到数 GB 并依赖 Visual Studio 构建工具，安装在用户电脑上，需要用户同意。
5. iOS 版是否和 Android 版共用同一个 GitHub 仓库（新增 ios 目录）还是单独仓库。建议先放同一仓库的 ios 目录，共用文档和许可证。

## 风险

- 没有经验证的前提：iOS 能否在相机热点（无互联网）上稳定保持连接、能否访问 192.168.1.1:15740，都没有实测。
- iOS 的本地网络权限和热点配置的行为和 Android 差别大，实机之前不能确认体验。
- 我不能在 Windows 上编译 iOS 代码，Swift 代码在 Mac 上第一次编译可能有较多修改；需要用户在 Mac 上配合运行和反馈编译错误。

## 方向调整：有线（ImageCaptureCore）加蓝牙（2026-11-04 调研）

用户 2026-11-04 选定研究两条路：B 是用 USB 线连接，通过苹果的 ImageCaptureCore 框架读相机；E 是用蓝牙在后台保持联系，按需开启 Wi-Fi。下面是资料调研的结论，**都没有在 iPhone 上验证过**。

### B：有线 + ImageCaptureCore

能做到的：

- ImageCaptureCore 从 iOS 13.2 起对第三方应用开放，可以列出 USB 连接的相机里的文件、读缩略图和元数据、下载原图，并带下载进度回调。还可以直接发 PTP 命令（requestSendPTPCommand），需要时可以读尼康私有的对象信息。
- App Store 上已经有用这个框架从相机导入照片的第三方应用（如 Image Capture Go、Capture: Camera & SD Import），说明这条路苹果允许、能上架。
- 不需要自己实现 PTP/IP 协议，不用加入热点，不需要本地网络权限，也不需要 Hotspot Configuration 能力。免费开发者账号应该也能用，但没有验证。
- 协议层不需要用 Nikon Pull 的 GPL 代码，将来如果要上 App Store，这一部分没有许可证障碍。
- 纯逻辑（日期分组、格式和存储卡筛选、双卡副本合并、已导入标记、传输队列）照样可以从 Kotlin 移植。

风险和未知：

- 苹果开发者论坛上有几条反馈：iOS 上找不到 USB 单反、文件列表一直是空的、iOS 18 升级后原本能连的应用失效（与“文件和隐私”设置有关）。这些反馈年份不一、没有官方答复，不知道在当前 iOS 上是否还存在。
- 相机的 USB 设置要选对。一位 Z8 用户的报告是：把“USB 数据连接”设为 MTP/PTP 后，iPhone 的“照片”应用出现导入入口；设为 iPhone 则是给尼康官方 NX MobileAir 用的。Zf 上的菜单名称和选项需要实机确认。
- iPhone 15 及以后是 USB-C，可以直接用 C 对 C 的线；更早的机型要用苹果的 Lightning 转 USB 相机转换器。
- 线连接时 iPhone 可能会顺带给相机供电，费手机电，需要实测。
- 缺点是必须插线，不能拍完就传。

**最便宜的第一步验证**：不用写代码。Zf 设为 MTP/PTP，用线连 iPhone，看系统“照片”应用能不能列出并导入 Zf 里的照片。能的话，ImageCaptureCore 大概率也能识别；如果“照片”都不行，这条路基本走不通。

### E：蓝牙后台 + 按需开 Wi-Fi

iOS 允许的：

- 声明 bluetooth-central 后台模式后，应用在后台也能保持蓝牙连接；相机发来通知、或重新出现在附近时，系统会唤醒应用，配合状态恢复，应用被系统回收后也能被重新启动。
- 每次唤醒只给很短的运行时间（苹果文档的说法是大约 10 秒），适合做短操作：接收相机的通知、写一下时间和位置、发一条本地通知。
- 用户从多任务界面划掉应用后，系统不再为蓝牙事件重启它。苹果工程师在论坛上提到 iOS 26 起，用 AccessorySetupKit 配对的配件例外，但这条只看到论坛转述，没有看到官方文档。

iOS 不允许或很难做到的：

- **后台加入热点不行**。NEHotspotConfiguration 只能在前台调用，后台调用直接返回“应用不在前台”的错误；用“仅本次加入”方式连上的热点，应用退到后台约 15 秒后就会断开。
- **后台通过 Wi-Fi 下载原图基本不可靠**。苹果技术支持工程师在论坛上的原话大意是：只要应用没有被挂起，TCP 本身在后台没有问题；真正的问题是 Wi-Fi，iOS 会离开没有互联网的网络、改用蜂窝数据，这对这类应用是致命的。
- **尼康官方也没有做到**。SnapBridge 在 App Store 上的说明写着：通过 Wi-Fi 从相机下载照片时，要保持应用在前台，后台不能下载。它的后台自动传输是把照片缩成 2 百万像素后传，原图要手动下载。
- 用蓝牙直接传原图不现实：一张 Zf 的 JPEG 原图大约 10 MB 以上，iPhone 上低功耗蓝牙的实际速率估计每秒只有几十 KB，一张图要几分钟。而且官方的蓝牙传图协议也没有人逆向过。

和 Android 的实验结果叠加：

- Android 上已经能用蓝牙让 Zf 开启热点，握手算法也有单元测试，可以移植到 iOS 的 CoreBluetooth。
- 但蓝牙唤醒出来的是“SnapBridge 模式”的热点，我们的 PTP 握手被相机拒绝，这个问题在 iOS 上同样存在，并且还没有解决。在它解决之前，E 只能做到“唤醒相机”，读不了照片。
- 应用自己配对（不借用官方应用的身份）也还没有做。
- 握手实现参考的开源项目是 nsg（AGPL-3.0）和 nikongeotag（GPL-3.0）；要上 App Store，需要依据 MIT 许可的 furble 和自己的抓包重写。

所以 E 在 iOS 上现实的目标是：

1. 后台：通过蓝牙知道“相机在附近、拍了新照片”，发一条本地通知，提醒用户打开应用导入。能否收到相机的新照片通知，要先在 Android 抓包里确认相机有没有这类蓝牙通知。
2. 前台：用户打开应用后，用蓝牙让相机开启 Wi-Fi，省去在相机菜单里手动操作。前提是解决 SnapBridge 模式热点的握手。
3. 不追求后台无线传原图。

### B + E 组合

两者分工：B 负责可靠地读取和导入原图，E 负责后台提醒。两者的界面、导入记录和纯逻辑可以共用。

建议顺序：

1. 用线把 Zf 连到 iPhone，用系统“照片”应用试导入（零代码）。
2. 在 Mac 上用 ImageCaptureCore 写一个最小应用：列出照片、显示缩略图、下载一张原图存进相册。验收：照片数量和文件内容与 Android 版在同一台相机上读到的一致。
3. 把纯逻辑移植到 Swift 包里，接到 B 上，做出可用的有线版。
4. 蓝牙：先移植握手，在 iPhone 上验证后台唤醒和本地通知；用蓝牙开启 Wi-Fi 的功能，等 Android 上解决了 SnapBridge 模式热点的握手之后再做。

### 新的未决问题

1. iPhone 的型号（Lightning 还是 USB-C）和 iOS 版本。
2. Zf 菜单里“USB 数据连接”之类的选项具体有哪些；第 1 步的导入结果。
3. 是否接受 iOS 版必须插线导入原图、后台只能提醒。

### 参考资料

- 苹果论坛：用蓝牙通知让后台 Wi-Fi TCP 传输保持运行是否可行 <https://developer.apple.com/forums/thread/850111>
- NEHotspotConfiguration 的错误码（含“应用不在前台”）<https://developer.apple.com/documentation/networkextension/nehotspotconfigurationerror>
- Core Bluetooth 后台处理 <https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html>
- 状态恢复与划掉应用后的重启规则（论坛转述）<https://developer.apple.com/forums/thread/844477>
- SnapBridge 的 App Store 页面 <https://apple.co/3Oo9Vge>
- ImageCaptureCore 在 iOS 上的论坛讨论 <https://developer.apple.com/forums/thread/119529>，<https://developer.apple.com/forums/thread/751312>
- Z8 通过 USB 连接 iPhone 的用户报告 <https://www.zsystemuser.com/nikon-z-system-news-and/2023-newsviews/the-iphone-and-the-z.html>
