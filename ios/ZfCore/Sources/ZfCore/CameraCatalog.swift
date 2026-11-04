public enum CameraCatalog {
    /// 所有存储卡上的照片和视频，最新的在前。文件夹和其他对象去掉；两张卡上的副本都保留，见 ``CopyGroups``。
    public static func arrange(_ objects: [CameraObject]) -> [CameraObject] {
        objects.filter { MediaFormat.of($0) != nil }.sorted { a, b in
            if a.captureDate != b.captureDate { return a.captureDate > b.captureDate }
            return a.handle > b.handle
        }
    }
}

/// 双卡“备份”记录时，同一张照片在两张卡上各有一份：名称和大小相同，时间差约一秒。
/// 只有每张卡上恰好一份时才算同一张；同一张卡上看起来一样的文件仍是不同的照片。
public enum CopyGroups {
    /// 两份副本的时间最多相差几秒。Zf 上观察到的是一秒。
    static let sameMomentSeconds: Int64 = 2

    public struct Key: Hashable, Sendable {
        public let filename: String
        public let size: Int64
    }

    public static func key(_ obj: CameraObject) -> Key { Key(filename: obj.filename, size: obj.size) }

    /// "20261031T181228" 换算成秒，只用于比较先后；格式不对或日期不存在时返回 nil。
    static func secondsOf(_ date: String) -> Int64? {
        let chars = Array(date.utf8)
        guard chars.count >= 15, chars[8] == UInt8(ascii: "T") else { return nil }
        func number(_ range: Range<Int>) -> Int64? {
            var value: Int64 = 0
            for c in chars[range] {
                guard c >= UInt8(ascii: "0"), c <= UInt8(ascii: "9") else { return nil }
                value = value * 10 + Int64(c - UInt8(ascii: "0"))
            }
            return value
        }
        guard let y = number(0..<4), let mo = number(4..<6), let d = number(6..<8),
              let h = number(9..<11), let mi = number(11..<13), let s = number(13..<15) else { return nil }
        guard (1...12).contains(mo), d >= 1, d <= daysIn(month: mo, year: y), h < 24, mi < 60, s < 60 else { return nil }
        return daysFromCivil(y, mo, d) * 86_400 + h * 3600 + mi * 60 + s
    }

