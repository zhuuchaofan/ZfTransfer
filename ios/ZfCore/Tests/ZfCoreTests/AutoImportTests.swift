import XCTest
@testable import ZfCore

final class AutoImportTests: XCTestCase {
    private func shot(_ handle: Int64, _ name: String, _ date: String, storage: Int64 = slot1, format: Int = 0x3801) -> CameraObject {
        obj(handle, name, date, storage: storage, format: format, size: 500)
    }
    private let never: (CameraObject) -> Bool = { _ in false }
    private func baseline(_ photos: CameraObject...) -> Set<AutoImport.Key> { AutoImport.baselineOf(photos) }

    func testOnlyPhotosNotInTheBaselineAreTaken() {
        let old = shot(1, "old.JPG", "20261029T100000")
        let fresh = shot(2, "new.JPG", "20261031T100000")
        XCTAssertEqual(AutoImport.pending([fresh, old], baseline: baseline(old), isImported: never), [fresh])
    }
    func testPhotosComeOutInShootingOrder() {
        let first = shot(2, "a.JPG", "20261031T100000")
        let second = shot(3, "b.JPG", "20261031T100005")
        XCTAssertEqual(AutoImport.pending([second, first], baseline: baseline(), isImported: never), [first, second])
    }
    func testRawAndVideoAreLeftAlone() {
        let raw = shot(2, "a.NEF", "20261031T100000", format: 0x3000)
        let video = shot(3, "a.MOV", "20261031T100001", format: 0x300D)
        XCTAssertTrue(AutoImport.pending([raw, video], baseline: baseline(), isImported: never).isEmpty)
    }
    func testABackedUpPhotoIsTakenOnce() {
        let one = shot(2, "a.JPG", "20261031T100000", storage: slot1)
        let two = shot(3, "a.JPG", "20261031T100001", storage: slot2)
        XCTAssertEqual(AutoImport.pending([two, one], baseline: baseline(), isImported: never), [one])
    }
    func testAnAlreadyImportedPhotoIsNotTakenAgain() {
        let one = shot(2, "a.JPG", "20261031T100000", storage: slot1)
        let two = shot(3, "a.JPG", "20261031T100001", storage: slot2)
        XCTAssertTrue(AutoImport.pending([two, one], baseline: baseline()) { $0.handle == 3 }.isEmpty)
    }
    func testAPhotoThatFailedIsOfferedAgain() {
        let fresh = shot(2, "a.JPG", "20261031T100000")
        XCTAssertEqual(AutoImport.pending([fresh], baseline: baseline(), isImported: never), [fresh])
        XCTAssertEqual(AutoImport.pending([fresh], baseline: baseline(), isImported: never), [fresh])
    }
    func testPhotosTakenWhileDisconnectedAreFoundAfterReconnecting() {
        let before = shot(1, "a.JPG", "20261031T100000")
        let kept = baseline(before)
        let whileAway = shot(2, "b.JPG", "20261031T100100")
        XCTAssertEqual(AutoImport.pending([whileAway, before], baseline: kept, isImported: never), [whileAway])
    }
    func testRenumberedHandlesDoNotMakeTheWholeCardLookNew() {
        let before = shot(1, "a.JPG", "20261031T100000")
        let kept = baseline(before)
        var renumbered = before
        renumbered.handle = 9001
        XCTAssertTrue(AutoImport.pending([renumbered], baseline: kept, isImported: never).isEmpty)
    }
    func testTheSameNameOnAnotherDayOrCardIsANewPhoto() {
        let before = shot(1, "a.JPG", "20261031T100000", storage: slot1)
        let kept = baseline(before)
        let laterSameName = shot(2, "a.JPG", "20261101T100000", storage: slot1)
        XCTAssertEqual(AutoImport.pending([laterSameName, before], baseline: kept, isImported: never), [laterSameName])
    }
    func testTheCatchUpLimitIsAFewDozenAtLeast() {
        XCTAssertTrue((50...500).contains(AutoImport.catchUpLimit))
    }
}
