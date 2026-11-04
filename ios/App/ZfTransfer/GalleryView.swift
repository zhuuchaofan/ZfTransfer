import SwiftUI
import UIKit
import ZfCore

struct GalleryView: View {
    @EnvironmentObject private var camera: CameraLink
    @EnvironmentObject private var imports: ImportStore
    @StateObject private var importer = Importer()

    @State private var filter: PhotoFilter = .all
    @State private var format: MediaFormat = .all
    @State private var card: Int64?
    @State private var selecting = false
    @State private var selected = Set<Int64>()
    @State private var previewing: PreviewTarget?

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 2), count: 3)

    var body: some View {
        let verified = imports.verifiedHandles(in: camera.catalog)
        let photos = PhotoCatalog.visible(camera.catalog, verified: verified, filter: filter, format: format, card: card)
        let sections = DayGroups.sections(photos)
        let slots = StorageCards.slots(camera.catalog)

        ScrollView {
            Text(CameraCatalogSummary.of(card == nil ? CopyGroups.unique(camera.catalog) : camera.catalog.filter { $0.storageId == card },
                                         scope: card == nil ? "相机" : StorageCards.label(slots, card)))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal)
            LazyVGrid(columns: columns, spacing: 2, pinnedViews: [.sectionHeaders]) {
                ForEach(sections) { section in
                    Section {
                        ForEach(section.indices, id: \.self) { index in
                            let photo = photos[index]
                            PhotoCell(photo: photo,
                                      image: camera.thumbnails[photo.handle],
                                      imported: verified.contains(photo.handle),
                                      selecting: selecting,
                                      selected: selected.contains(photo.handle))
                                .onAppear { camera.requestThumbnail(photo.handle) }
                                .onTapGesture { tap(photo, in: photos) }
                                .onLongPressGesture {
                                    selecting = true
                                    toggle(photo)
                                }
                        }
                    } header: {
                        DayHeader(day: section.day, count: section.count, selecting: selecting) {
                            let handles = section.indices.map { photos[$0] }.filter(\.canImport).map(\.handle)
                            if handles.allSatisfy(selected.contains) { selected.subtract(handles) } else { selected.formUnion(handles) }
                        }
                    }
                }
            }
            if photos.isEmpty {
                Text("没有符合条件的照片").foregroundStyle(.secondary).padding(.top, 40)
            }
        }
        .navigationTitle(camera.cameraName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Menu {
                    Picker("导入状态", selection: $filter) {
                        ForEach(PhotoFilter.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    Picker("格式", selection: $format) {
                        ForEach(MediaFormat.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    if slots.count > 1 {
                        Picker("存储卡", selection: $card) {
                            Text("全部").tag(Int64?.none)
                            ForEach(slots, id: \.self) { Text(StorageCards.label(slots, $0)).tag(Int64?.some($0)) }
                        }
                    }
                } label: { Image(systemName: "line.3.horizontal.decrease.circle") }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button(selecting ? "完成" : "选择") {
                    selecting.toggle()
                    if !selecting { selected.removeAll() }
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            if importer.isRunning || importer.message != nil {
                ImportBanner(importer: importer)
            } else if selecting {
                HStack {
                    Text("已选 \(selected.count) 张").font(.subheadline)
                    Spacer()
                    Button("导入原图") {
                        let chosen = photos.filter { selected.contains($0.handle) && $0.canImport }
                        selected.removeAll()
                        selecting = false
                        importer.run(chosen, camera: camera, store: imports)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(selected.isEmpty)
                }
                .padding()
                .background(.bar)
            }
        }
        .fullScreenCover(item: $previewing) { target in
            PreviewView(photos: target.photos, index: target.index, importer: importer)
        }
    }

    private func tap(_ photo: CameraObject, in photos: [CameraObject]) {
        if selecting {
            toggle(photo)
        } else if photo.canImport {
            let previewable = photos.filter(\.canImport)
            if let index = previewable.firstIndex(of: photo) {
                previewing = PreviewTarget(photos: previewable, index: index)
            }
        }
    }

    private func toggle(_ photo: CameraObject) {
        guard photo.canImport else { return }
        if selected.contains(photo.handle) { selected.remove(photo.handle) } else { selected.insert(photo.handle) }
    }
}

struct PreviewTarget: Identifiable {
    let photos: [CameraObject]
    let index: Int
    var id: Int64 { photos[index].handle }
}

private struct DayHeader: View {
    let day: String
    let count: Int
    let selecting: Bool
    let toggleAll: () -> Void

    var body: some View {
        HStack {
            Text(day).font(.subheadline.weight(.semibold))
            Text("\(count) 张").font(.caption).foregroundStyle(.secondary)
            Spacer()
            if selecting { Button("全选本日", action: toggleAll).font(.caption) }
        }
        .padding(.horizontal)
        .padding(.vertical, 6)
        .background(.bar)
    }
}

private struct PhotoCell: View {
    let photo: CameraObject
    let image: UIImage?
    let imported: Bool
    let selecting: Bool
    let selected: Bool

    var body: some View {
        Color(.secondarySystemBackground)
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    Image(systemName: "photo").foregroundStyle(.tertiary)
                }
            }
            .clipped()
            .overlay(alignment: .bottomLeading) {
                HStack(spacing: 4) {
                    if let format = MediaFormat.of(photo), format != .jpeg {
                        Text(format == .heif ? "HIF" : format.label)
                    }
                    if imported { Text("已导入") }
                }
                .font(.caption2.bold())
                .foregroundStyle(.white)
                .padding(3)
                .background(.black.opacity(0.45))
            }
            .overlay(alignment: .topTrailing) {
                if selecting && photo.canImport {
                    Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundStyle(selected ? Color.accentColor : Color.white)
                        .shadow(radius: 1)
                        .padding(4)
                }
            }
            .opacity(photo.canImport || !selecting ? 1 : 0.4)
            .contentShape(Rectangle())
    }
}