    private static func daysIn(month: Int64, year: Int64) -> Int64 {
        switch month {
        case 2: return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0 ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }

    /// 公历日期到 1970-01-01 的天数（Howard Hinnant 的算法）。
    private static func daysFromCivil(_ year: Int64, _ month: Int64, _ day: Int64) -> Int64 {
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    private static func sameMoment(_ copies: [CameraObject]) -> Bool {
        let seconds = copies.map { secondsOf($0.captureDate) }
        if seconds.contains(where: { $0 == nil }) { return Set(copies.map(\.captureDate)).count == 1 }
        let values = seconds.compactMap { $0 }
        return values.max()! - values.min()! <= sameMomentSeconds
    }

    private static func isOnePhoto(_ copies: [CameraObject]) -> Bool {
        copies.count > 1 && Dictionary(grouping: copies, by: \.storageId).values.allSatisfy { $0.count == 1 } && sameMoment(copies)
    }

    /// 按 key 分组，组的顺序与第一次出现的顺序一致。
    private static func groups(_ photos: [CameraObject]) -> [[CameraObject]] {
        var order: [Key] = []
        var byKey: [Key: [CameraObject]] = [:]
        for photo in photos {
            let k = key(photo)
            if byKey[k] == nil { order.append(k) }
            byKey[k, default: []].append(photo)
        }
        return order.map { byKey[$0]! }
    }

    /// 每张照片只留一份，顺序与 photos 相同。已导入的那份优先，否则留卡槽靠前的。
    public static func unique(_ photos: [CameraObject], isImported: (CameraObject) -> Bool = { _ in false }) -> [CameraObject] {
        var kept = Set<Int64>()
        var merged = Set<Int64>()
        for copies in groups(photos) where isOnePhoto(copies) {
            copies.forEach { merged.insert($0.handle) }
            let chosen = copies.first(where: isImported) ?? copies.min { ($0.storageId, $0.handle) < ($1.storageId, $1.handle) }!
            kept.insert(chosen.handle)
        }
        return photos.filter { !merged.contains($0.handle) || kept.contains($0.handle) }
    }

    /// 任何一份已导入，这张照片的所有副本都算已导入。
    public static func importedWithCopies(_ photos: [CameraObject], isImported: (CameraObject) -> Bool) -> Set<Int64> {
        var handles = Set<Int64>()
        for copies in groups(photos) {
            if isOnePhoto(copies) {
                if copies.contains(where: isImported) { copies.forEach { handles.insert($0.handle) } }
            } else {
                copies.filter(isImported).forEach { handles.insert($0.handle) }
            }
        }
        return handles
    }

    /// 同名文件出现在多张卡上却没有被合并的情况，值得记进日志。
    public static func nearMisses(_ photos: [CameraObject]) -> [[CameraObject]] {
        var order: [String] = []
        var byName: [String: [CameraObject]] = [:]
        for photo in photos {
            if byName[photo.filename] == nil { order.append(photo.filename) }
            byName[photo.filename, default: []].append(photo)
        }
        return order.map { byName[$0]! }.filter { named in
            Set(named.map(\.storageId)).count > 1 && unique(named).count == named.count
        }
    }

    /// 与 handle 是同一张照片的所有句柄，包括它自己。
    public static func copiesOf(_ photos: [CameraObject], handle: Int64) -> Set<Int64> {
        guard let photo = photos.first(where: { $0.handle == handle }) else { return [handle] }
        let copies = photos.filter { key($0) == key(photo) }
        return isOnePhoto(copies) ? Set(copies.map(\.handle)) : [handle]
    }
}

/// 相机的存储卡，按存储编号排序，与相机的卡槽顺序一致。
public enum StorageCards {
    public static func slots(_ photos: [CameraObject]) -> [Int64] { Array(Set(photos.map(\.storageId))).sorted() }

    public static func label(_ slots: [Int64], _ storageId: Int64?) -> String {
        guard let storageId else { return "全部" }
        return "卡 \((slots.firstIndex(of: storageId) ?? -1) + 1)"
    }
}

/// 只有经过确认的导入才算“已导入”。
public enum PhotoCatalog {
    /// card 为 nil 表示所有卡，同一张照片的副本只显示一次；否则只显示那张卡上的文件。
    public static func inCard(_ photos: [CameraObject], verified: Set<Int64>, card: Int64?) -> [CameraObject] {
        guard let card else { return CopyGroups.unique(photos) { verified.contains($0.handle) } }
        return photos.filter { $0.storageId == card }
    }

    public static func visible(_ photos: [CameraObject], verified: Set<Int64>, filter: PhotoFilter,
                               format: MediaFormat = .all, card: Int64? = nil) -> [CameraObject] {
        inCard(photos, verified: verified, card: card).filter { obj in
            guard format.matches(obj) else { return false }
            switch filter {
            case .all: return true
            case .notImported: return !verified.contains(obj.handle)
            case .imported: return verified.contains(obj.handle)
            }
        }
    }

    /// 预览打开期间，已有的成员不变，只在后面追加新出现的、能预览的照片。
    public static func appendPreview(_ existing: [CameraObject], loaded: [CameraObject], verified: Set<Int64>,
                                     filter: PhotoFilter, format: MediaFormat = .all, card: Int64? = nil) -> [CameraObject] {
        var known = Set(existing.map(\.handle))
        return existing + visible(loaded, verified: verified, filter: filter, format: format, card: card)
            .filter { $0.canImport && known.insert($0.handle).inserted }
    }
}

public enum CameraCatalogSummary {
    /// 只有 JPEG 时写“相机共 N 张 JPEG”，否则按格式分别计数；scope 可以是某张卡。
    public static func of(_ loaded: [CameraObject], scope: String = "相机") -> String {
        let counts = MediaFormat.allCases.filter { $0 != .all }
            .map { format in (format, loaded.filter { format.matches($0) }.count) }
            .filter { $0.1 > 0 }
        if counts.allSatisfy({ $0.0 == .jpeg }) { return "\(scope)共 \(loaded.count) 张 JPEG" }
        return "\(scope)共 \(loaded.count) 个：" + counts.map { "\($0.0.label) \($0.1)" }.joined(separator: " · ")
    }
}

public enum CameraEvents {
    /// 这些 PTP 事件之后，相机的文件列表多半变了。
    public static func changesCatalog(_ code: Int) -> Bool { code == 0x4002 || code == 0x4003 || code == 0x400D }
}

public enum ThumbnailPlan {
    public struct Batch: Equatable, Sendable {
        public let handles: [Int64]
        public let next: Int
    }

    /// 接下来要读的缩略图：先读屏幕上的（focus），再从 from 开始按显示顺序读其余的。返回空表示全部读完。
    public static func nextBatch(focus: [Int64], order: [Int64], from: Int, limit: Int, have: (Int64) -> Bool) -> Batch {
        var picked: [Int64] = []
        var seen = Set<Int64>()
        for handle in focus {
            if picked.count >= limit { break }
            if !have(handle), seen.insert(handle).inserted { picked.append(handle) }
        }
        var next = from
        while picked.count < limit && next < order.count {
            let handle = order[next]
            next += 1
            if !have(handle), seen.insert(handle).inserted { picked.append(handle) }
        }
        return Batch(handles: picked, next: next)
    }
}
