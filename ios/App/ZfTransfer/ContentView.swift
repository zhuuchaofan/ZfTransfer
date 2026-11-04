import SwiftUI
import UIKit

struct ContentView: View {
    @EnvironmentObject private var camera: CameraLink
    @State private var showingLog = false

    var body: some View {
        NavigationStack {
            Group {
                switch camera.phase {
                case .ready:
                    GalleryView()
                default:
                    StatusView()
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showingLog = true } label: { Image(systemName: "doc.text.magnifyingglass") }
                        .accessibilityLabel("诊断日志")
                }
            }
            .sheet(isPresented: $showingLog) { LogView(log: camera.log) }
        }
    }
}

/// 没连上相机时的说明和当前进度。
struct StatusView: View {
    @EnvironmentObject private var camera: CameraLink

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            switch camera.phase {
            case .idle, .waitingForAuthorization:
                ProgressView("正在申请权限…")
            case .denied(let message):
                Image(systemName: "lock").font(.largeTitle)
                Text(message).multilineTextAlignment(.center)
                Button("打开设置") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
                Button("重试") { camera.retryAuthorization() }
            case .searching:
                Image(systemName: "cable.connector").font(.largeTitle)
                Text("等待相机连接").font(.headline)
                VStack(alignment: .leading, spacing: 8) {
                    Text("1. 相机菜单里把 USB 连接方式设为 MTP/PTP。")
                    Text("2. 用 USB-C 线连接相机和 iPhone，打开相机。")
                    Text("3. iPhone 解锁并保持本应用在前台。")
                    Text("如果系统“照片”应用弹出导入界面，先把它关掉，再回到这里。")
                        .foregroundStyle(.secondary)
                }
                .font(.subheadline)
                ProgressView()
            case .opening(let name):
                ProgressView("正在打开 \(name)…")
            case .loading(let name):
                ProgressView("正在读取 \(name) 的照片列表…")
            case .failed(let message):
                Image(systemName: "exclamationmark.triangle").font(.largeTitle)
                Text(message).multilineTextAlignment(.center)
                Text("拔下线重新连接试试；还不行请把诊断日志发给开发者。").foregroundStyle(.secondary)
            case .ready:
                EmptyView()
            }
            Spacer()
        }
        .padding()
        .navigationTitle("Zf 传图")
    }
}

struct LogView: View {
    @ObservedObject var log: DiagnosticLog
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(log.text.isEmpty ? "暂无记录" : log.text)
                    .font(.system(.caption, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
            }
            .navigationTitle("诊断日志")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("关闭") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("复制") { UIPasteboard.general.string = log.text }
                }
            }
        }
    }
}
