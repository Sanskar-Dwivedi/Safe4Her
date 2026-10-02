import Foundation
import CoreLocation

struct Profile: Codable, Identifiable {
    let id: String
    var displayName: String?
    var phone: String?
    var verifiedFemale: Bool
    var verificationMethod: String?
    enum CodingKeys: String, CodingKey {
        case id, phone
        case displayName = "display_name", verifiedFemale = "verified_female", verificationMethod = "verification_method"
    }
}

struct TrustedContact: Codable, Identifiable {
    var id = UUID().uuidString
    var name: String
    var phone: String
    var relation: String?
    var priority = 1
    var receivesSos = true
    var telegramChatId: Int64?
    var telegramLinkCode: String?
    enum CodingKeys: String, CodingKey {
        case id, name, phone, relation, priority
        case receivesSos = "receives_sos", telegramChatId = "telegram_chat_id", telegramLinkCode = "telegram_link_code"
    }
}

struct AppSettings: Codable {
    var testMode = true
    var testNumber = ""
    var callNumber = "112"
    var countdownSeconds = 5
    var audioEvidence = true
    var videoEvidence = true
    var theme = "black"
    var fakeCaller = "Mom"
    var fakeVoice = "Hi, where are you? I'm nearby. Come and meet me, I'll wait for you."
    var pocketGesture = false
    var onboardingComplete = false
}

struct Fix: Codable {
    let lat: Double
    let lng: Double
    let accuracy: Double
    let at: Date
    var coordinate: CLLocationCoordinate2D { .init(latitude: lat, longitude: lng) }
    var mapsURL: String { "https://maps.apple.com/?ll=\(lat),\(lng)" }
    var isFresh: Bool { accuracy >= 0 && abs(at.timeIntervalSinceNow) < 120 }
}

struct Incident: Codable, Identifiable {
    var id = UUID().uuidString
    var startedAt = Date()
    var trigger: String
    var test: Bool
    var state = "active"
    var shareURL: String?
    var serverAccepted = false
    var telegramAccepted = 0
    var telegramPending = 0
    var lastFix: Fix?
    var error: String?
    var endPending = false
}

struct JourneyState: Codable, Identifiable {
    var id = UUID().uuidString
    var name: String
    var lat: Double
    var lng: Double
    var deadline: Date
    var serverArmed = false
    var serverPending = false
    var state = "active"
    var incidentId = UUID().uuidString
    var lastDistance: Double?
    var anchor: Fix?
    var stillSince = Date()
    var checkUntil: Date?
    var checkReason: String?
    var endPending = false
}

struct ActivityEntry: Codable, Identifiable {
    var id = UUID().uuidString
    var at = Date()
    let message: String
}

struct SafePlace: Decodable, Identifiable {
    let id: Int64
    let name: String
    let category: String
    let lat: Double
    let lng: Double
    let phone: String?
}

struct DangerCell: Decodable, Identifiable {
    let lat: Double
    let lng: Double
    let score: Double
    let reports: Int
    var id: String { "\(lat),\(lng)" }
}

enum AppFailure: LocalizedError {
    case message(String)
    var errorDescription: String? { if case let .message(message) = self { return message }; return nil }
}

enum Disk {
    static var root: URL {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Security4Her", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var resourceURL = url
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try? resourceURL.setResourceValues(values)
        return url
    }
    static func read<T: Decodable>(_ name: String, as: T.Type = T.self) -> T? {
        guard let data = try? Data(contentsOf: root.appendingPathComponent(name)) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }
    static func write<T: Encodable>(_ value: T, to name: String) throws {
        try JSONEncoder().encode(value).write(to: root.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    static func remove(_ name: String) { try? FileManager.default.removeItem(at: root.appendingPathComponent(name)) }
}
