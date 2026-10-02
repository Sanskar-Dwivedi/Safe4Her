import Foundation
import Combine

struct CommunityReport: Codable, Identifiable {
    let id: String
    let kind: String
    let lat: Double
    let lng: Double
    let radiusM: Int
    let category: String
    let name: String?
    let note: String?
    let at: Int64
    let by: String
    var sample: Bool? = false
    var valid: Bool {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return ["danger", "safe_area", "safe_place"].contains(kind) && lat.isFinite && lng.isFinite &&
            (-90...90).contains(lat) && (-180...180).contains(lng) && (0...2000).contains(radiusM) &&
            (1...64).contains(id.count) && (1...64).contains(by.count) && (name?.count ?? 0) <= 60 &&
            (note?.count ?? 0) <= 200 && sample != true && at > now - 30 * 86400000 && at < now + 300000
    }
}
struct CommunityConfirmation: Codable { let reportId: String; let by: String; let at: Int64 }
struct CommunityEvent: Codable {
    let t: String
    var report: CommunityReport?
    var confirm: CommunityConfirmation?
}

@MainActor
final class CommunityFeed: ObservableObject {
    @Published var reports: [CommunityReport] = Disk.read("community-reports.json") ?? []
    @Published var confirmations: [CommunityConfirmation] = Disk.read("community-confirmations.json") ?? []
    private var queued: [CommunityEvent] = Disk.read("community-queue.json") ?? []
    private let author: String
    init() {
        author = Disk.read("community-author.json", as: String.self) ?? UUID().uuidString
        try? Disk.write(author, to: "community-author.json")
    }
    func sync() async throws {
        var remaining: [CommunityEvent] = []
        for event in queued { do { try await publish(event) } catch { remaining.append(event) } }
        queued = remaining; try Disk.write(queued, to: "community-queue.json")
        let (data, response) = try await URLSession.shared.data(for: URLRequest(url: URL(string: "https://ntfy.sh/s4h-community-v1/json?poll=1&since=all")!, timeoutInterval: 20))
        guard let http = response as? HTTPURLResponse, http.statusCode == 200, data.count <= 2_000_000 else { throw AppFailure.message("Community feed is unavailable or too large.") }
        for line in String(decoding: data, as: UTF8.self).split(separator: "\n") where line.utf8.count <= 4096 {
            guard let envelope = try? JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any],
                  let message = envelope["message"] as? String,
                  let event = try? JSONDecoder().decode(CommunityEvent.self, from: Data(message.utf8)) else { continue }
            merge(event)
        }
        reports = Array(Dictionary(grouping: reports.filter(\.valid), by: \.by).values.flatMap { $0.sorted { $0.at > $1.at }.prefix(10) }.sorted { $0.at > $1.at }.prefix(2000))
        try save()
    }
    private func merge(_ event: CommunityEvent) {
        if let report = event.report, report.valid, !reports.contains(where: { $0.id == report.id }) { reports.append(report) }
        if let confirm = event.confirm {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            if (1...64).contains(confirm.by.count), (1...64).contains(confirm.reportId.count), confirm.at > now - 30 * 86400000, confirm.at < now + 300000,
               !confirmations.contains(where: { $0.reportId == confirm.reportId && $0.by == confirm.by }) { confirmations.append(confirm) }
        }
    }
    private func save() throws {
        let since = Int64((Date().timeIntervalSince1970 - 30 * 86400) * 1000)
        confirmations = Array(confirmations.filter { $0.at > since }.suffix(5000))
        try Disk.write(reports, to: "community-reports.json"); try Disk.write(confirmations, to: "community-confirmations.json")
    }
    private func publish(_ event: CommunityEvent) async throws {
        let message = String(decoding: try JSONEncoder().encode(event), as: UTF8.self)
        var request = URLRequest(url: URL(string: "https://ntfy.sh")!, timeoutInterval: 15)
        request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["topic": "s4h-community-v1", "message": message, "priority": 1])
        let (_, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw AppFailure.message("Report is saved locally and queued for the next community refresh.") }
    }
    private func send(_ event: CommunityEvent) async throws {
        merge(event); try save()
        do { try await publish(event) }
        catch { queued.append(event); try Disk.write(queued, to: "community-queue.json"); throw error }
    }
    func add(kind: String, fix: Fix, name: String, note: String, category: String) async throws {
        guard reports.filter({ $0.by == author && $0.at > Int64((Date().timeIntervalSince1970 - 86400) * 1000) }).count < 10 else { throw AppFailure.message("You can share up to ten community reports per day.") }
        let report = CommunityReport(id: UUID().uuidString, kind: kind, lat: fix.lat, lng: fix.lng,
            radiusM: kind == "safe_place" ? 0 : 200, category: category, name: String(name.prefix(60)),
            note: String(note.prefix(200)), at: Int64(Date().timeIntervalSince1970 * 1000), by: author)
        try await send(.init(t: "report", report: report))
    }
    func confirm(_ report: CommunityReport) async throws {
        guard report.by != author, !confirmations.contains(where: { $0.reportId == report.id && $0.by == author }) else { return }
        try await send(.init(t: "confirm", confirm: .init(reportId: report.id, by: author, at: Int64(Date().timeIntervalSince1970 * 1000))))
    }
    func count(_ report: CommunityReport) -> Int { 1 + Set(confirmations.filter { $0.reportId == report.id && $0.by != report.by }.map(\.by)).count }
}
