import XCTest
@testable import ZfCore

final class FormatBrowseTests: XCTestCase {
    private func file(_ handle: Int64, _ name: String, _ format: Int) -> CameraObject {
        obj(handle, name, "20261028T120000", format: format, size: 1000)
    }
    private lazy var jpeg = file(1, "a.JPG", 0x3801)
    private lazy var raw = file(2, "b.NEF", 0x3000)
    private lazy var hif = file(3, "c.HIF", 0x3801)
    private lazy var video = file(4, "d.MOV", 0x300D)
    private lazy var unknown = file(5, "e.bin", 0x3000)

    func testOnlyJpegCanBeImported() {
        XCTAssertTrue(jpeg.canImport)
        XCTAssertFalse(raw.canImport)
        XCTAssertFalse(video.canImport)
        XCTAssertFalse(unknown.canImport)
    }
    func testAHifFileThatReportsTheJpegFormatCodeIsNotImportedAsJpeg() {
        XCTAssertFalse(hif.canImport)
        XCTAssertEqual(MediaFormat.of(hif), .heif)
    }
    func testWithoutAFormatCodeTheSuffixDecides() {
        XCTAssertEqual(MediaFormat.classify(filename: "DSC_0001.jpg"), .jpeg)
        XCTAssertEqual(MediaFormat.classify(filename: "DSC_0001.NEF"), .raw)
        XCTAssertNil(MediaFormat.classify(filename: "README"))
    }
    func testEveryKnownFormatIsBrowsableThroughItsFilter() {
        let all = [jpeg, raw, hif, video, unknown]
        XCTAssertEqual(PhotoCatalog.visible(all, verified: [], filter: .all, format: .jpeg), [jpeg])
        XCTAssertEqual(PhotoCatalog.visible(all, verified: [], filter: .all, format: .raw), [raw])
        XCTAssertEqual(PhotoCatalog.visible(all, verified: [], filter: .all, format: .video), [video])
        XCTAssertEqual(PhotoCatalog.visible(all, verified: [], filter: .all, format: .heif), [hif])
    }
    func testSummaryNamesOnlyJpegsWhenThatIsAllThereIs() {
        XCTAssertEqual(CameraCatalogSummary.of([jpeg, jpeg]), "相机共 2 张 JPEG")
        XCTAssertEqual(CameraCatalogSummary.of([]), "相机共 0 张 JPEG")
    }
    func testSummaryBreaksDownMixedFormatsAndSkipsEmptyOnes() {
        XCTAssertEqual(CameraCatalogSummary.of([jpeg, jpeg, raw, video]), "相机共 4 个：JPEG 2 · RAW 1 · 视频 1")
    }
    func testPreviewListsNeverContainFilesThatCannotBePreviewed() {
        XCTAssertEqual(PhotoCatalog.appendPreview([], loaded: [jpeg, raw, video], verified: [], filter: .all, format: .all), [jpeg])
    }
}
