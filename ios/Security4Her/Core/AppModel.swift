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
    @Published var profile: Profile? { didSet { retryEvidenceCopies() } }
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
    private var syncAgain = false
    private var journeyStarting = false
    @Published var readiness: SafetyResponse?
    private var lastSync = Date.distantPast
    private var lastLive = Date.distantPast
    private var lastBackupRetry = Date.distantPast
    private var authSession: ASWebAuthenticationSession?
    private var observers = Set<AnyCancellable>()
    private var launchTask: Task<Void, Never>?
    private var startInProgress = false
    private var livePublishing = false
    private var pendingLiveEnds: [String: Date] = Disk.read("live-ends.json") ?? [:]
    private var pendingAction: (SafetyAction, Date)?
    override private init() {
        super.init()
        if let saved: LiveSession = Disk.read("live-session.json") {
            liveTopic = saved.topic; liveUntil = saved.until
            if saved.until <= Date() { stopLive() } else { location.start("live") }
        }
        location.onFix = { [weak self] fix in self?.received(fix) }
        evidence.location = { [weak self] in self?.location.fix }
        deterrents.onKnock = { [weak self] in self?.beginCountdown(trigger: "pocket") }
        Notifications.shared.configure()
        Notifications.shared.onAction = { [weak self] action in
            guard let self else { return }
            if action == "fake-call" { Task { _ = try? await self.requestSafetyAction(.fakeCall) } }
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
        if canRestoreDemo {
            activateDemo()
        } else if backend.config.configured, backend.session != nil {
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
        refreshJourneyPresentation()
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
        guard !demo || !hasUnresolvedSafetySession else {
            error = "End the demo safety session before signing in."; return
        }
        evidence.backup.objectWillChange.sink { [weak self] in self?.objectWillChange.send() }.store(in: &observers)
        busy = true; defer { busy = false }
        do {
            profile = try await (creating ? backend.signUp(name: name, email: email, password: password) : backend.signIn(email: email, password: password))
            demo = false
            UserDefaults.standard.set(false, forKey: "offlineDemo")
            if let profile { persist(profile, "profile.json") }
            if profile?.verifiedFemale == true { try await loadContacts() }
        } catch { report(error) }
    }
    private var hasUnresolvedSafetySession: Bool {
        countdown != nil || startInProgress || journeyStarting || incident?.state == "active" || incident?.endPending == true || journey?.state == "active" ||
            journey?.serverPending == true || journey?.endPending == true
    }
    private var canRestoreDemo: Bool {
        // Never hide a live or uncertain server session behind offline demo mode.
        if let incident, incident.state == "active" || incident.endPending {
            guard incident.test, !incident.serverAccepted, !incident.endPending else { return false }
        }
        if let journey, journey.state == "active" || journey.serverArmed || journey.endPending || journey.serverPending {
            guard !journey.serverArmed, !journey.serverPending, !journey.endPending else { return false }
        }
        if UserDefaults.standard.bool(forKey: "offlineDemo") { return true }
        // Older builds saved demo sessions but did not save the selected demo account.
        // Recover only local practice state with no saved authenticated account.
        let cached: Profile? = Disk.read("profile.json")
        guard backend.session == nil, cached == nil, hasUnresolvedSafetySession else { return false }
        if let incident, incident.state == "active" {
            let owners = evidence.cases.filter { $0.id == incident.id }.map(\.ownerId)
            guard owners.allSatisfy({ $0 == "demo" }) else { return false }
        }
        return true
    }
    private func activateDemo() {
        demo = true; settings.testMode = true
        profile = Profile(id: "demo", displayName: "Demo", verifiedFemale: true)
        contacts = []
        UserDefaults.standard.set(true, forKey: "offlineDemo")
    }
    func enterDemo() {
        guard !hasUnresolvedSafetySession || canRestoreDemo else {
            error = "A saved safety session needs attention. Sign in to the account that started it and end it before entering the demo."; return
        }
        activateDemo(); loading = false
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
        guard !hasUnresolvedSafetySession else {
            error = "End and synchronize active safety sessions before signing out."; return
        }
        if !demo { await backend.signOut() }
        Disk.remove("profile.json")
        UserDefaults.standard.set(false, forKey: "offlineDemo")
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
    func requestSafetyAction(_ action: SafetyAction) async throws -> String {
        await launch()
        guard profile?.verifiedFemale == true else {
            throw AppFailure.message("Open Security4Her and finish sign-in and setup. No SOS has started.")
        }
        if action == .sos {
            if incident?.state == "active" { selectedTab = 0; return "An SOS is already active. Open Security4Her to review it." }
            guard incident?.endPending != true else { throw AppFailure.message("The previous SOS is still ending. Open Security4Her to synchronize it.") }
            if countdown != nil { return "The SOS countdown is already running. Keep Security4Her open." }
        }
        pendingAction = (action, Date())
        consumeSafetyAction()
        if action == .journey { return "Open Security4Her to check in. Your journey has not been ended." }
        if pendingAction != nil { return "Open and unlock Security4Her to continue. No SOS has started yet." }
        return action == .sos ? "The SOS countdown has started. Keep Security4Her open." : "The simulated call is open. No real call was placed."
    }
    private func consumeSafetyAction() {
        guard UIApplication.shared.applicationState == .active, let (action, created) = pendingAction else { return }
        pendingAction = nil
        guard Date().timeIntervalSince(created) < 30 else { log("Fast-access request expired. Start again from the app."); return }
        switch action {
        case .sos: selectedTab = 0; beginCountdown()
        case .fakeCall: deterrents.ring()
        case .journey: selectedTab = 1
        }
    }
    func openSafetyURL(_ url: URL) async {
        guard url.scheme == "security4her" else { return }
        let action: SafetyAction?
        switch url.host { case "sos": action = .sos; case "fake-call": action = .fakeCall; case "journey": action = .journey; default: action = nil }
        guard let action else { return }
        do { _ = try await requestSafetyAction(action) } catch { report(error) }
    }
    func cancelCountdown() { countdownTask?.cancel(); countdownTask = nil; countdown = nil }
    func startSOS(trigger: String) async {
        guard profile?.verifiedFemale == true, incident?.endPending != true else {
            error = "Finish account setup and synchronize the previous SOS before starting another."; return
        }
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
        if journey?.incidentId == current.id, journey?.state == "alerted" {
            journey?.state = "ended"; journey?.endPending = !demo
            journey?.revision = (journey?.mutationRevision ?? 0) + 1
            if let journey { persist(journey, "journey.json") }
            refreshJourneyPresentation()
        }
        log("SOS ended locally. Synchronizing the server status.")
        await synchronize()
    }
    private func received(_ fix: Fix) {
        if incident?.state == "active" { incident?.lastFix = fix; if let incident { persist(incident, "incident.json") } }
        evaluateJourney(fix)
        location.confirmArrival(journey?.state == "active" && journey?.arrivalCandidateAt != nil)
        if Date().timeIntervalSince(lastSync) > 20 { Task { await synchronize() } }
        publishLiveIfNeeded()
    }
    private func tick() {
        if Date().timeIntervalSince(lastBackupRetry) >= 60 { retryEvidenceCopies() }
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
        guard profile?.verifiedFemale == true else { return }
        if syncBusy { syncAgain = true; return }
        lastSync = Date()
        guard !demo else { return }
        syncBusy = true
        defer {
            syncBusy = false
            if syncAgain { syncAgain = false; Task { await synchronize() } }
            refreshJourneyPresentation()
        }
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
                var body: [String: Any] = ["journeyId": current.id, "revision": current.mutationRevision,
                                           "reason": current.endReason ?? "ended"]
                if let fix = location.fix { body["lat"] = fix.lat; body["lng"] = fix.lng; body["accuracyM"] = fix.accuracy; body["fixAt"] = ISO8601DateFormatter().string(from: fix.at) }
                let action: String
                if current.endPending { action = "journeyEnd" }
                else if current.serverPending {
                    action = "journeyStart"
                    body["incidentId"] = current.incidentId; body["name"] = current.name
                    body["deadline"] = ISO8601DateFormatter().string(from: current.deadline)
                    body["test"] = current.test ?? false
                } else if let pending = current.pendingDeadline {
                    action = "journeyExtend"; body["deadline"] = ISO8601DateFormatter().string(from: pending)
                } else { action = "journeyStatus" }
                let result = try await backend.safety(action, body)
                guard SafetyPolicy.acceptsJourneyResponse(id: current.id, revision: current.mutationRevision, state: current.state,
                    currentID: journey?.id, currentRevision: journey?.mutationRevision ?? 0, currentState: journey?.state) else { return }
                journey?.acknowledgedAt = Date()
                journey?.revision = max(current.mutationRevision, result.revision ?? 0)
                if let deadline = result.deadlineDate { journey?.deadline = deadline }
                if (result.revision ?? 0) >= current.mutationRevision { journey?.pendingDeadline = nil }
                if current.endPending { journey?.endPending = false; journey?.serverArmed = false; journey?.serverPending = false }
                else if result.state == "active" { journey?.serverArmed = true; journey?.serverPending = false }
                else if result.state == "ended" {
                    journey?.state = "ended"; journey?.serverArmed = false; journey?.serverPending = false
                    journey?.pendingDeadline = nil
                    location.stop("journey"); Notifications.shared.cancel("journey")
                }
                if result.state == "alerted" {
                    journey?.state = "alerted"; location.stop("journey")
                    journey?.serverPending = false
                    if SafetyPolicy.shouldRestoreIncident(id: current.incidentId, serverState: result.incidentState,
                                                          localID: incident?.id, localState: incident?.state) {
                        incident = Incident(id: current.incidentId, trigger: "journey", test: current.test ?? false, shareURL: result.shareUrl, serverAccepted: true, lastFix: location.fix)
                        if let incident { persist(incident, "incident.json") }
                        location.start("sos")
                        log("The server raised a missed-check-in SOS. Open SOS to review alerts.")
                    }
                }
                if let journey { persist(journey, "journey.json") }
            } catch {
                if journey?.endPending == true { self.error = "Journey cancellation is waiting for internet. Contacts may still be alerted until the server confirms." }
                else { log("Journey synchronization failed; the saved request will retry. \(error.localizedDescription)") }
            }
        }
    }
    var sosMessage: String {
        let prefix = incident?.test == true || settings.testMode || demo ? "TEST SOS" : "SOS"
        return "\(prefix) from \(profile?.displayName ?? "your contact"). I may need help. " +
            (incident?.shareURL.map { "Live location: \($0). " } ?? "") +
            ((location.fix ?? incident?.lastFix).map { "Last location: \($0.mapsURL) (±\(Int($0.accuracy)) m, \($0.at.formatted()))." } ?? "Location unavailable.")
    }
    func composeSOS() {
        let practice = demo || (incident?.state == "active" ? incident?.test == true : settings.testMode)
        let numbers = practice ? [settings.testNumber].filter { !$0.isEmpty } : contacts.filter(\.receivesSos).map(\.phone)
        guard !numbers.isEmpty else { error = settings.testMode ? "Set a test phone number first." : "Add a trusted contact first."; return }
        messageDraft = MessageDraft(recipients: numbers, body: sosMessage)
    }
    func dial() {
        let practice = demo || (incident?.state == "active" ? incident?.test == true : settings.testMode)
        let number = practice ? settings.testNumber : settings.callNumber
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
        } else { consumeSafetyAction(); refreshJourneyPresentation(); retryEvidenceCopies(); Task { await synchronize() } }
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
        guard profile?.verifiedFemale == true else { error = "Finish account setup before starting a journey."; return }
        guard !journeyStarting, journey?.state != "active", journey?.endPending != true else { error = "End the current journey first."; return }
        journeyStarting = true; defer { journeyStarting = false }
        var current = JourneyState(name: name, lat: coordinate.latitude, lng: coordinate.longitude, deadline: Date().addingTimeInterval(Double(minutes) * 60))
        current.test = demo || settings.testMode
        if !demo && !settings.testMode {
            do {
                let readiness = try await backend.safety("capabilities")
                self.readiness = readiness
                guard readiness.schedulerReady == true, readiness.deliveryReady == true,
                      (readiness.eligibleRecipients ?? 0) > 0 else {
                    throw AppFailure.message("Journey monitoring needs a running server scheduler, configured delivery, and a linked Telegram contact. Refresh setup after linking a contact.")
                }
                current.serverPending = true
            } catch { report(error); return }
        }
        journey = current; persist(current, "journey.json"); location.start("journey")
        if current.serverPending { await synchronize() }
        else { refreshJourneyPresentation(); log("Practice journey started. No automatic remote alerts.") }
    }
    func extendJourney(minutes: Int = 10) async {
        guard var current = journey, current.state == "active" else { return }
        guard !current.serverPending else { error = "Wait for the server to confirm the journey, or end it."; return }
        let next = SafetyPolicy.extendedDeadline(current.pendingDeadline ?? current.deadline, now: Date(), minutes: minutes)
        current.revision = current.mutationRevision + 1
        if current.serverArmed { current.pendingDeadline = next } else { current.deadline = next }
        current.checkUntil = nil; current.checkReason = nil; current.stillSince = Date(); current.anchor = location.fix
        current.arrivalCandidateAt = nil
        journey = current; persist(current, "journey.json"); refreshJourneyPresentation()
        await synchronize()
    }
    func endJourney(reason: String = "ended") async {
        guard var current = journey else { return }
        current.state = "ended"; current.endPending = current.serverArmed || current.serverPending
        current.revision = current.mutationRevision + 1; current.pendingDeadline = nil; current.endReason = reason
        journey = current; persist(current, "journey.json"); location.stop("journey"); Notifications.shared.cancel("journey")
        location.confirmArrival(false)
        refreshJourneyPresentation()
        await synchronize()
    }
    private func evaluateJourney(_ fix: Fix) {
        guard var current = journey, current.state == "active", !current.serverPending, fix.isFresh else { return }
        let here = CLLocation(latitude: fix.lat, longitude: fix.lng)
        let distance = here.distance(from: .init(latitude: current.lat, longitude: current.lng))
        if SafetyPolicy.arrivalCandidate(distance: distance, accuracy: fix.accuracy, at: fix.at, now: Date()) {
            if SafetyPolicy.confirmsArrival(first: current.arrivalCandidateAt, at: fix.at) {
                Task { await endJourney(reason: "arrived") }; return
            }
            if current.arrivalCandidateAt == nil || fix.at.timeIntervalSince(current.arrivalCandidateAt!) >= 120 {
                current.arrivalCandidateAt = fix.at
            }
        } else { current.arrivalCandidateAt = nil }
        if let anchor = current.anchor, here.distance(from: .init(latitude: anchor.lat, longitude: anchor.lng)) > 80 {
            current.anchor = fix; current.stillSince = Date()
        } else if current.anchor == nil { current.anchor = fix; current.stillSince = Date() }
        if current.checkUntil == nil {
            let away = current.lastDistance.map { distance > $0 + 500 } ?? false
            if away || Date().timeIntervalSince(current.stillSince) > (settings.testMode ? 60 : 600) {
                current.checkUntil = Date().addingTimeInterval(settings.testMode ? 30 : 120)
                current.checkReason = away ? "Moving away from your destination" : "You have stopped moving"
                if current.serverArmed, let until = current.checkUntil {
                    current.pendingDeadline = min(current.deadline, until)
                    current.revision = current.mutationRevision + 1
                    Task { await synchronize() }
                }
                Task { try? await Notifications.shared.schedule(id: "journey", title: "Are you safe?", body: "Open Security4Her to check in.", after: 1, category: "journey") }
            }
        }
        current.lastDistance = min(current.lastDistance ?? distance, distance)
        journey = current; persist(current, "journey.json")
    }
    func refreshReadiness() async {
        guard !demo else { readiness = nil; return }
        do { readiness = try await backend.safety("capabilities") } catch { readiness = nil; report(error) }
    }
    func retryEvidenceCopies() {
        lastBackupRetry = Date()
        evidence.backup.activate(ownerID: profile?.id, cases: evidence.cases, directory: evidence.directory)
    }
    func chooseEvidenceFolder(_ url: URL) {
        guard let owner = profile?.id else { return }
        do { try evidence.backup.choose(url, ownerID: owner); retryEvidenceCopies() } catch { report(error) }
    }
    func updateReminders() { refreshJourneyPresentation() }
    private func refreshJourneyPresentation() {
        JourneyPresentation.shared.update(journey, alarmEnabled: settings.alarmReminders == true)
    }
    private func overdueJourney() async {
        guard journey?.state == "active" else { return }
        journey?.state = "alerted"; location.stop("journey")
        if let journey { persist(journey, "journey.json") }
        refreshJourneyPresentation()
        await startSOS(trigger: "journey")
    }
    var liveURL: URL? {
        guard let topic = liveTopic else { return nil }
        guard var parts = URLComponents(string: backend.config.liveURL), parts.scheme == "https", parts.host != nil else { return nil }
        parts.queryItems = (parts.queryItems ?? []) + [URLQueryItem(name: "t", value: topic)]
        return parts.url
    }
    func startLive() {
        guard let url = URL(string: backend.config.liveURL), url.scheme == "https", url.host != nil else {
            error = "Live-map sharing is not configured in this build. Contact the app provider to enable it."; return
        }
        if liveTopic != nil { stopLive() }
        liveTopic = "s4h-" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        liveUntil = Date().addingTimeInterval(3600); liveStatus = "Waiting for location"; lastLive = .distantPast; location.start("live")
        persist(LiveSession(topic: liveTopic!, until: liveUntil!), "live-session.json")
    }
    func stopLive() {
        if let topic = liveTopic { pendingLiveEnds[topic] = Date(); persist(pendingLiveEnds, "live-ends.json") }
        liveTopic = nil; liveUntil = nil; liveStatus = "Sharing ended; sending stop update"; location.stop("live")
        Disk.remove("live-session.json"); publishLiveIfNeeded()
    }
    private func publishLiveIfNeeded() {
        guard !livePublishing else { return }
        if let topic = pendingLiveEnds.keys.sorted().first {
            guard Date().timeIntervalSince(lastLive) >= 20 else { return }
            lastLive = Date()
            livePublishing = true
            Task {
                defer { livePublishing = false }
                do {
                    try await publishLive(topic: topic, payload: ["version": 1, "state": "ended"])
                    pendingLiveEnds.removeValue(forKey: topic); persist(pendingLiveEnds, "live-ends.json")
                    if liveTopic == nil { liveStatus = "Sharing ended; stop update published" }
                } catch { if liveTopic == nil { liveStatus = "Sharing ended locally; stop update waiting for internet" } }
            }
            return
        }
        guard !livePublishing, let topic = liveTopic, let fix = location.fix, fix.isFresh, Date().timeIntervalSince(lastLive) >= 20 else { return }
        livePublishing = true; lastLive = Date()
        Task {
            defer { livePublishing = false }
            do {
                try await publishLive(topic: topic, payload: ["version": 1, "state": "active", "lat": fix.lat,
                    "lng": fix.lng, "accuracyM": fix.accuracy, "fixAt": ISO8601DateFormatter().string(from: fix.at)], click: fix.mapsURL)
                if liveTopic == topic { liveStatus = "Updated \(Date().formatted(date: .omitted, time: .shortened))" }
            } catch { if liveTopic == topic { liveStatus = "Update failed; retrying when the app can run" } }
        }
    }
    private func publishLive(topic: String, payload: [String: Any], click: String? = nil) async throws {
        var body: [String: Any] = ["topic": topic, "title": "Security4Her location",
            "message": String(decoding: try JSONSerialization.data(withJSONObject: payload), as: UTF8.self)]
        if let click { body["click"] = click }
        var request = URLRequest(url: URL(string: "https://ntfy.sh")!, timeoutInterval: 15)
        request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (_, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw AppFailure.message("Live map update failed.") }
    }
}

private struct LiveSession: Codable { let topic: String; let until: Date }

struct MessageDraft: Identifiable {
    let id = UUID()
    let recipients: [String]
    let body: String
}
