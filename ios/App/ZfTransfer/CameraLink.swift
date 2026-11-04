import Foundation
import ImageCaptureCore
import UIKit
import ZfCore

/// 通过 ImageCaptureCore 读取 USB 连接的相机。
///
/// 流程：申请读取相机内容的授权 → ICDeviceBrowser 发现相机 → 打开会话 → 等相机报告“目录完整”
/// → 把 mediaFiles 转成 ZfCore 的 CameraObject。缩略图和原图都按需读取。
///
/// 未验证（需要实机确认）：Zf 在 MTP/PTP 模式下能否被发现；存储卡的区分方式；拍摄时间取哪个字段最准。
@MainActor
final class CameraLink: NSObject, ObservableObject {
    enum Phase: Equatable {
        case idle
        case waitingForAuthorization
        case denied(String)
        case searching
        case opening(String)
        case loading(String)
        case ready(String)
        case failed(String)
    }

    @Published private(set) var phase: Phase = .idle
    /// 整理后的目录：只含照片和视频，最新的在前，两张卡上的副本都保留。
    @Published private(set) var catalog: [CameraObject] = []
    @Published private(set) var thumbnails: [Int64: UIImage] = [:]

    let log = DiagnosticLog()

    private let browser = ICDeviceBrowser()
    private var camera: ICCameraDevice?
    private var files: [Int64: ICCameraFile] = [:]
    private var thumbnailRequested = Set<Int64>()
    private var started = false

