import ActivityKit
import Foundation

struct JourneyActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var status: String
        var deadline: Date?
        var acknowledgedAt: Date?
    }
    var journeyID: String
}
