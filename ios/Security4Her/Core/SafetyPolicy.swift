import Foundation

/// Pure policies shared by the app and regression tests. Wall-clock checks are explicit.
enum SafetyPolicy {
    static func extendedDeadline(_ deadline: Date, now: Date, minutes: Int) -> Date {
        max(deadline, now).addingTimeInterval(Double(minutes) * 60)
    }

    static func fresh(at: Date, accuracy: Double, now: Date) -> Bool {
        accuracy.isFinite && accuracy >= 0 && at <= now.addingTimeInterval(5) && now.timeIntervalSince(at) < 120
    }

    static func arrivalCandidate(distance: Double, accuracy: Double, at: Date, now: Date) -> Bool {
        fresh(at: at, accuracy: accuracy, now: now) && accuracy <= 50 &&
            distance.isFinite && distance >= 0 && distance + accuracy <= 150
    }

    static func confirmsArrival(first: Date?, at: Date) -> Bool {
        guard let first else { return false }
        return at.timeIntervalSince(first) >= 10 && at.timeIntervalSince(first) < 120
    }

    static func acceptsJourneyResponse(id: String, revision: Int, state: String,
                                       currentID: String?, currentRevision: Int, currentState: String?) -> Bool {
        id == currentID && revision == currentRevision && state == currentState
    }

    static func shouldRestoreIncident(id: String, serverState: String?, localID: String?, localState: String?) -> Bool {
        serverState == "active" && !(id == localID && localState != "active") && localState != "active"
    }
}
