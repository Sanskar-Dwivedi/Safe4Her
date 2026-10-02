import Foundation
import SwiftUI
import MapKit
import Combine
import AuthenticationServices
import AVFoundation

@MainActor
final class AppModel: NSObject, ObservableObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = AppModel()
    @Published var settings: AppSettings = Disk.read("settings.json") ?? AppSettings() { didSet { persist(settings, "settings.json") } }
    @Published var profile: Profile?
    @Published var contacts: [TrustedContact] = []
    @Published var incident: Incident? = Disk.read("incident.json")
    @Published var journey: JourneyState? = Disk.read("journey.json")
    @Published var activity: [ActivityEntry] = Disk.read("activity.json") ?? []
    @Published var countdown: Int?
    @Published var loading = true
    @Published var busy = false
    @Published var error: String?
    @Published var demo = false
    @Published var liveTopic: String?
    @Published var liveUntil: Date?
    @Published var liveStatus = "Not sharing"
    @Published var selectedTab = 0
    @Published var messageDraft: MessageDraft?
    let backend = Backend()
    let location = LocationService()
    let evidence = EvidenceStore()
    let deterrents = Deterrents()
    private var countdownTask: Task<Void, Never>?
    private var timer: Timer?
    private var syncBusy = false
    private var lastSync = Date.distantPast
    private var lastLive = Date.distantPast
    private var authSession: ASWebAuthenticationSession?
    private var observers = Set<AnyCancellable>()
    private var launchTask: Task<Void, Never>?
    private var startInProgress = false
    private var livePublishing = false
    override private init() {
        super.init()
        location.onFix = { [weak self] fix in self?.received(fix) }
        evidence.location = { [weak self] in self?.location.fix }
        deterrents.onKnock = { [weak self] in self?.beginCountdown(trigger: "pocket") }
        Notifications.shared.configure()
        Notifications.shared.onAction = { [weak self] action in
            guard let self else { return }
            if action == "fake-call" { self.deterrents.ring() }
            if action == "journey" { self.selectedTab = 1 }
        }
        for publisher in [location.objectWillChange, evidence.objectWillChange, deterrents.objectWillChange] {
            publisher.sink { [weak self] in self?.objectWillChange.send() }.store(in: &observers)
        }
    }
    func launch() async {
        if let launchTask { await launchTask.value; return }
        let task = Task { await initializeSession() }
        launchTask = task
        await task.value
    }
    private func initializeSession() async {
        // Keychain credentials may survive uninstall. A new installation requires a fresh sign-in.
        if !UserDefaults.standard.bool(forKey: "installationInitialized") {
            backend.clearLocalSession(); SecureStore.remove("safety-pin")
            UserDefaults.standard.set(true, forKey: "installationInitialized")
        }
        if backend.config.configured, backend.session != nil {
            if let cached: Profile = Disk.read("profile.json"), cached.id == backend.session?.user.id {
                profile = cached
                contacts = Disk.read("contacts-\(cached.id).json") ?? []
            }
            do {
                profile = try await backend.profile()
                if let profile { persist(profile, "profile.json") }
                if profile?.verifiedFemale == true { try await loadContacts() }
            } catch { log("Server connection unavailable. Saved local setup remains available; remote actions will retry.") }
        }
        loading = false
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in Task { @MainActor in self?.tick() } }
        if incident?.state == "active" { location.start("sos"); log("SOS restored. Recording does not silently restart after termination.") }
        if journey?.state == "active" { location.start("journey") }
        Task { await synchronize() }
    }
    private func persist<T: Encodable>(_ value: T, _ name: String) {
        do { try Disk.write(value, to: name) } catch { self.error = "Could not save local state: \(error.localizedDescription)" }
    }
    func log(_ text: String) {
        activity.insert(.init(message: text), at: 0)
        activity = Array(activity.prefix(500)); persist(activity, "activity.json")
    }
    func report(_ error: Error) { self.error = error.localizedDescription; log(error.localizedDescription) }
    func authenticate(name: String, email: String, password: String, creating: Bool) async {
        busy = true; defer { busy = false }
        do {
            profile = try await (creating ? backend.signUp(name: name, email: email, password: password) : backend.signIn(email: email, password: password))
            demo = false
            if let profile { persist(profile, "profile.json") }
            if profile?.verifiedFemale == true { try await loadContacts() }
        } catch { report(error) }
    }
    func enterDemo() {
        guard incident?.state != "active", journey?.state != "active" else { error = "Resolve the active safety session before changing accounts."; return }
        demo = true; settings.testMode = true
        profile = Profile(id: "demo", displayName: "Demo", verifiedFemale: true)
        contacts = []; loading = false
    }
    func verifyIdentity() async {
        do {
            let url = try await backend.verificationURL()
            authSession = ASWebAuthenticationSession(url: url, callbackURLScheme: "security4her") { [weak self] url, error in
                Task { @MainActor in
                    guard let self else { return }
                    if let error { self.report(error); return }
                    guard let url, let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
                          components.queryItems?.first(where: { $0.name == "ok" })?.value == "1" else {
                        self.error = "Verification did not complete. The deployment must have DigiLocker configured."; return
                    }
                    do {
                        self.profile = try await self.backend.profile()
                        if let profile = self.profile { self.persist(profile, "profile.json") }
                        try await self.loadContacts()
                    }
                    catch { self.report(error) }
                }
            }
            authSession?.presentationContextProvider = self
            authSession?.prefersEphemeralWebBrowserSession = true
            if authSession?.start() != true { error = "Could not open identity verification." }
        } catch { report(error) }
    }
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }
    func loadContacts() async throws {
        if demo { return }
        contacts = try await backend.contacts()
        if let profile { persist(contacts, "contacts-\(profile.id).json"); persist(profile, "profile.json") }
    }
    func saveContact(_ contact: TrustedContact, isNew: Bool) async {
        guard !contact.name.trimmingCharacters(in: .whitespaces).isEmpty, !contact.phone.isEmpty else { error = "Enter a name and phone number."; return }
        do {
            if demo { contacts.removeAll { $0.id == contact.id }; contacts.append(contact) }
            else { try await backend.saveContact(contact, isNew: isNew); try await loadContacts() }
        } catch { report(error) }
    }
    func deleteContact(_ contact: TrustedContact) async {
        do {
            if !demo { try await backend.deleteContact(contact.id) }
            contacts.removeAll { $0.id == contact.id }
        } catch { report(error) }
    }
    func signOut() async {
        guard incident?.state != "active", incident?.endPending != true, journey?.state != "active", journey?.endPending != true else {
            error = "End and synchronize active safety sessions before signing out."; return
        }
        if !demo { await backend.signOut() }
        Disk.remove("profile.json")
        profile = nil; contacts = []; demo = false; incident = nil; journey = nil
        Disk.remove("incident.json"); Disk.remove("journey.json"); stopLive()
    }
    func beginCountdown(trigger: String = "button") {
        guard profile?.verifiedFemale == true else { error = "Sign in and complete setup before starting an SOS."; return }
        guard incident?.state != "active", countdown == nil, !startInProgress else { return }
        guard incident?.endPending != true else { error = "The previous SOS is still waiting to end on the server. Retry synchronization."; return }
        countdown = settings.countdownSeconds
        countdownTask = Task {
            for value in stride(from: settings.countdownSeconds, through: 1, by: -1) {
                if Task.isCancelled { return }
                countdown = value
                UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
            if Task.isCancelled { return }
            countdown = nil
            await startSOS(trigger: trigger)
        }
    }
    func cancelCountdown() { countdownTask?.cancel(); countdownTask = nil; countdown = nil }
    func startSOS(trigger: String) async {
        guard incident?.state != "active", !startInProgress else { return }
        startInProgress = true; defer { startInProgress = false }
        let current = Incident(trigger: trigger, test: settings.testMode || demo, lastFix: location.fix)
        incident = current; persist(current, "incident.json")
        location.start("sos"); log(current.test ? "Test SOS started; automatic contact delivery is disabled." : "SOS started; connecting to Telegram alert service.")
        Task { await synchronize() }
        if UIApplication.shared.applicationState == .active {
            await evidence.start(id: current.id, ownerId: profile?.id ?? "demo", audio: settings.audioEvidence, video: settings.videoEvidence)
        } else { log("Open the app to start evidence recording. SOS delivery is independent of recording.") }
    }
    func endSOS(pin: String) async {
        guard SecureStore.acceptsPIN(pin) else { error = "Incorrect safety PIN."; return }
        guard var current = incident else { return }
        evidence.stop(); location.stop("sos")
        current.state = "resolved"; current.endPending = !demo; incident = current; persist(current, "incident.json")
        log("SOS ended locally. Synchronizing the server status.")
        await synchronize()
    }
    private func received(_ fix: Fix) {
        if incident?.state == "active" { incident?.lastFix = fix; if let incident { persist(incident, "incident.json") } }
        evaluateJourney(fix)
        if Date().timeIntervalSince(lastSync) > 20 { Task { await synchronize() } }
        publishLiveIfNeeded()
    }
    private func tick() {
        if let current = incident, current.state == "active", Date().timeIntervalSince(current.startedAt) >= 3600, evidence.recordingId != nil {
            evidence.stop(); log("Evidence recording reached its one-hour limit. SOS remains active.")
        }
        if Date().timeIntervalSince(lastSync) > 20 { Task { await synchronize() } }
        if let journey, journey.state == "active", !journey.endPending {
            if let until = journey.checkUntil, until <= Date(), !journey.serverArmed, !journey.serverPending { Task { await overdueJourney() } }
            else if journey.deadline <= Date(), !journey.serverArmed, !journey.serverPending { Task { await overdueJourney() } }
        }
        if let end = liveUntil, end <= Date() { stopLive() }
        publishLiveIfNeeded()
    }
    func synchronize() async {
        guard !syncBusy, profile?.verifiedFemale == true else { return }
        lastSync = Date()
        guard !demo else { return }
        syncBusy = true; defer { syncBusy = false }
        if let current = incident, current.state == "active" || current.endPending {
            do {
                var body: [String: Any] = ["incidentId": current.id, "trigger": current.trigger, "test": current.test]
                if let fix = current.lastFix { body["lat"] = fix.lat; body["lng"] = fix.lng; body["accuracyM"] = fix.accuracy; body["fixAt"] = ISO8601DateFormatter().string(from: fix.at) }
                let response = try await backend.safety(current.endPending ? "end" : "sos", body)
                guard incident?.id == current.id, incident?.state == current.state else { return }
                incident?.serverAccepted = true; incident?.shareURL = response.shareUrl
                incident?.telegramAccepted = response.accepted ?? 0; incident?.telegramPending = response.pending ?? 0
                incident?.error = nil; incident?.endPending = false
                if let state = response.state, state != "active" {
                    incident?.state = state; evidence.stop(); location.stop("sos")
                }
                if let incident { persist(incident, "incident.json") }
            } catch {
                if incident?.id == current.id { incident?.error = error.localizedDescription }
            }
        }
        if let current = journey, current.serverArmed || current.serverPending || current.endPending {
            do {
                var body: [String: Any] = ["journeyId": current.id]
                if let fix = location.fix { body["lat"] = fix.lat; body["lng"] = fix.lng; body["accuracyM"] = fix.accuracy; body["fixAt"] = ISO8601DateFormatter().string(from: fix.at) }
                let result = try await backend.safety(current.endPending ? "journeyEnd" : "journeyStatus", body)
                guard journey?.id == current.id else { return }
                if current.endPending { journey?.endPending = false; journey?.serverArmed = false; journey?.serverPending = false }
                else if result.state == "active" { journey?.serverArmed = true; journey?.serverPending = false }
                else if result.state == "ended" {
                    journey?.state = "ended"; journey?.serverArmed = false; journey?.serverPending = false
                    location.stop("journey"); Notifications.shared.cancel("journey")
                }
                if result.state == "alerted" {
                    journey?.state = "alerted"; location.stop("journey")
                    journey?.serverPending = false
                    if incident?.state != "active" {
                        incident = Incident(id: current.incidentId, trigger: "journey", test: settings.testMode, shareURL: result.shareUrl, serverAccepted: true, lastFix: location.fix)
                        if let incident { persist(incident, "incident.json") }
                        location.start("sos")
                        log("The server raised a missed-check-in SOS. Open SOS to review alerts.")
                    }
                }
                if let journey { persist(journey, "journey.json") }
            } catch { if journey?.endPending == true { self.error = "Journey cancellation is waiting for internet. Contacts may still be alerted until the server confirms." } }
        }
    }
    var sosMessage: String {
        let prefix = incident?.test == true || settings.testMode || demo ? "TEST SOS" : "SOS"
        return "\(prefix) from \(profile?.displayName ?? "your contact"). I may need help. " +
            (incident?.shareURL.map { "Live location: \($0). " } ?? "") +
            ((location.fix ?? incident?.lastFix).map { "Last location: \($0.mapsURL) (±\(Int($0.accuracy)) m, \($0.at.formatted()))." } ?? "Location unavailable.")
    }
    func composeSOS() {
        let numbers = settings.testMode || demo ? [settings.testNumber].filter { !$0.isEmpty } : contacts.filter(\.receivesSos).map(\.phone)
        guard !numbers.isEmpty else { error = settings.testMode ? "Set a test phone number first." : "Add a trusted contact first."; return }
        messageDraft = MessageDraft(recipients: numbers, body: sosMessage)
    }
    func dial() {
        let number = settings.testMode || demo ? settings.testNumber : settings.callNumber
        let cleaned = number.filter { $0.isNumber || $0 == "+" }
        guard !cleaned.isEmpty, let url = URL(string: "tel:\(cleaned)") else { error = "Set a valid call number in settings."; return }
        UIApplication.shared.open(url) { [weak self] opened in Task { @MainActor in
            self?.log(opened ? "Call confirmation opened. Connection is not confirmed." : "This device cannot open the phone call.")
        } }
    }
    func setForeground(_ active: Bool) {
        evidence.setForeground(active)
        deterrents.monitorKnocks(active && settings.pocketGesture && profile?.verifiedFemale == true)
        if !active {
            cancelCountdown() // A suspended timer must not claim it completed an emergency action.
            if deterrents.torchOn { deterrents.setTorch(false) }
        } else { Task { await synchronize() } }
    }
    func scheduleFakeCall(seconds: Double) async {
        do {
            if seconds <= 0 { deterrents.ring(); return }
            guard try await Notifications.shared.authorize() else { throw AppFailure.message("Enable notifications to schedule a fake call.") }
            try await Notifications.shared.schedule(id: "fake-call", title: "Incoming call from \(settings.fakeCaller)", body: "Tap to open", after: seconds, category: "fake-call")
            log("Fake call scheduled. In the background, iOS shows a notification to open.")
        } catch { report(error) }
    }
    func startJourney(name: String, coordinate: CLLocationCoordinate2D, minutes: Int) async {
        guard journey?.state != "active", journey?.endPending != true else { error = "End the current journey first."; return }
        var current = JourneyState(name: name, lat: coordinate.latitude, lng: coordinate.longitude, deadline: Date().addingTimeInterval(Double(minutes) * 60))
        if !demo && !settings.testMode {
            do {
                let readiness = try await backend.safety("capabilities")
                guard readiness.schedulerReady == true else { throw AppFailure.message("Server journey monitoring is not configured. Use Test Mode for an on-screen practice journey.") }
                // Persist the ID before the request: a timed-out response must not orphan an armed deadline.
                current.serverPending = true; journey = current; persist(current, "journey.json")
                let response = try await backend.safety("journeyStart", ["journeyId": current.id, "incidentId": current.incidentId, "name": name,
                    "lat": coordinate.latitude, "lng": coordinate.longitude, "deadline": ISO8601DateFormatter().string(from: current.deadline)])
                current.serverArmed = response.state == "active"
                guard current.serverArmed else { throw AppFailure.message("Journey monitoring was not confirmed.") }
                current.serverPending = false
            } catch { report(error); return }
        }
        journey = current; persist(current, "journey.json"); location.start("journey")
        do {
            if try await Notifications.shared.authorize() {
                try await Notifications.shared.schedule(id: "journey", title: "Journey check-in", body: "Open Security4Her and confirm you are safe before your deadline.", after: max(1, current.deadline.timeIntervalSinceNow - 120), category: "journey")
            }
        } catch { report(error) }
        log(current.serverArmed ? "Journey deadline confirmed by the server." : "Practice journey started. No automatic remote alerts.")
    }
    func extendJourney(minutes: Int = 10) async {
        guard var current = journey, current.state == "active" else { return }
        let next = Date().addingTimeInterval(Double(minutes) * 60)
        do {
            if current.serverArmed { _ = try await backend.safety("journeyExtend", ["journeyId": current.id, "deadline": ISO8601DateFormatter().string(from: next)]) }
            current.deadline = next; current.checkUntil = nil; current.checkReason = nil; current.stillSince = Date(); current.anchor = location.fix
            journey = current; persist(current, "journey.json")
            try await Notifications.shared.schedule(id: "journey", title: "Journey check-in", body: "Confirm you are safe before your deadline.", after: max(1, next.timeIntervalSinceNow - 120), category: "journey")
        } catch { report(error) }
    }
    func endJourney() async {
        guard var current = journey else { return }
        current.state = "ended"; current.endPending = current.serverArmed || current.serverPending
        journey = current; persist(current, "journey.json"); location.stop("journey"); Notifications.shared.cancel("journey")
        await synchronize()
    }
    private func evaluateJourney(_ fix: Fix) {
        guard var current = journey, current.state == "active", !current.serverPending, fix.isFresh else { return }
        let here = CLLocation(latitude: fix.lat, longitude: fix.lng)
        let distance = here.distance(from: .init(latitude: current.lat, longitude: current.lng))
        if distance < 150 { Task { await endJourney() }; return }
        if let anchor = current.anchor, here.distance(from: .init(latitude: anchor.lat, longitude: anchor.lng)) > 80 {
            current.anchor = fix; current.stillSince = Date()
        } else if current.anchor == nil { current.anchor = fix; current.stillSince = Date() }
        if current.checkUntil == nil {
            let away = current.lastDistance.map { distance > $0 + 500 } ?? false
            if away || Date().timeIntervalSince(current.stillSince) > (settings.testMode ? 60 : 600) {
                current.checkUntil = Date().addingTimeInterval(settings.testMode ? 30 : 120)
                current.checkReason = away ? "Moving away from your destination" : "You have stopped moving"
                if current.serverArmed, let until = current.checkUntil {
                    let id = current.id
                    Task {
                        do { _ = try await backend.safety("journeyExtend", ["journeyId": id, "deadline": ISO8601DateFormatter().string(from: until)]) }
                        catch { report(error) }
                    }
                }
                Task { try? await Notifications.shared.schedule(id: "journey", title: "Are you safe?", body: "Open Security4Her to check in.", after: 1, category: "journey") }
            }
        }
        current.lastDistance = min(current.lastDistance ?? distance, distance)
        journey = current; persist(current, "journey.json")
    }
    private func overdueJourney() async {
        guard journey?.state == "active" else { return }
        journey?.state = "alerted"; location.stop("journey")
        if let journey { persist(journey, "journey.json") }
        await startSOS(trigger: "journey")
    }
    var liveURL: URL? {
        guard let topic = liveTopic else { return nil }
        let base = backend.config.liveURL.isEmpty ? "https://ntfy.sh/\(topic)" : backend.config.liveURL + "?t=\(topic)"
        return URL(string: base)
    }
    func startLive() {
        liveTopic = "s4h-" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        liveUntil = Date().addingTimeInterval(3600); liveStatus = "Waiting for location"; lastLive = .distantPast; location.start("live")
    }
    func stopLive() { liveTopic = nil; liveUntil = nil; liveStatus = "Sharing ended"; location.stop("live") }
    private func publishLiveIfNeeded() {
        guard !livePublishing, let topic = liveTopic, let fix = location.fix, fix.isFresh, Date().timeIntervalSince(lastLive) >= 20 else { return }
        livePublishing = true; lastLive = Date()
        Task {
            defer { livePublishing = false }
            do {
                var request = URLRequest(url: URL(string: "https://ntfy.sh")!, timeoutInterval: 15)
                request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.httpBody = try JSONSerialization.data(withJSONObject: ["topic": topic, "title": "Live location", "message": "\(fix.lat), \(fix.lng), accurate to \(Int(fix.accuracy)) m", "click": fix.mapsURL])
                let (_, response) = try await URLSession.shared.data(for: request)
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw AppFailure.message("Live map update failed.") }
                if liveTopic == topic { liveStatus = "Updated \(Date().formatted(date: .omitted, time: .shortened))" }
            } catch { if liveTopic == topic { liveStatus = "Update failed; retrying when the app can run" } }
        }
    }
}

struct MessageDraft: Identifiable {
    let id = UUID()
    let recipients: [String]
    let body: String
}
