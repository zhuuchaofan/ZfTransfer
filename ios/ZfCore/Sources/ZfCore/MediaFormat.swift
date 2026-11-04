/// 导入状态筛选。
public enum PhotoFilter: String, CaseIterable, Sendable {
    case all = "全部"
    case notImported = "未导入"
    case imported = "已导入"
    public var label: String { rawValue }
}

/// 文件格式筛选。文件名后缀优先于 PTP 格式码；HLG 不从后缀推断。
public enum MediaFormat: String, CaseIterable, Sendable {
    case all = "全部格式"
    case jpeg = "JPEG"
    case raw = "RAW"
    case video = "视频"
    case heif = "HIF / HEIF"
    public var label: String { rawValue }

    public func matches(_ obj: CameraObject) -> Bool { self == .all || self == MediaFormat.of(obj) }

    public static func of(_ obj: CameraObject) -> MediaFormat? { classify(filename: obj.filename, ptpFormat: obj.format) }

    public static func classify(filename: String, ptpFormat: Int = 0) -> MediaFormat? {
        let suffix: String
        if let dot = filename.lastIndex(of: ".") {
            suffix = String(filename[filename.index(after: dot)...]).lowercased()
        } else {
            suffix = ""
        }
        switch suffix {
        case "jpg", "jpeg": return .jpeg
        case "nef", "nrw", "dng": return .raw
        case "mov", "mp4", "avi", "mpeg", "mpg", "m4v": return .video
        case "hif", "heif", "heic": return .heif
        default:
            switch ptpFormat {
            case CameraObject.formatExifJPEG: return .jpeg
            case 0x300A, 0x300B, 0x300C, 0x300D: return .video
            default: return nil
            }
        }
    }
}

public extension CameraObject {
    /// 目前只有 JPEG 能预览和导入，其他格式只能浏览。
    var canImport: Bool { MediaFormat.of(self) == .jpeg }
}
