/// 按拍摄日期把连续的照片分组。保持相机的顺序，后加载的照片只会延长最后一组或开新组。
public enum DayGroups {
    public static let unknownDay = "未知日期"

    /// "20261028T120000" 变成 "2026-10-28"。
    public static func dayOf(_ captureDate: String) -> String {
        let digits = Array(captureDate.prefix(8))
        guard digits.count == 8, digits.allSatisfy({ $0.isASCII && $0.isNumber }) else { return unknownDay }
        let s = String(digits)
        return "\(s.prefix(4))-\(s.dropFirst(4).prefix(2))-\(s.dropFirst(6))"
    }

    public struct Section: Equatable, Identifiable, Sendable {
        public let day: String
        /// 在照片列表里的起始位置和数量。
        public let first: Int
        public let count: Int
        /// 同一天可能在列表里出现多次，用起始位置区分。
        public var id: Int { first }
        public var indices: Range<Int> { first..<(first + count) }
    }

    /// SwiftUI 的网格自带分组标题，只需要分组本身，不需要 Android 版那样的补位格子。
    public static func sections(_ photos: [CameraObject]) -> [Section] {
        var result: [Section] = []
        var start = 0
        while start < photos.count {
            let day = dayOf(photos[start].captureDate)
            var end = start + 1
            while end < photos.count && dayOf(photos[end].captureDate) == day { end += 1 }
            result.append(Section(day: day, first: start, count: end - start))
            start = end
        }
        return result
    }
}

/// 拖动选择：以手势开始时的选择为基础，按起点和终点之间的范围增加或去掉，可以来回拖。
public struct RangeSelection: Sendable {
    private let initial: Set<Int>
    private let anchor: Int

    public init(initial: Set<Int>, anchor: Int) {
        self.initial = initial
        self.anchor = anchor
    }

    public func at(_ end: Int) -> Set<Int> {
        let range = Set(min(anchor, end)...max(anchor, end))
        return initial.contains(anchor) ? initial.subtracting(range) : initial.union(range)
    }
}
