import Foundation
#if canImport(XCTest)
import XCTest
#else
// Windows Swift ships without XCTest. These adapters run the same assertions
// when this file and SafetyPolicy.swift are compiled as a standalone executable.
private func XCTAssertTrue(_ value: Bool, file: StaticString = #file, line: UInt = #line) { precondition(value, "Expected true", file: file, line: line) }
private func XCTAssertFalse(_ value: Bool, file: StaticString = #file, line: UInt = #line) { precondition(!value, "Expected false", file: file, line: line) }
private func XCTAssertEqual<T: Equatable>(_ left: T, _ right: T, file: StaticString = #file, line: UInt = #line) { precondition(left == right, "Values differ", file: file, line: line) }
class XCTestCase {}
#endif
#if canImport(Security4Her)
@testable import Security4Her
#elseif canImport(SafetyPolicies)
@testable import SafetyPolicies
#endif

final class SafetyPolicyTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    func testExtensionDoesNotShortenFutureDeadline() {
        XCTAssertEqual(SafetyPolicy.extendedDeadline(now.addingTimeInterval(1800), now: now, minutes: 10), now.addingTimeInterval(2400))
        XCTAssertEqual(SafetyPolicy.extendedDeadline(now.addingTimeInterval(-60), now: now, minutes: 10), now.addingTimeInterval(600))
    }
    func testArrivalRequiresEntireAccurateCircleAndSeparatedFixes() {
        XCTAssertFalse(SafetyPolicy.arrivalCandidate(distance: 140, accuracy: 20, at: now, now: now))
        XCTAssertFalse(SafetyPolicy.arrivalCandidate(distance: 0, accuracy: 51, at: now, now: now))
        XCTAssertFalse(SafetyPolicy.arrivalCandidate(distance: 10, accuracy: 5, at: now.addingTimeInterval(-121), now: now))
        XCTAssertFalse(SafetyPolicy.arrivalCandidate(distance: 10, accuracy: 5, at: now.addingTimeInterval(60), now: now))
        XCTAssertTrue(SafetyPolicy.arrivalCandidate(distance: 100, accuracy: 50, at: now, now: now))
        XCTAssertFalse(SafetyPolicy.confirmsArrival(first: nil, at: now))
        XCTAssertFalse(SafetyPolicy.confirmsArrival(first: now, at: now.addingTimeInterval(9)))
        XCTAssertFalse(SafetyPolicy.confirmsArrival(first: now, at: now.addingTimeInterval(121)))
        XCTAssertTrue(SafetyPolicy.confirmsArrival(first: now, at: now.addingTimeInterval(10)))
    }
    func testCancellationAndNewerMutationRejectLateReplies() {
        XCTAssertFalse(SafetyPolicy.acceptsJourneyResponse(id: "a", revision: 0, state: "active", currentID: "a", currentRevision: 1, currentState: "ended"))
        XCTAssertFalse(SafetyPolicy.acceptsJourneyResponse(id: "a", revision: 1, state: "active", currentID: "a", currentRevision: 2, currentState: "active"))
        XCTAssertTrue(SafetyPolicy.acceptsJourneyResponse(id: "a", revision: 2, state: "active", currentID: "a", currentRevision: 2, currentState: "active"))
    }
    func testResolvedSOSCannotBeResurrected() {
        XCTAssertFalse(SafetyPolicy.shouldRestoreIncident(id: "a", serverState: "active", localID: "a", localState: "resolved"))
        XCTAssertFalse(SafetyPolicy.shouldRestoreIncident(id: "a", serverState: "resolved", localID: nil, localState: nil))
        XCTAssertTrue(SafetyPolicy.shouldRestoreIncident(id: "a", serverState: "active", localID: nil, localState: nil))
    }
}

#if !canImport(XCTest)
@main
enum PolicyChecks {
    static func main() {
        let checks = SafetyPolicyTests()
        checks.testExtensionDoesNotShortenFutureDeadline()
        checks.testArrivalRequiresEntireAccurateCircleAndSeparatedFixes()
        checks.testCancellationAndNewerMutationRejectLateReplies()
        checks.testResolvedSOSCannotBeResurrected()
        print("4 safety policy regression tests passed")
    }
}
#endif
