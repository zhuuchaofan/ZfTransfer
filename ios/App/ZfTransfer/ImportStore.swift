import Foundation
import Photos
import ZfCore

/// 导入记录和保存到相册。
///
/// 只申请“添加照片”权限，应用读不回相册，所以“已导入”只依据本应用成功写入相册的记录。
/// 记录用拍摄时间、文件名和存储卡标识照片，不用句柄，因为句柄在相机重启后可能变化。
@MainActor
final class ImportStore: ObservableObject {
    @Published private(set) var imported: Set<AutoImport.Key> = []

    private let fileURL: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("imported.json")
    }()

    init() {
        if let data = try? Data(contentsOf: fileURL),
           let keys = try? JSONDecoder().decode([AutoImport.Key].self, from: data) {
            imported = Set(keys)
        }
    }

    func isImported(_ object: CameraObject) -> Bool { imported.contains(AutoImport.keyOf(object)) }

    /// 已导入的句柄，包括另一张卡上的副本。
    func verifiedHandles(in catalog: [CameraObject]) -> Set<Int64> {
        CopyGroups.importedWithCopies(catalog, isImported: isImported)
    }

    private func record(_ object: CameraObject) {
        imported.insert(AutoImport.keyOf(object))
        if let data = try? JSONEncoder().encode(Array(imported)) {
            try? data.write(to: fileURL, options: .atomic)
        }
    }

    struct SaveError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    static func requestAddAccess() async -> Bool {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        return status == .authorized || status == .limited
    }

    /// 把下载好的 JPEG 写入相册，文件被移入相册，成功后记为已导入。
    func save(_ fileURL: URL, as object: CameraObject) async throws {
        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            let options = PHAssetResourceCreationOptions()
            options.originalFilename = object.filename
            options.shouldMoveFile = true
            request.addResource(with: .photo, fileURL: fileURL, options: options)
        }
        record(object)
    }
}
