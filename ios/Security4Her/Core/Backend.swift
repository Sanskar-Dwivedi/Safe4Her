import Foundation

struct AppConfiguration {
    let baseURL: URL?
    let anonymousKey: String
    let trackingURL: String
    let liveURL: String
    static let current: AppConfiguration = {
        func value(_ key: String) -> String {
            let text = Bundle.main.object(forInfoDictionaryKey: key) as? String ?? ""
            return text.contains("$(") ? "" : text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let raw = value("S4HSupabaseURL")
        return .init(baseURL: raw.hasPrefix("https://") ? URL(string: raw) : nil,
                     anonymousKey: value("S4HSupabaseAnonKey"), trackingURL: value("S4HTrackingURL"),
                     liveURL: value("S4HLiveURL"))
    }()
    var configured: Bool { baseURL != nil && !anonymousKey.isEmpty }
}

struct AuthSession: Codable {
    let access_token: String
    let refresh_token: String
    let expires_in: Int
    let user: AuthUser
    var savedAt: Date? = Date()
    struct AuthUser: Codable { let id: String }
}

struct SafetyResponse: Decodable {
    var shareUrl: String?
    var accepted: Int?
    var pending: Int?
    var state: String?
    var schedulerReady: Bool?
    var deliveryReady: Bool?
    var eligibleRecipients: Int?
    var deadline: String?
    var revision: Int?
    var incidentState: String?
    var deadlineDate: Date? {
        guard let deadline else { return nil }
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return parser.date(from: deadline) ?? ISO8601DateFormatter().date(from: deadline)
    }
}

@MainActor
final class Backend {
    let config = AppConfiguration.current
    private(set) var session: AuthSession?
    private var refreshTask: Task<AuthSession, Error>?
    init() {
        if let data = SecureStore.read("session") { session = try? JSONDecoder().decode(AuthSession.self, from: data) }
    }
    private func keep(_ value: AuthSession) throws {
        var dated = value; dated.savedAt = Date()
        try SecureStore.write(JSONEncoder().encode(dated), account: "session")
        session = dated
    }
    func request(_ path: String, method: String = "GET", body: [String: Any]? = nil,
                 authenticated: Bool = true, retry: Bool = true) async throws -> Data {
        guard let base = config.baseURL, config.configured else { throw AppFailure.message("The iPhone build needs its Supabase configuration.") }
        if authenticated { try await refreshIfNeeded() }
        guard let directory = URL(string: base.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/"),
              let url = URL(string: path, relativeTo: directory) else { throw AppFailure.message("Invalid server address.") }
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.httpMethod = method
        request.setValue(config.anonymousKey, forHTTPHeaderField: "apikey")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if authenticated {
            guard let session else { throw AppFailure.message("Sign in again to connect to the server.") }
            request.setValue("Bearer \(session.access_token)", forHTTPHeaderField: "Authorization")
        }
        if let body { request.httpBody = try JSONSerialization.data(withJSONObject: body) }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw AppFailure.message("Invalid server response.") }
        if http.statusCode == 401 && authenticated && retry {
            try await refreshIfNeeded(force: true)
            return try await self.request(path, method: method, body: body, authenticated: true, retry: false)
        }
        guard (200..<300).contains(http.statusCode) else {
            let error = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            let message = error?["msg"] as? String ?? error?["message"] as? String ?? error?["error_description"] as? String ?? error?["error"] as? String
            throw AppFailure.message(message ?? "Server request failed (\(http.statusCode)).")
        }
        return data
    }
    func refreshIfNeeded(force: Bool = false) async throws {
        guard let current = session else { throw AppFailure.message("Not signed in.") }
        if !force, let at = current.savedAt, Date().timeIntervalSince(at) < Double(current.expires_in - 90) { return }
        if let pending = refreshTask { _ = try await pending.value; return }
        let task = Task<AuthSession, Error> {
            let data = try await self.request("auth/v1/token?grant_type=refresh_token", method: "POST",
                                              body: ["refresh_token": current.refresh_token], authenticated: false)
            let refreshed = try JSONDecoder().decode(AuthSession.self, from: data)
            try self.keep(refreshed)
            return refreshed
        }
        refreshTask = task
        defer { refreshTask = nil }
        _ = try await task.value
    }
    func signIn(email: String, password: String) async throws -> Profile {
        let data = try await request("auth/v1/token?grant_type=password", method: "POST",
                                     body: ["email": email.trimmingCharacters(in: .whitespacesAndNewlines), "password": password], authenticated: false)
        try keep(JSONDecoder().decode(AuthSession.self, from: data))
        return try await profile()
    }
    func signUp(name: String, email: String, password: String) async throws -> Profile {
        let data = try await request("auth/v1/signup", method: "POST", body: ["email": email, "password": password, "data": ["display_name": name]], authenticated: false)
        guard let session = try? JSONDecoder().decode(AuthSession.self, from: data) else { throw AppFailure.message("Check your email to confirm your account, then sign in.") }
        try keep(session)
        return try await profile()
    }
    func profile() async throws -> Profile {
        guard let id = session?.user.id else { throw AppFailure.message("Not signed in.") }
        let rows = try JSONDecoder().decode([Profile].self, from: await request("rest/v1/profiles?id=eq.\(id)&select=*"))
        guard let profile = rows.first else { throw AppFailure.message("Your profile is not available yet.") }
        return profile
    }
    func signOut() async {
        _ = try? await request("auth/v1/logout", method: "POST")
        SecureStore.remove("session"); session = nil
    }
    func clearLocalSession() { SecureStore.remove("session"); session = nil }
    func contacts() async throws -> [TrustedContact] {
        try JSONDecoder().decode([TrustedContact].self, from: await request("rest/v1/trusted_contacts?select=*&order=priority.asc"))
    }
    func saveContact(_ contact: TrustedContact, isNew: Bool) async throws {
        let body: [String: Any] = ["name": contact.name, "phone": contact.phone, "relation": contact.relation ?? "", "priority": contact.priority, "receives_sos": contact.receivesSos]
        _ = try await request("rest/v1/trusted_contacts" + (isNew ? "" : "?id=eq.\(contact.id)"), method: isNew ? "POST" : "PATCH", body: body)
    }
    func deleteContact(_ id: String) async throws { _ = try await request("rest/v1/trusted_contacts?id=eq.\(id)", method: "DELETE") }
    func rpc(_ name: String, _ body: [String: Any] = [:]) async throws -> Data {
        try await request("rest/v1/rpc/\(name)", method: "POST", body: body)
    }
    func safety(_ action: String, _ values: [String: Any] = [:]) async throws -> SafetyResponse {
        var body = values; body["action"] = action
        return try JSONDecoder().decode(SafetyResponse.self, from: await request("functions/v1/ios-safety", method: "POST", body: body))
    }
    func verificationURL() async throws -> URL {
        try await refreshIfNeeded()
        guard let base = config.baseURL, let token = session?.access_token else { throw AppFailure.message("Sign in again.") }
        var components = URLComponents(url: base.appendingPathComponent("functions/v1/digilocker-start"), resolvingAgainstBaseURL: false)!
        components.queryItems = [.init(name: "access_token", value: token)]
        return components.url!
    }
}
