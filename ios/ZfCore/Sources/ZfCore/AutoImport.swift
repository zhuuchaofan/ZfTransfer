/// 应用第一次看到相机之后拍的新照片，自动下载。基线是那一刻相机里已有的照片，
/// 所以断线期间拍的照片下次连上时会补下，而已有的照片不会被整批拉下来。
public enum AutoImport {
    /// 新照片多于这个数时，留给用户自己选，不自动塞满手机。
    public static let catchUpLimit = 100

    /// 跨连接识别一张照片的依据。相机重启后句柄可能重新编号，卡、文件名和拍摄时间不会变。
    public struct Key: Hashable, Codable, Sendable {
        public let storageId: Int64
        public let filename: String
        public let captureDate: String

        public init(storageId: Int64, filename: String, captureDate: String) {
            self.storageId = storageId
            self.filename = filename
            self.captureDate = captureDate
        }
    }

    public static func keyOf(_ obj: CameraObject) -> Key {
        Key(storageId: obj.storageId, filename: obj.filename, captureDate: obj.captureDate)
    }

    public static func baselineOf(_ catalog: [CameraObject]) -> Set<Key> { Set(catalog.map(keyOf)) }

    /// 不在基线里、还没导入的 JPEG，每张照片一份，按拍摄先后排列。
    public static func pending(_ catalog: [CameraObject], baseline: Set<Key>, isImported: (CameraObject) -> Bool) -> [CameraObject] {
        CopyGroups.unique(catalog.filter { $0.canImport && !baseline.contains(keyOf($0)) }, isImported: isImported)
            .filter { !isImported($0) }
            .reversed()
    }
}