    let downloadsDirectory: URL = {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("CameraDownloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    let previewDirectory: URL = {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let url = base.appendingPathComponent("Originals", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    var cameraName: String { camera?.name ?? "相机" }

    func start() {
        guard !started else { return }
        started = true
        browser.delegate = self
        phase = .waitingForAuthorization
        log.add("申请读取相机内容的授权")
        browser.requestContentsAuthorization { [weak self] status in
            Task { @MainActor in self?.authorizationAnswered(status) }
        }
    }

    private func authorizationAnswered(_ status: ICAuthorizationStatus) {
        log.add("相机内容授权：\(status.rawValue)")
        // 苹果论坛有报告：iOS 26.1 测试版上这个授权一直返回“未确定”，也不弹窗。
        // 所以只有明确被拒绝时才停下，其他情况照样开始查找，能不能读到由后面的步骤说明。
        if status == .denied || status == .restricted {
            phase = .denied("没有读取相机内容的权限。请到“设置 → Zf 传图”中允许访问，再回到应用。")
            return
        }
        phase = .searching
        browser.start()
        log.add("开始查找相机（USB）")
    }

    /// 授权被拒后，用户从设置回来时重试。
    func retryAuthorization() {
        started = false
        start()
    }

    // MARK: 目录

    private func rebuildCatalog() {
        guard let camera else { return }
        let mediaFiles = camera.mediaFiles ?? []
        // 顶层条目一般对应存储卡；把每个文件归到它最上层的文件夹，按顶层顺序编号（未验证）。
        let roots = (camera.contents ?? []).map { ObjectIdentifier($0) }
        var objects: [CameraObject] = []
        var map: [Int64: ICCameraFile] = [:]
        var unknownRoot = 0
        for case let file as ICCameraFile in mediaFiles {
            let handle = Int64(file.ptpObjectHandle)
            var root: ICCameraItem = file
            while let parent = root.parentFolder { root = parent }
            let storageIndex = roots.firstIndex(of: ObjectIdentifier(root))
            if storageIndex == nil { unknownRoot += 1 }
            let object = CameraObject(
                handle: handle,
                storageId: Int64((storageIndex ?? 0) + 1),
                format: 0,
                size: Int64(file.fileSize),
                filename: file.name ?? "未命名",
                captureDate: Self.captureDate(file.creationDate)
            )
            objects.append(object)
            map[handle] = file
        }
        files = map
        catalog = CameraCatalog.arrange(objects)
        let slots = StorageCards.slots(catalog)
        log.add("目录：媒体文件 \(mediaFiles.count) 个，整理后 \(catalog.count) 个，顶层条目 \(roots.count) 个，存储卡 \(slots.count) 张，找不到顶层的 \(unknownRoot) 个")
        log.add(CameraCatalogSummary.of(catalog))
        for group in CopyGroups.nearMisses(catalog).prefix(5) {
            log.add("同名但未合并：\(group.map { "\($0.filename)/卡\($0.storageId)/\($0.size)/\($0.captureDate)" }.joined(separator: ", "))")
        }
        phase = .ready(cameraName)
    }

    /// 与 Android 版 PTP 的 CaptureDate 格式一致："yyyyMMdd'T'HHmmss"，按手机所在时区。
    static func captureDate(_ date: Date?) -> String {
        guard let date else { return "" }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd'T'HHmmss"
        return formatter.string(from: date)
    }

    // MARK: 缩略图

    func requestThumbnail(_ handle: Int64) {
        guard thumbnails[handle] == nil, !thumbnailRequested.contains(handle), let file = files[handle] else { return }
        thumbnailRequested.insert(handle)
        if let image = file.thumbnail {
            thumbnails[handle] = UIImage(cgImage: image)
            return
        }
        file.requestThumbnail()
    }

    // MARK: 下载

    struct DownloadError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// 把原图下载到 directory，返回文件位置。
    func download(_ handle: Int64, to directory: URL) async throws -> URL {
        guard let file = files[handle] else { throw DownloadError(message: "相机上找不到这张照片，可能已经断开或照片被删除") }
        let started = Date()
        let options: [ICDownloadOption: Any] = [
            .downloadsDirectoryURL: directory,
            .overwrite: true,
        ]
        let savedName: String = try await withCheckedThrowingContinuation { continuation in
            _ = file.requestDownload(options: options) { filename, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let filename {
                    continuation.resume(returning: filename)
                } else {
                    continuation.resume(throwing: DownloadError(message: "下载没有返回文件名"))
                }
            }
        }
        let url = directory.appendingPathComponent(savedName)
        let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? -1
        let seconds = Date().timeIntervalSince(started)
        log.add(String(format: "下载 %@：%lld 字节（相机报告 %lld），用时 %.2f 秒", file.name ?? "?", size, Int64(file.fileSize), seconds))
        if size != Int64(file.fileSize) {
            throw DownloadError(message: "下载的大小与相机报告的不一致")
        }
        return url
    }

    /// 预览用的原图缓存；同一张照片只下载一次。
    func originalForPreview(_ object: CameraObject) async throws -> URL {
        let cached = previewDirectory.appendingPathComponent("\(object.storageId)-\(object.captureDate)-\(object.filename)")
        if let size = try? FileManager.default.attributesOfItem(atPath: cached.path)[.size] as? Int64, size == object.size {
            return cached
        }
        let temporary = try await download(object.handle, to: downloadsDirectory)
        try? FileManager.default.removeItem(at: cached)
        try FileManager.default.moveItem(at: temporary, to: cached)
        return cached
    }

    private func reset(_ phase: Phase) {
        camera = nil
        files = [:]
        catalog = []
        thumbnails = [:]
        thumbnailRequested = []
        self.phase = phase
    }
}

// MARK: - ICDeviceBrowserDelegate

extension CameraLink: ICDeviceBrowserDelegate {
    nonisolated func deviceBrowser(_ browser: ICDeviceBrowser, didAdd device: ICDevice, moreComing: Bool) {
        Task { @MainActor in self.deviceAdded(device) }
    }

    nonisolated func deviceBrowser(_ browser: ICDeviceBrowser, didRemove device: ICDevice, moreGoing: Bool) {
        Task { @MainActor in
            self.log.add("设备移除：\(device.name ?? "?")")
            if device === self.camera { self.reset(.searching) }
        }
    }

    private func deviceAdded(_ device: ICDevice) {
        log.add("发现设备：\(device.name ?? "?")，传输方式 \(device.transportType ?? "?")，是否相机 \(device is ICCameraDevice)")
        guard let found = device as? ICCameraDevice else { return }
        guard camera == nil else {
            log.add("已经连着一台相机，忽略新设备")
            return
        }
        camera = found
        found.delegate = self
        phase = .opening(found.name ?? "相机")
        log.add("打开会话")
        found.requestOpenSession()
    }
}

// MARK: - ICCameraDeviceDelegate

extension CameraLink: ICCameraDeviceDelegate {
    nonisolated func device(_ device: ICDevice, didOpenSessionWithError error: Error?) {
        Task { @MainActor in
            if let error {
                self.log.add("打开会话失败：\(error.localizedDescription)")
                self.phase = .failed("打开相机失败：\(error.localizedDescription)")
            } else {
                self.log.add("会话已打开，等待相机报告目录")
                self.phase = .loading(device.name ?? "相机")
            }
        }
    }

    nonisolated func device(_ device: ICDevice, didCloseSessionWithError error: Error?) {
        Task { @MainActor in self.log.add("会话关闭：\(error?.localizedDescription ?? "正常")") }
    }

    nonisolated func didRemove(_ device: ICDevice) {
        Task { @MainActor in
            self.log.add("相机断开")
            if device === self.camera { self.reset(.searching) }
        }
    }

    nonisolated func deviceDidBecomeReady(withCompleteContentCatalog device: ICCameraDevice) {
        Task { @MainActor in
            self.log.add("相机目录已完整")
            self.rebuildCatalog()
        }
    }

    nonisolated func cameraDevice(_ camera: ICCameraDevice, didAdd items: [ICCameraItem]) {
        Task { @MainActor in
            guard case .ready = self.phase else { return }
            self.log.add("相机新增 \(items.count) 个条目")
            self.rebuildCatalog()
        }
    }

    nonisolated func cameraDevice(_ camera: ICCameraDevice, didRemove items: [ICCameraItem]) {
        Task { @MainActor in
            guard case .ready = self.phase else { return }
            self.log.add("相机删除 \(items.count) 个条目")
            self.rebuildCatalog()
        }
    }

    nonisolated func cameraDevice(_ camera: ICCameraDevice, didRenameItems items: [ICCameraItem]) {}

    nonisolated func cameraDevice(_ camera: ICCameraDevice, didReceiveThumbnail thumbnail: CGImage?, for item: ICCameraItem, error: Error?) {
        let handle = Int64(item.ptpObjectHandle)
        let image = thumbnail.map { UIImage(cgImage: $0) }
        Task { @MainActor in
            if let image {
                self.thumbnails[handle] = image
            } else {
                self.thumbnailRequested.remove(handle)
                if let error { self.log.add("缩略图失败 \(item.name ?? "?")：\(error.localizedDescription)") }
            }
        }
    }

    nonisolated func cameraDevice(_ camera: ICCameraDevice, didReceiveMetadata metadata: [AnyHashable: Any]?, for item: ICCameraItem, error: Error?) {}

    nonisolated func cameraDeviceDidChangeCapability(_ camera: ICCameraDevice) {}

    nonisolated func cameraDevice(_ camera: ICCameraDevice, didReceivePTPEvent eventData: Data) {}

    nonisolated func cameraDeviceDidRemoveAccessRestriction(_ device: ICDevice) {
        Task { @MainActor in self.log.add("相机解除访问限制") }
    }

    nonisolated func cameraDeviceDidEnableAccessRestriction(_ device: ICDevice) {
        Task { @MainActor in self.log.add("相机启用访问限制") }
    }
}
