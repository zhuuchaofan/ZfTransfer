import Foundation

/// 应用内的诊断日志。实机测试时用户可以一键复制，贴回来排查。只记录事件和数量，不记录照片内容。
@MainActor
final class DiagnosticLog: ObservableObject {
    @Published private(set) var lines: [String] = []
    private let limit = 500
    private let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    func add(_ message: String) {
        let line = "\(formatter.string(from: Date())) \(message)"
        lines.append(line)
        if lines.count > limit { lines.removeFirst(lines.count - limit) }
        print("[ZfTransfer] \(line)")
    }

    var text: String { lines.joined(separator: "\n") }
}
