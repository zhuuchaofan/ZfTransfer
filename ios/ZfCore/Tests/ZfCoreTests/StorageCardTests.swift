import XCTest
@testable import ZfCore

final class StorageCardTests: XCTestCase {
    private func card(_ handle: Int64, _ name: String, _ date: String, _ storage: Int64, size: Int64 = 500, format: Int = 0x3801) -> CameraObject {
        obj(handle, name, date, storage: storage, format: format, size: size)
    }
    private lazy var a1 = card(10, "a.JPG", "20261029T100000", slot1)
    private lazy var a2 = card(300, "a.JPG", "20261029T100000", slot2)
    private lazy var b1 = card(11, "b.JPG", "20261028T100000", slot1)
    private lazy var c2 = card(301, "c.JPG", "20261027T100000", slot2)

    func testArrangingKeepsEveryCopyOnEveryCard() {
        XCTAssertEqual(CameraCatalog.arrange([a1, a2, b1, c2]).count, 4)
    }
    func testAShotWrittenToBothCardsIsOnePhoto() {
        XCTAssertEqual(CopyGroups.unique([a2, a1]), [a1])
    }
    func testTheCopyThatIsAlreadyImportedIsTheOneKept() {
        XCTAssertEqual(CopyGroups.unique([a1, a2]) { $0.storageId == slot2 }, [a2])
    }
    func testTheSecondCopyIsWrittenAMomentLaterAndStillIsTheSamePhoto() {
        let first = card(1, "ZCF_3901.NEF", "20261031T181228", slot1, size: 9_665_536)
        let second = card(2, "ZCF_3901.NEF", "20261031T181229", slot2, size: 9_665_536)
        XCTAssertEqual(CopyGroups.unique([second, first]), [first])
        XCTAssertEqual(CopyGroups.copiesOf([first, second], handle: 1), [1, 2])
        XCTAssertTrue(CopyGroups.nearMisses([first, second]).isEmpty)
    }
    func testASecondCopyAcrossAMinuteBoundaryIsStillMerged() {
        let first = card(1, "a.JPG", "20261031T181259", slot1)
        let second = card(2, "a.JPG", "20261031T181300", slot2)
        XCTAssertEqual(CopyGroups.unique([first, second]).count, 1)
    }
    func testASecondCopyAcrossMidnightAndMonthEndIsStillMerged() {
        let first = card(1, "a.JPG", "20261031T235959", slot1)
        let second = card(2, "a.JPG", "20261101T000000", slot2)
        XCTAssertEqual(CopyGroups.unique([first, second]).count, 1)
    }
    func testTimesMoreThanAFewSecondsApartAreDifferentPhotos() {
        let first = card(1, "a.JPG", "20261031T181200", slot1)
        let second = card(2, "a.JPG", "20261031T181205", slot2)
        XCTAssertEqual(CopyGroups.unique([first, second]).count, 2)
    }
    func testAnImpossibleDateIsComparedAsText() {
        let first = card(1, "a.JPG", "20260230T100000", slot1)
        let second = card(2, "a.JPG", "20260230T100001", slot2)
        XCTAssertEqual(CopyGroups.unique([first, second]).count, 2)
    }
    func testSameNameButDifferentTimeOrSizeAreDifferentPhotos() {
        let otherTime = card(2, "a.JPG", "20261030T100000", slot2)
        let otherSize = card(3, "a.JPG", "20261029T100000", slot2, size: 600)
        XCTAssertEqual(CopyGroups.unique([a1, otherTime, otherSize]).count, 3)
    }
    func testTwoCopiesOfAVideoAreOneToo() {
        let v1 = card(1, "v.MOV", "20261029T100000", slot1, size: 9_000, format: 0x300D)
        let v2 = card(2, "v.MOV", "20261029T100000", slot2, size: 9_000, format: 0x300D)
        XCTAssertEqual(CopyGroups.unique([v1, v2]).count, 1)
    }
    func testIdenticalLookingFilesOnOneCardStayDistinct() {
        let x = card(1, "same.JPG", "date", slot1)
        let y = card(2, "same.JPG", "date", slot1)
        XCTAssertEqual(CopyGroups.unique([x, y]).count, 2)
        XCTAssertEqual(CopyGroups.importedWithCopies([x, y]) { $0.handle == 2 }, [2])
        XCTAssertEqual(CopyGroups.copiesOf([x, y], handle: 1), [1])
    }
    func testAnAmbiguousGroupIsNeverMerged() {
        let x = card(1, "same.JPG", "date", slot1)
        let y = card(2, "same.JPG", "date", slot1)
        let z = card(3, "same.JPG", "date", slot2)
        XCTAssertEqual(CopyGroups.unique([x, y, z]).count, 3)
    }
    func testASameNameFileThatDiffersBetweenTheCardsIsReported() {
        let identical1 = card(1, "same.JPG", "20261029T100000", slot1)
        let identical2 = card(2, "same.JPG", "20261029T100000", slot2)
        let differs1 = card(3, "odd.NEF", "20261029T101500", slot1, size: 800)
        let differs2 = card(4, "odd.NEF", "20261029T111500", slot2, size: 800)
        let reported = CopyGroups.nearMisses([identical1, identical2, differs1, differs2])
        XCTAssertEqual(reported.count, 1)
        XCTAssertEqual(Set(reported[0].map(\.handle)), [3, 4])
    }
    func testFilesOnOneCardOnlyAreNeverReported() {
        XCTAssertTrue(CopyGroups.nearMisses([card(1, "a.JPG", "d1", slot1), card(2, "a.JPG", "d2", slot1)]).isEmpty)
    }
    func testImportedOnOneCardMeansImportedOnBoth() {
        XCTAssertEqual(CopyGroups.importedWithCopies([a1, a2, b1, c2]) { $0.handle == 10 }, [10, 300])
    }
    func testNothingImportedMeansNoHandles() {
        XCTAssertTrue(CopyGroups.importedWithCopies([a1, a2]) { _ in false }.isEmpty)
    }

