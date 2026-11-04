import XCTest
@testable import ZfCore

final class DayGroupsTests: XCTestCase {
    private func shot(_ handle: Int64, _ date: String) -> CameraObject { obj(handle, "p\(handle).jpg", date) }

    func testFormatsTheCaptureDateAsADay() {
        XCTAssertEqual(DayGroups.dayOf("20261028T120000"), "2026-10-28")
        XCTAssertEqual(DayGroups.dayOf("20261028"), "2026-10-28")
    }
    func testMissingOrBrokenDatesFallBackToUnknown() {
        XCTAssertEqual(DayGroups.dayOf(""), DayGroups.unknownDay)
        XCTAssertEqual(DayGroups.dayOf("2026"), DayGroups.unknownDay)
        XCTAssertEqual(DayGroups.dayOf("2026-10-2"), DayGroups.unknownDay)
    }
    func testNoPhotosMeansNoSections() {
        XCTAssertTrue(DayGroups.sections([]).isEmpty)
    }
    func testConsecutivePhotosOfOneDayFormOneSection() {
        let photos = [shot(1, "20261028T1"), shot(2, "20261028T2"), shot(3, "20261028T3"), shot(4, "20261028T4"),
                      shot(5, "20261027T1"), shot(6, "20261027T2")]
        XCTAssertEqual(DayGroups.sections(photos), [
            DayGroups.Section(day: "2026-10-28", first: 0, count: 4),
            DayGroups.Section(day: "2026-10-27", first: 4, count: 2),
        ])
    }
    func testTheSameDayAppearingAgainLaterGetsItsOwnSection() {
        let sections = DayGroups.sections([shot(1, "20261028"), shot(2, "20261027"), shot(3, "20261028")])
        XCTAssertEqual(sections.count, 3)
        XCTAssertEqual(Set(sections.map(\.id)).count, 3)
    }
    func testASectionOwnsExactlyThePhotosOfItsDay() {
        let photos = [shot(1, "20261028"), shot(2, "20261028"), shot(3, "20261027")]
        let first = DayGroups.sections(photos)[0]
        XCTAssertEqual(first.indices.map { photos[$0].handle }, [1, 2])
    }

    func testDraggingAddsARangeAndCanBeReversed() {
        let selection = RangeSelection(initial: [9], anchor: 2)
        XCTAssertEqual(selection.at(4), [2, 3, 4, 9])
        XCTAssertEqual(selection.at(0), [0, 1, 2, 9])
        XCTAssertEqual(selection.at(2), [2, 9])
    }
    func testDraggingFromASelectedPhotoRemovesTheRange() {
        let selection = RangeSelection(initial: [1, 2, 3, 4], anchor: 2)
        XCTAssertEqual(selection.at(3), [1, 4])
    }
}
