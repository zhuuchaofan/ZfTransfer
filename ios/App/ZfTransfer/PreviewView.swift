import ImageIO
import SwiftUI
import UIKit
import ZfCore

/// 全屏预览：下载原图到缓存后显示，支持双指缩放和双击放大，左右滑动切换。
struct PreviewView: View {
    let photos: [CameraObject]
    @State var index: Int
    @ObservedObject var importer: Importer
    @EnvironmentObject private var camera: CameraLink
    @EnvironmentObject private var imports: ImportStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let photo = photos[index]
        NavigationStack {
            TabView(selection: $index) {
                ForEach(photos.indices, id: \.self) { i in
                    PreviewPage(photo: photos[i], thumbnail: camera.thumbnails[photos[i].handle])
                        .tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .background(Color.black)
            .navigationTitle(photo.filename)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.visible, for: .navigationBar, .bottomBar)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("关闭") { dismiss() } }
                ToolbarItem(placement: .bottomBar) {
                    if importer.isRunning {
                        Text("导入 \(importer.done)/\(importer.total)")
                    } else if imports.isImported(photo) {
                        Text("已导入").foregroundStyle(.secondary)
                    } else {
                        Button("导入原图") { importer.run([photo], camera: camera, store: imports) }
                    }
                }
            }
        }
    }
}

private struct PreviewPage: View {
    let photo: CameraObject
    let thumbnail: UIImage?
    @EnvironmentObject private var camera: CameraLink
    @State private var image: UIImage?
    @State private var failure: String?

    var body: some View {
        ZStack {
            if let image {
                ZoomableImage(image: image)
            } else {
                if let thumbnail {
                    Image(uiImage: thumbnail).resizable().scaledToFit()
                }
                if let failure {
                    Text(failure).foregroundStyle(.white).padding().background(.black.opacity(0.6))
                } else {
                    ProgressView().tint(.white)
                }
            }
        }
        .task(id: photo.handle) {
            do {
                let url = try await camera.originalForPreview(photo)
                image = await Task.detached { Self.decode(url, maxPixel: 4096) }.value
                if image == nil { failure = "无法解码这张照片" }
            } catch {
                failure = "读取原图失败：\(error.localizedDescription)"
            }
        }
    }

    /// 缩到最长边 maxPixel 再显示，避免 24MP 原图占太多内存；同时按 EXIF 方向转正。
    nonisolated static func decode(_ url: URL, maxPixel: Int) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

/// UIScrollView 做的缩放视图：双指缩放、双击在适应屏幕和 3 倍之间切换。
struct ZoomableImage: UIViewRepresentable {
    let image: UIImage

    func makeUIView(context: Context) -> UIScrollView {
        let scroll = UIScrollView()
        scroll.delegate = context.coordinator
        scroll.minimumZoomScale = 1
        scroll.maximumZoomScale = 6
        scroll.showsVerticalScrollIndicator = false
        scroll.showsHorizontalScrollIndicator = false
        scroll.backgroundColor = .black
        let imageView = UIImageView(image: image)
        imageView.contentMode = .scaleAspectFit
        imageView.frame = scroll.bounds
        imageView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        scroll.addSubview(imageView)
        context.coordinator.imageView = imageView
        let doubleTap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTapped(_:)))
        doubleTap.numberOfTapsRequired = 2
        scroll.addGestureRecognizer(doubleTap)
        return scroll
    }

    func updateUIView(_ scroll: UIScrollView, context: Context) {
        context.coordinator.imageView?.image = image
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        weak var imageView: UIImageView?

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

        @objc func doubleTapped(_ gesture: UITapGestureRecognizer) {
            guard let scroll = gesture.view as? UIScrollView else { return }
            if scroll.zoomScale > 1 {
                scroll.setZoomScale(1, animated: true)
            } else {
                let point = gesture.location(in: imageView)
                let size = CGSize(width: scroll.bounds.width / 3, height: scroll.bounds.height / 3)
                scroll.zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2, width: size.width, height: size.height), animated: true)
            }
        }
    }
}