    func testCardsAreNumberedByStorageIdNoMatterTheListOrder() {
        let slots = StorageCards.slots([c2, a2, a1, b1])
        XCTAssertEqual(slots, [slot1, slot2])
        XCTAssertEqual(StorageCards.label(slots, slot1), "卡 1")
        XCTAssertEqual(StorageCards.label(slots, slot2), "卡 2")
        XCTAssertEqual(StorageCards.label(slots, nil), "全部")
    }
    func testOneCardMeansNoChoice() {
        XCTAssertEqual(StorageCards.slots([a1, b1]).count, 1)
    }

    func testAllCardsShowsEachPhotoOnce() {
        let shown = PhotoCatalog.visible([a1, a2, b1, c2], verified: [], filter: .all, format: .all, card: nil)
        XCTAssertEqual(shown.map(\.filename), ["a.JPG", "b.JPG", "c.JPG"])
    }
    func testOneCardShowsOnlyThatCardsFilesIncludingTheOnesTheOtherCardLacks() {
        let all = [a1, a2, b1, c2]
        XCTAssertEqual(PhotoCatalog.visible(all, verified: [], filter: .all, format: .all, card: slot2), [a2, c2])
        XCTAssertEqual(PhotoCatalog.visible(all, verified: [], filter: .all, format: .all, card: slot1), [a1, b1])
    }
    func testTheImportFilterWorksPerCard() {
        let all = [a1, a2, b1, c2]
        let imported: Set<Int64> = [10, 300]
        XCTAssertEqual(PhotoCatalog.visible(all, verified: imported, filter: .notImported, format: .all, card: slot1), [b1])
        XCTAssertEqual(PhotoCatalog.visible(all, verified: imported, filter: .notImported, format: .all, card: slot2), [c2])
    }
    func testAPreviewKeepsToTheCardItWasOpenedOn() {
        let appended = PhotoCatalog.appendPreview([], loaded: [a1, a2, c2], verified: [], filter: .all, format: .all, card: slot2)
        XCTAssertEqual(appended, [a2, c2])
    }
    func testTheSummaryNamesTheCardWhenOneIsChosen() {
        XCTAssertEqual(CameraCatalogSummary.of([a2, c2], scope: "卡 2"), "卡 2共 2 张 JPEG")
        XCTAssertEqual(CameraCatalogSummary.of([a1, b1]), "相机共 2 张 JPEG")
    }
}
