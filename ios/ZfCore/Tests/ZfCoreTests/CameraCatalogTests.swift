import XCTest
@testable import ZfCore

final class CameraCatalogTests: XCTestCase {
    func testTwoCardsAreMergedNewestFirst() {
        let cardOne = [obj(10, "a.JPG", "20261029T100000", storage: 1), obj(9, "b.JPG", "20261020T100000", storage: 1), obj(8, "c.JPG", "20260410T100000", storage: 1)]
        let cardTwo = [obj(300, "d.JPG", "20261028T100000", storage: 2), obj(299, "e.JPG", "20260721T100000", storage: 2)]
        XCTAssertEqual(CameraCatalog.arrange(cardOne + cardTwo).map(\.filename), ["a.JPG", "d.JPG", "b.JPG", "e.JPG", "c.JPG"])
    }
    func testFoldersAndUnknownObjectsAreDropped() {
        let folder = obj(1, "DCIM", "20260101T000000", format: 0x3001)
        let other = obj(2, "x.bin", "20261029T100000", format: 0x3000)
        let photo = obj(3, "p.JPG", "20261029T100000")
        XCTAssertEqual(CameraCatalog.arrange([folder, other, photo]), [photo])
    }
    func testVideosAreKept() {
        let video = obj(4, "v.MOV", "20261029T110000", format: 0x300D)
        XCTAssertEqual(CameraCatalog.arrange([video]), [video])
    }
    func testPhotosWithTheSameSecondKeepTheNewerHandleFirst() {
        let a = obj(5, "a.JPG", "20261029T100000"), b = obj(6, "b.JPG", "20261029T100000")
        XCTAssertEqual(CameraCatalog.arrange([a, b]), [b, a])
    }
    func testPhotosWithoutADateGoLast() {
        let dated = obj(1, "a.JPG", "20260101T000000"), undated = obj(2, "b.JPG", "")
        XCTAssertEqual(CameraCatalog.arrange([undated, dated]), [dated, undated])
    }
    func testAnEmptyCameraGivesAnEmptyCatalog() {
        XCTAssertTrue(CameraCatalog.arrange([]).isEmpty)
    }

    func testAnAddedOrRemovedObjectAndAFinishedCaptureTriggerARefresh() {
        XCTAssertTrue(CameraEvents.changesCatalog(0x4002))
        XCTAssertTrue(CameraEvents.changesCatalog(0x4003))
        XCTAssertTrue(CameraEvents.changesCatalog(0x400D))
    }
    func testOtherEventsDoNot() {
        XCTAssertFalse(CameraEvents.changesCatalog(0x4004))
        XCTAssertFalse(CameraEvents.changesCatalog(0xC101))
        XCTAssertFalse(CameraEvents.changesCatalog(0))
    }

    private let order: [Int64] = Array(1...10)

    func testWhatIsOnScreenIsReadBeforeTheRest() {
        let batch = ThumbnailPlan.nextBatch(focus: [8, 9], order: order, from: 0, limit: 4) { _ in false }
        XCTAssertEqual(batch.handles, [8, 9, 1, 2])
        XCTAssertEqual(batch.next, 2)
    }
    func testThumbnailsAlreadyReadAreSkipped() {
        let have: Set<Int64> = [1, 2, 3]
        let batch = ThumbnailPlan.nextBatch(focus: [], order: order, from: 0, limit: 3) { have.contains($0) }
        XCTAssertEqual(batch.handles, [4, 5, 6])
        XCTAssertEqual(batch.next, 6)
    }
    func testAFocusedHandleIsNotReadTwice() {
        let batch = ThumbnailPlan.nextBatch(focus: [1, 2], order: order, from: 0, limit: 4) { _ in false }
        XCTAssertEqual(batch.handles, [1, 2, 3, 4])
    }
    func testAnEmptyBatchAtTheEndMeansEverythingIsRead() {
        let batch = ThumbnailPlan.nextBatch(focus: [3], order: order, from: 10, limit: 4) { _ in true }
        XCTAssertTrue(batch.handles.isEmpty)
        XCTAssertEqual(batch.next, order.count)
    }
}
