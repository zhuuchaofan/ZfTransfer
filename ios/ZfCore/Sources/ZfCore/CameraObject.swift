/// 相机里的一个文件。字段与 Android 版的 CameraObject 对应；iOS 上由 ImageCaptureCore 的 ICCameraFile 转换而来。
public struct CameraObject: Hashable, Sendable {
    public static let formatExifJPEG = 0x3801

    /// 文件在本次连接里的编号（PTP 对象句柄）。相机重启后可能重新编号，不能跨连接使用。
    public var handle: Int64
    /// 所在存储卡。数值越小，卡槽越靠前。
    public var storageId: Int64
    /// PTP 格式码；不知道时为 0，此时只按文件名后缀判断格式。
    public var format: Int
    public var size: Int64
    public var filename: String
    /// 拍摄时间，形如 "20261028T120000"；不知道时为空字符串。
    public var captureDate: String

    public init(handle: Int64, storageId: Int64, format: Int = 0, size: Int64, filename: String, captureDate: String) {
        self.handle = handle
        self.storageId = storageId
        self.format = format
        self.size = size
        self.filename = filename
        self.captureDate = captureDate
    }
}
