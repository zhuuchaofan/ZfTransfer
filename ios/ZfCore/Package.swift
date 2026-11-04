// swift-tools-version:5.9
import PackageDescription

// 与平台无关的纯逻辑：照片目录整理、双卡副本合并、筛选、日期分组、自动导入规则。
// 不依赖 ImageCaptureCore 和 UIKit，所以可以在 Linux 和 Mac 上直接跑测试。
let package = Package(
    name: "ZfCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [.library(name: "ZfCore", targets: ["ZfCore"])],
    targets: [
        .target(name: "ZfCore"),
        .testTarget(name: "ZfCoreTests", dependencies: ["ZfCore"]),
    ]
)
