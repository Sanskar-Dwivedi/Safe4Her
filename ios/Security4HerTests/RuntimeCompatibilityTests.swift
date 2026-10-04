import XCTest
@testable import Security4Her

final class RuntimeCompatibilityTests: XCTestCase {
    func testOldSettingsStillDecode() throws {
        let data = try JSONEncoder().encode(AppSettings())
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        json.removeValue(forKey: "alarmReminders")
        let restored = try JSONDecoder().decode(AppSettings.self, from: JSONSerialization.data(withJSONObject: json))
        XCTAssertTrue(restored.testMode)
        XCTAssertNil(restored.alarmReminders)
    }
    func testFractionalServerDeadline() throws {
        let response = try JSONDecoder().decode(SafetyResponse.self, from: Data("{\"deadline\":\"2026-10-03T12:00:00.123+00:00\"}".utf8))
        XCTAssertNotNil(response.deadlineDate)
    }
    func testExistingJourneyFileKeepsItsTerminalState() throws {
        let journey = JourneyState(name: "Home", lat: 28, lng: 77, deadline: Date(), state: "ended")
        let restored = try JSONDecoder().decode(JourneyState.self, from: JSONEncoder().encode(journey))
        XCTAssertEqual(restored.state, "ended")
        XCTAssertEqual(restored.mutationRevision, 0)
        XCTAssertNil(restored.pendingDeadline)
    }
}
