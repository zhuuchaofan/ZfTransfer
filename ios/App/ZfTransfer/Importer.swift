import SwiftUI
import ZfCore

/// 依次下载原图并写入相册。一张失败不影响后面的；结束后给出成功和失败的数量。
@MainActor
final class Importer: ObservableObject {
    @Published private(set) var isRunning = false
    @Published private(set) var done = 0
    @Published private(set) var total = 0
    @Published private(set) var current: String?
    @Published var message: String?
    private var cancelled = false

    func run(_ photos: [CameraObject], camera: CameraLink, store: ImportStore) {
        guard !isRunning, !photos.isEmpty else { return }
        Task { await perform(photos, camera: camera, store: store) }
    }

    func cancel() { cancelled = true }

    private func perform(_ photos: [CameraObject], camera: CameraLink, store: ImportStore) async {
        guard await ImportStore.requestAddAccess() else {
            message = "没有添加照片的权限。请到“设置 → Zf 传图 → 照片”中允许“仅添加”。"
            return
        }
        isRunning = true
        cancelled = false
        message = nil
        done = 0
        total = photos.count
        var failed = 0
        camera.log.add("开始导入 \(photos.count) 张")
        for photo in photos {
            if cancelled { break }
            current = photo.filename
            do {
                let url = try await camera.download(photo.handle, to: camera.downloadsDirectory)
                try await store.save(url, as: photo)
            } catch {
                failed += 1
                camera.log.add("导入失败 \(photo.filename)：\(error.localizedDescription)")
            }
            done += 1
        }
        let succeeded = done - failed
        camera.log.add("导入结束：成功 \(succeeded)，失败 \(failed)，\(cancelled ? "已取消" : "完成")")
        isRunning = false
        current = nil
        message = failed == 0 ? "已导入 \(succeeded) 张到相册" : "导入 \(succeeded) 张，失败 \(failed) 张（详情见诊断日志）"
    }
}

struct ImportBanner: View {
    @ObservedObject var importer: Importer

    var body: some View {
        HStack {
            if importer.isRunning {
                ProgressView(value: Double(importer.done), total: Double(max(importer.total, 1)))
                    .frame(width: 80)
                Text("导入 \(importer.done)/\(importer.total)  \(importer.current ?? "")")
                    .font(.subheadline)
                    .lineLimit(1)
                Spacer()
                Button("取消") { importer.cancel() }
            } else if let message = importer.message {
                Text(message).font(.subheadline)
                Spacer()
                Button("好") { importer.message = nil }
            }
        }
        .padding()
        .background(.bar)
    }
}
